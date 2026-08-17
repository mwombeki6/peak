package com.mwombeki.peak.platformbilling

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.internal.OutboxWorkerProcessor
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.time.LocalDate
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * A hotel behind on its subscription can still get a guest out of the building.
 *
 * This is Peak's least negotiable promise, and it is the one whose failure would be discovered
 * by a guest at a front desk at 2am. `SubscriptionRestrictionIntegrationTests` proves
 * `can_access_module` permits checkout under suspension, and `GuestServiceIgnoresCommercialStateTests`
 * proves no guest-serving module reads commercial state. Both are true and neither is this.
 *
 * They establish that nothing *should* block the checkout. This establishes that the checkout
 * actually completes — through HTTP authorization, the route matrix, RLS, the folio, the
 * payment, the fiscal receipt and the stay transition, on a tenant Peak has suspended. Every
 * one of those sits outside the packages the source scan covers, and any of them could refuse
 * for a reason unrelated to a `lifecycle_status` lookup.
 *
 * The suspension lands **after check-in**, which is both the harder case and the real one: a
 * subscription lapses while a guest is in the building, not before they arrive.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
        "peak.security.outbound.allowed-provider-hosts[0]=api.clickpesa.com",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SuspendedTenantGuestCheckoutIntegrationTests {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var outboxWorkerProcessor: OutboxWorkerProcessor

    @AfterTest
    fun resetSession() {
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun aSuspendedHotelCanStillSettleAndCheckOutAnInHouseGuest() {
        val hotel = hotelWithAnArrivingGuest()
        val stay = checkIn(hotel)

        // The subscription lapses with the guest upstairs.
        suspend(hotel)

        // Settling the folio must work, or the guest cannot pay and checkout is moot.
        val cashSessionId = postForId(
            hotel, "/api/v1/properties/${hotel.propertyId}/payments/cash-sessions",
            "idem-cash-session-${hotel.tenantId}", "id", """{"openingFloat": 0.00}""",
        )
        postForId(
            hotel, "/api/v1/properties/${hotel.propertyId}/payments/cash",
            "idem-payment-${hotel.tenantId}", "id",
            """
            {
              "folioId": "${stay.folioId}",
              "cashSessionId": "$cashSessionId",
              "amount": 118.00
            }
            """.trimIndent(),
        )

        // A fiscal receipt is part of a lawful Tanzanian checkout, so it is part of the
        // promise. Suspending Peak's own billing must not make a hotel unable to issue one.
        postForId(
            hotel, "/api/v1/properties/${hotel.propertyId}/fiscal/provider-configs",
            "idem-fiscal-config-${hotel.tenantId}", "id",
            """
            {
              "providerCode": "contract_mock",
              "providerName": "Contract Fiscal Provider",
              "environment": "sandbox",
              "endpointUrl": "https://fiscal.test.invalid",
              "secretRef": "literal:fiscal-test-secret",
              "taxpayerIdentifier": "TIN-${hotel.tenantId.toString().take(8)}",
              "isDefault": true
            }
            """.trimIndent(),
        )
        postForId(
            hotel, "/api/v1/properties/${hotel.propertyId}/folios/${stay.folioId}/invoice",
            "idem-invoice-${hotel.tenantId}", "id", """{"dueDateDays": 0}""",
        )
        outboxWorkerProcessor.processBatchBlocking(OutboxDestination.FISCAL)

        mockMvc.perform(
            post("/api/v1/properties/${hotel.propertyId}/checkouts/${stay.stayId}")
                .secureJson("{}")
                .headersFor(hotel, "corr-checkout", "idem-checkout-${hotel.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("checked_out"))

        assertEquals(
            "suspended",
            jdbcTemplate.queryForObject(
                "SELECT lifecycle_status FROM tenant_control_states WHERE tenant_id = ?",
                String::class.java,
                hotel.tenantId,
            ),
            "the guest left and the hotel is still suspended — checkout must not have " +
                "quietly restored the tenant's commercial standing",
        )
    }

    /**
     * The other half, in the same journey rather than a separate fixture.
     *
     * Suspension has to bite somewhere or it is not suspension. Proving both against one
     * tenant is what shows the line is drawn where it was meant to be, rather than the guard
     * being off entirely — a checkout that succeeds because nothing is enforced at all would
     * satisfy the test above on its own.
     */
    @Test
    fun theSameSuspendedHotelIsStillDeniedGrowthAndAdministration() {
        val hotel = hotelWithAnArrivingGuest()
        checkIn(hotel)
        suspend(hotel)

        mockMvc.perform(
            post("/api/v1/tenants/${hotel.tenantId}/users/invitations")
                .secureJson(
                    """
                    {
                      "email": "new-hire-${hotel.tenantId}@example.com",
                      "fullName": "New Hire",
                      "tenantRoleId": "${hotel.invitableRoleId}",
                      "expiresInHours": 24
                    }
                    """.trimIndent(),
                )
                .headersFor(hotel, "corr-invite", "idem-invite-${hotel.tenantId}"),
        ).andExpect(status().isForbidden)

        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tenant_user_invitations WHERE tenant_id = ?",
                Int::class.java,
                hotel.tenantId,
            ),
            "a denial that still created the invitation would be worse than an allow, " +
                "because nothing would look wrong",
        )
    }

    /** Suspends the tenant exactly as `SubscriptionLifecycleService` does. */
    private fun suspend(hotel: Hotel) {
        jdbcTemplate.update(
            """
            UPDATE tenant_control_states
            SET lifecycle_status = 'suspended', subscription_status = 'past_due'
            WHERE tenant_id = ?
            """.trimIndent(),
            hotel.tenantId,
        )
        // The subscription row stays service-granting on purpose: an expired one drops out of
        // the set can_access_module reads, and the tenant would fail at
        // is_tenant_module_enabled before any restriction allowance was ever consulted.
        jdbcTemplate.update(
            "UPDATE tenant_subscriptions SET status = 'past_due' WHERE tenant_id = ?",
            hotel.tenantId,
        )
    }

    private fun checkIn(hotel: Hotel): Stay {
        val guestId = postForId(
            hotel, "/api/v1/properties/${hotel.propertyId}/guests",
            "idem-guest-${hotel.tenantId}", "id",
            """
            {
              "fullName": "In House Guest",
              "email": "guest-${hotel.tenantId}@example.com",
              "phonePrimary": "+255700000001",
              "dateOfBirth": "1990-01-01",
              "nationality": "TZ"
            }
            """.trimIndent(),
        )
        mockMvc.perform(
            post(
                "/api/v1/properties/${hotel.propertyId}/guests/$guestId" +
                    "/identity-documents/manual-verification",
            )
                .secureJson(
                    """
                    {
                      "documentType": "NIDA",
                      "documentNumber": "19900101123456789000",
                      "issuingCountry": "TZ",
                      "attestationReason": "Physical NIDA card inspected at reception"
                    }
                    """.trimIndent(),
                )
                .headersFor(hotel, "corr-identity", "idem-identity-${hotel.tenantId}"),
        ).andExpect(status().isOk)

        val today = LocalDate.now()
        val reservationId = postForId(
            hotel, "/api/v1/properties/${hotel.propertyId}/reservations",
            "idem-reservation-${hotel.tenantId}", "reservationId",
            """
            {
              "primaryGuestId": "$guestId",
              "roomTypeId": "${hotel.roomTypeId}",
              "roomId": "${hotel.roomId}",
              "checkInDate": "$today",
              "checkOutDate": "${today.plusDays(1)}",
              "adults": 1,
              "children": 0,
              "ratePerNight": 100.00
            }
            """.trimIndent(),
        )
        val folioId = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT id FROM folios WHERE tenant_id = ? AND reservation_id = ?",
                UUID::class.java,
                hotel.tenantId,
                reservationId,
            ),
        )
        val stayId = postForId(
            hotel, "/api/v1/properties/${hotel.propertyId}/checkins",
            "idem-checkin-${hotel.tenantId}", "stayId",
            """{"reservationId": "$reservationId", "roomId": "${hotel.roomId}"}""",
        )
        return Stay(stayId, folioId)
    }

    private fun postForId(
        hotel: Hotel,
        path: String,
        idempotencyKey: String,
        idField: String,
        json: String,
    ): UUID {
        val payload = mockMvc.perform(
            post(path).secureJson(json).headersFor(hotel, "corr-$idempotencyKey", idempotencyKey),
        )
            .andExpect(status().is2xxSuccessful)
            .andReturn().response.contentAsString
        val id = Regex(""""$idField"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1)
            ?: error("Response did not contain $idField: $payload")
        return UUID.fromString(id)
    }

    private fun MockHttpServletRequestBuilder.secureJson(
        json: String? = null,
    ): MockHttpServletRequestBuilder {
        contentType(MediaType.APPLICATION_JSON)
        accept(MediaType.APPLICATION_JSON)
        json?.let { content(it) }
        return this
    }

    private fun MockHttpServletRequestBuilder.headersFor(
        hotel: Hotel,
        correlationId: String,
        idempotencyKey: String? = null,
    ): MockHttpServletRequestBuilder {
        header(PeakRequestHeaders.TENANT_ID, hotel.tenantId.toString())
        header(PeakRequestHeaders.TENANT_USER_ID, hotel.tenantUserId.toString())
        header(PeakRequestHeaders.CORRELATION_ID, correlationId)
        idempotencyKey?.let { header(PeakRequestHeaders.IDEMPOTENCY_KEY, it) }
        return this
    }

    /** A working hotel, still solvent, with a guest about to arrive. */
    private fun hotelWithAnArrivingGuest(): Hotel {
        val hotel = Hotel(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            tenantUserId = UUID.randomUUID(),
            tenantRoleId = UUID.randomUUID(),
            propertyRoleId = UUID.randomUUID(),
            invitableRoleId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            roomTypeId = UUID.randomUUID(),
            roomId = UUID.randomUUID(),
        )

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            hotel.planId, "Plan ${hotel.planId}", "plan-${hotel.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            hotel.tenantId, "Hotel ${hotel.tenantId}", "hotel-${hotel.tenantId}",
            "tenant_${hotel.tenantId}".replace("-", "_"), hotel.planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_control_states (
                tenant_id, lifecycle_status, verification_status, provisioning_status,
                subscription_status, service_status, offboarding_status
            ) VALUES (?, 'active', 'verified', 'ready', 'active', 'operational', 'none')
            """.trimIndent(),
            hotel.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_subscriptions (
                tenant_id, plan_id, status, billing_cycle, billing_currency,
                provider, current_period_starts_at
            ) VALUES (?, ?, 'active', 'monthly', 'TZS', 'manual', now() - interval '30 days')
            """.trimIndent(),
            hotel.tenantId, hotel.planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Front Desk', ?, 'active', true)
            """.trimIndent(),
            hotel.tenantUserId, hotel.tenantId, "desk-${hotel.tenantUserId}@example.com",
        )
        jdbcTemplate.update(
            "INSERT INTO tenant_roles (id, tenant_id, name, code) VALUES (?, ?, 'Operator', ?)",
            hotel.tenantRoleId, hotel.tenantId, "operator-${hotel.tenantRoleId}",
        )
        jdbcTemplate.update(
            "INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id) VALUES (?, ?, ?)",
            hotel.tenantUserId, hotel.tenantId, hotel.tenantRoleId,
        )
        // Invitations refuse system roles, and that 400 would otherwise be mistaken for the
        // restriction guard doing its job.
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code, is_system)
            VALUES (?, ?, 'Front Desk', 'front_desk', false)
            """.trimIndent(),
            hotel.invitableRoleId, hotel.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, status, is_active, total_rooms)
            VALUES (?, ?, 'Suspended Hotel', 'active', true, 1)
            """.trimIndent(),
            hotel.propertyId, hotel.tenantId,
        )
        MODULES.forEach { moduleId ->
            jdbcTemplate.update(
                """
                INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
                VALUES (?, ?, true, true)
                """.trimIndent(),
                hotel.tenantId, moduleId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO property_modules (
                    tenant_id, property_id, module_id, is_enabled, is_configured
                ) VALUES (?, ?, ?, true, true)
                """.trimIndent(),
                hotel.tenantId, hotel.propertyId, moduleId,
            )
        }
        jdbcTemplate.update(
            """
            INSERT INTO roles (id, tenant_id, name, is_system, is_active)
            VALUES (?, ?, 'Property Role', false, true)
            """.trimIndent(),
            hotel.propertyRoleId, hotel.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            hotel.tenantUserId, hotel.propertyId, hotel.propertyRoleId, hotel.tenantId,
        )
        PERMISSIONS.forEach { code ->
            val permissionId = UUID.randomUUID()
            jdbcTemplate.update(
                "INSERT INTO permissions (id, tenant_id, code, description) VALUES (?, ?, ?, ?)",
                permissionId, hotel.tenantId, code, "Permission $code",
            )
            jdbcTemplate.update(
                """
                INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
                SELECT ?, ? FROM permission_catalog
                WHERE code = ? AND is_tenant_permission = true
                  AND access_scope IN ('tenant', 'both')
                """.trimIndent(),
                hotel.tenantRoleId, permissionId, code,
            )
            jdbcTemplate.update(
                """
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, ? FROM permission_catalog
                WHERE code = ? AND is_tenant_permission = true
                  AND access_scope IN ('property', 'both')
                """.trimIndent(),
                hotel.propertyRoleId, permissionId, code,
            )
        }
        jdbcTemplate.update(
            """
            INSERT INTO room_types (
                id, tenant_id, property_id, name, code, base_price,
                max_adults, max_children, max_occupancy, is_active
            ) VALUES (?, ?, ?, 'Standard', 'STD', 100.00, 2, 1, 3, true)
            """.trimIndent(),
            hotel.roomTypeId, hotel.tenantId, hotel.propertyId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO rooms (
                id, tenant_id, property_id, room_type_id, room_number, floor, status
            ) VALUES (?, ?, ?, ?, '101', 1, 'vacant_clean')
            """.trimIndent(),
            hotel.roomId, hotel.tenantId, hotel.propertyId, hotel.roomTypeId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tax_rates (tenant_id, name, code, rate, tax_type, applies_to, is_active)
            VALUES (?, 'VAT', 'VAT18', 0.18, 'vat', ARRAY['room'], true)
            """.trimIndent(),
            hotel.tenantId,
        )
        return hotel
    }

    private data class Hotel(
        val planId: UUID,
        val tenantId: UUID,
        val tenantUserId: UUID,
        val tenantRoleId: UUID,
        val propertyRoleId: UUID,
        val invitableRoleId: UUID,
        val propertyId: UUID,
        val roomTypeId: UUID,
        val roomId: UUID,
    )

    private data class Stay(val stayId: UUID, val folioId: UUID)

    private companion object {
        val MODULES = listOf(
            "reservations", "frontdesk", "billing", "payments", "fiscal", "tenant_admin",
        )

        /**
         * Everything both journeys need. The growth and administration permissions are granted
         * on purpose: a 403 must come from the restriction, never from an absent grant, or the
         * test would pass against a tenant that simply could not do those things anyway.
         */
        val PERMISSIONS = listOf(
            "guests.view", "guests.manage", "guests.identity.manual_verify",
            "guests.identity.verify", "guests.identity.manage", "guests.identity.view",
            "reservations.guests.manage", "reservations.view", "reservations.create",
            "checkin.process", "frontdesk.stays.view", "checkout.process",
            "folio.view", "billing.invoice", "payments.view", "payments.collect",
            "payments.cash.manage", "fiscal.configure", "fiscal.view",
            "tenant.users.manage", "tenant.roles.view",
        )
    }
}
