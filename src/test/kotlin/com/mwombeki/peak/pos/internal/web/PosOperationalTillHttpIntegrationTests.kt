package com.mwombeki.peak.pos.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import com.mwombeki.peak.usermanagement.internal.application.DevicePairingService
import com.mwombeki.peak.usermanagement.internal.application.DeviceSessionService
import com.mwombeki.peak.usermanagement.internal.application.StaffCredentialService
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * A PIN till may search in-house stays to post F&B, and must not become STRONG
 * enough to read rooms or reservations. Switch Staff / Lock revokes the ops_
 * bearer without closing the drawer.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PosOperationalTillHttpIntegrationTests {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var pairing: DevicePairingService
    @Autowired private lateinit var sessions: DeviceSessionService
    @Autowired private lateinit var credentials: StaffCredentialService

    @AfterTest
    fun resetSession() {
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun aPinSessionCanSearchRoomChargeCandidatesAndCannotReadRooms() {
        val till = seedOperationalTill()

        mockMvc.perform(
            get("/api/v1/properties/${till.propertyId}/pos/room-charge-candidates")
                .secure(true)
                .queryParam("query", "204")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${till.token}")
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-candidates-${till.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].stayId").value(till.stayId.toString()))
            .andExpect(jsonPath("$[0].roomId").value(till.roomId.toString()))
            .andExpect(jsonPath("$[0].roomNumber").value("204"))
            .andExpect(jsonPath("$[0].guestDisplayName").value("Amina Hassan"))
            .andExpect(jsonPath("$[0].postingEligible").value(true))
            .andExpect(jsonPath("$[0].folioId").doesNotExist())
            .andExpect(jsonPath("$[0].reservationId").doesNotExist())
            .andExpect(jsonPath("$[0].passport").doesNotExist())
            .andExpect(jsonPath("$[0].email").doesNotExist())
            .andExpect(jsonPath("$[0].phone").doesNotExist())

        mockMvc.perform(
            get("/api/v1/properties/${till.propertyId}/rooms")
                .secure(true)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${till.token}")
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-rooms-${till.tenantId}"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(containsString("Session class")))

        mockMvc.perform(
            get("/api/v1/properties/${till.propertyId}/reservations")
                .secure(true)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${till.token}")
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-reservations-${till.tenantId}"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(containsString("Session class")))
    }

    @Test
    fun lockingTheCurrentPinSessionRevokesTheBearer() {
        val till = seedOperationalTill()

        mockMvc.perform(
            delete("/api/v1/staff/sessions/current")
                .secure(true)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${till.token}")
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-lock-${till.tenantId}"),
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            get("/api/v1/properties/${till.propertyId}/pos/room-charge-candidates")
                .secure(true)
                .queryParam("query", "204")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${till.token}")
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-locked-${till.tenantId}"),
        )
            .andExpect(status().isForbidden)
    }

    private fun seedOperationalTill(): Till {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val propertyId = UUID.randomUUID()
        val managerId = UUID.randomUUID()
        val staffUserId = UUID.randomUUID()
        val roleId = UUID.randomUUID()
        val roomTypeId = UUID.randomUUID()
        val roomId = UUID.randomUUID()
        val stayId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        val folioId = UUID.randomUUID()
        val guestId = UUID.randomUUID()
        val pin = "418205"

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId, "Plan $planId", "plan-$planId",
        )
        jdbcTemplate.update(
            "INSERT INTO tenants (id, name, slug, schema_name, plan_id) VALUES (?, ?, ?, ?, ?)",
            tenantId, "Tenant $tenantId", "tenant-$tenantId",
            "tenant_$tenantId".replace("-", "_"), planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Manager', ?, 'active', true)
            """.trimIndent(),
            managerId, tenantId, "mgr-$managerId@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, status, is_active)
            VALUES (?, ?, 'Amina Hassan', 'active', true)
            """.trimIndent(),
            staffUserId, tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, code, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            propertyId, tenantId, "Property $propertyId", "P${propertyId.toString().take(8)}",
        )
        val staffNumber = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT allocate_property_staff_number(?, ?, ?)",
                String::class.java,
                tenantId, propertyId, staffUserId,
            ),
        )

        listOf("pos", "property", "reservations", "tenant_admin").forEach { moduleId ->
            jdbcTemplate.update(
                """
                INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
                VALUES (?, ?, true, true)
                ON CONFLICT ON CONSTRAINT tenant_modules_tenant_id_module_id_key
                DO UPDATE SET is_enabled = true, is_configured = true
                """.trimIndent(),
                tenantId,
                moduleId,
            )
            if (moduleId != "tenant_admin") {
                jdbcTemplate.update(
                    """
                    INSERT INTO property_modules (
                        tenant_id, property_id, module_id, is_enabled, is_configured
                    )
                    VALUES (?, ?, ?, true, true)
                    ON CONFLICT ON CONSTRAINT property_modules_tenant_id_property_id_module_id_key
                    DO UPDATE SET is_enabled = true, is_configured = true
                    """.trimIndent(),
                    tenantId,
                    propertyId,
                    moduleId,
                )
            }
        }

        jdbcTemplate.update(
            "INSERT INTO roles (id, tenant_id, name, is_active) VALUES (?, ?, 'Till waiter', true)",
            roleId,
            tenantId,
        )
        listOf("pos.order.settle", "property.view", "reservations.view").forEach { code ->
            jdbcTemplate.update(
                """
                INSERT INTO permissions (id, tenant_id, code, description)
                SELECT gen_random_uuid(), ?, pc.code, pc.description
                FROM permission_catalog pc
                WHERE pc.code = ?
                ON CONFLICT (tenant_id, code) DO UPDATE SET
                    description = EXCLUDED.description,
                    updated_at = now()
                """.trimIndent(),
                tenantId,
                code,
            )
            jdbcTemplate.update(
                """
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions WHERE tenant_id = ? AND code = ?
                """.trimIndent(),
                roleId,
                tenantId,
                code,
            )
        }
        jdbcTemplate.update(
            """
            INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            staffUserId, propertyId, roleId, tenantId,
        )

        jdbcTemplate.update(
            """
            INSERT INTO room_types (
                id, tenant_id, property_id, name, code, base_price,
                max_adults, max_children, max_occupancy, is_active
            ) VALUES (?, ?, ?, 'Standard', 'STD', 100.00, 2, 1, 3, true)
            """.trimIndent(),
            roomTypeId, tenantId, propertyId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO rooms (
                id, tenant_id, property_id, room_type_id, room_number, status
            ) VALUES (?, ?, ?, ?, '204', 'occupied')
            """.trimIndent(),
            roomId, tenantId, propertyId, roomTypeId,
        )
        jdbcTemplate.update(
            "INSERT INTO guests (id, tenant_id, full_name) VALUES (?, ?, 'Amina Hassan')",
            guestId, tenantId,
        )
        val today = LocalDate.now()
        jdbcTemplate.update(
            """
            INSERT INTO reservations (
                id, tenant_id, property_id, primary_guest_id, status,
                check_in_date, check_out_date, adults, children
            ) VALUES (?, ?, ?, ?, 'checked_in', ?, ?, 1, 0)
            """.trimIndent(),
            reservationId, tenantId, propertyId, guestId, today, today.plusDays(1),
        )
        jdbcTemplate.update(
            """
            INSERT INTO folios (
                id, tenant_id, property_id, reservation_id, folio_type, status
            ) VALUES (?, ?, ?, ?, 'guest', 'open')
            """.trimIndent(),
            folioId, tenantId, propertyId, reservationId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO reservation_rooms (
                id, tenant_id, reservation_id, room_type_id, room_id, folio_id,
                check_in_date, check_out_date, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'checked_in')
            """.trimIndent(),
            UUID.randomUUID(), tenantId, reservationId, roomTypeId, roomId, folioId,
            today, today.plusDays(1),
        )
        jdbcTemplate.update(
            """
            INSERT INTO stays (
                id, tenant_id, reservation_id, room_id, status, check_in_time
            ) VALUES (?, ?, ?, ?, 'checked_in', now())
            """.trimIndent(),
            stayId, tenantId, reservationId, roomId,
        )

        val secret = credentials.issueActivation(tenantId, staffUserId, managerId)
        credentials.activate(tenantId, staffNumber, secret.plaintext, pin)
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val requested = pairing.requestPairing(publicKey(keys))
        val paired = pairing.approve(
            tenantId = tenantId,
            pairingCode = requested.code,
            approval = DevicePairingService.Approval(
                propertyId = propertyId,
                outletId = null,
                terminalName = "Till 1",
                mode = "POS",
            ),
            actorId = managerId,
        )
        val challenge = requireNotNull(sessions.issueChallenge(paired.deviceCode))
        val session = requireNotNull(
            sessions.login(
                deviceCode = paired.deviceCode,
                challengeId = challenge.challengeId,
                signature = sign(keys, challenge.nonce),
                staffNumber = staffNumber.substringAfterLast('-'),
                pin = pin,
            ),
        )

        return Till(
            tenantId = tenantId,
            propertyId = propertyId,
            stayId = stayId,
            roomId = roomId,
            token = session.token,
        )
    }

    private fun publicKey(keys: KeyPair): String =
        Base64.getEncoder().encodeToString(keys.public.encoded)

    private fun sign(keys: KeyPair, nonceB64: String): String {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keys.private)
        signer.update(Base64.getDecoder().decode(nonceB64))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private data class Till(
        val tenantId: UUID,
        val propertyId: UUID,
        val stayId: UUID,
        val roomId: UUID,
        val token: String,
    )
}
