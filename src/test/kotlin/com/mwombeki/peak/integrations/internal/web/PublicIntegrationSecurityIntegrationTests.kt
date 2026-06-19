package com.mwombeki.peak.integrations.internal.web

import com.jayway.jsonpath.JsonPath
import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.hamcrest.Matchers.containsString
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.integrations.payment.providers.vodacom-mpesa.base-url=https://payments.example.com/vodacom",
        "peak.integrations.payment.providers.vodacom-mpesa.api-key=test-api-key",
        "peak.integrations.payment.providers.vodacom-mpesa.api-secret=test-api-secret",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PublicIntegrationSecurityIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun createsPublicBookingSessionForActivePropertyModule() {
        val fixture = publicFixture()
        insertPublicFixture(fixture)

        val result = mockMvc.perform(
            post("/api/v1/public/properties/${fixture.propertyId}/booking-engine/sessions")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-public-booking-active")
                .content(bookingSessionJson(fixture)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sessionId").isString)
            .andExpect(jsonPath("$.status").value("payment_pending"))
            .andReturn()

        val sessionId = UUID.fromString(
            JsonPath.read(result.response.contentAsString, "$.sessionId"),
        )
        val row = jdbcTemplate.queryForMap(
            """
            SELECT tenant_id, property_id, status
            FROM booking_sessions
            WHERE id = ?
            """.trimIndent(),
            sessionId,
        )

        assertEquals(fixture.tenantId, row["tenant_id"])
        assertEquals(fixture.propertyId, row["property_id"])
        assertEquals("payment_pending", row["status"])
    }

    @Test
    fun deniesPublicBookingWhenTenantIsSuspended() {
        val fixture = publicFixture(tenantStatus = "suspended")
        insertPublicFixture(fixture)

        mockMvc.perform(
            post("/api/v1/public/properties/${fixture.propertyId}/booking-engine/sessions")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-public-booking-suspended")
                .content(bookingSessionJson(fixture)),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(containsString("Public module is not accessible")))
    }

    @Test
    fun deniesPublicBookingWhenPropertyIsInactive() {
        val fixture = publicFixture(propertyStatus = "suspended", propertyActive = false)
        insertPublicFixture(fixture)

        mockMvc.perform(
            post("/api/v1/public/properties/${fixture.propertyId}/booking-engine/sessions")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-public-booking-property")
                .content(bookingSessionJson(fixture)),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(containsString("Public module is not accessible")))
    }

    @Test
    fun deniesPublicBookingWhenTenantModuleIsDisabled() {
        val fixture = publicFixture(tenantModuleEnabled = false)
        insertPublicFixture(fixture)

        mockMvc.perform(
            post("/api/v1/public/properties/${fixture.propertyId}/booking-engine/sessions")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-public-booking-tenant-module")
                .content(bookingSessionJson(fixture)),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().string(containsString("Public module is not accessible")))
    }

    @Test
    fun deniesPublicBookingWhenPropertyModuleIsDisabled() {
        val fixture = publicFixture(propertyModuleEnabled = false)
        insertPublicFixture(fixture)

        mockMvc.perform(
            post("/api/v1/public/properties/${fixture.propertyId}/booking-engine/sessions")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-public-booking-property-module")
                .content(bookingSessionJson(fixture)),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().string(containsString("Public module is not accessible")))
    }

    @Test
    fun replaysPublicPaymentInitiationWithoutDuplicatingAttempt() {
        val fixture = publicFixture()
        insertPublicFixture(fixture)
        val sessionId = insertBookingSession(fixture)
        val request = paymentJson(sessionId)

        val first = mockMvc.perform(
            post("/api/v1/public/properties/${fixture.propertyId}/booking-engine/payments/initiate")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-public-payment")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-public-payment-${fixture.propertyId}")
                .content(request),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.referenceId").isString)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn()

        val firstReference = JsonPath.read<String>(first.response.contentAsString, "$.referenceId")

        val replay = mockMvc.perform(
            post("/api/v1/public/properties/${fixture.propertyId}/booking-engine/payments/initiate")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-public-payment-replay")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-public-payment-${fixture.propertyId}")
                .content(request),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.referenceId").value(firstReference))
            .andReturn()

        assertEquals(
            firstReference,
            JsonPath.read(replay.response.contentAsString, "$.referenceId"),
        )
        assertEquals(1, paymentAttemptCount(fixture, sessionId))
    }

    @Test
    fun rejectsPublicPaymentWithoutIdempotencyKey() {
        val fixture = publicFixture()
        insertPublicFixture(fixture)
        val sessionId = insertBookingSession(fixture)

        mockMvc.perform(
            post("/api/v1/public/properties/${fixture.propertyId}/booking-engine/payments/initiate")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-public-payment-missing-idem")
                .content(paymentJson(sessionId)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(containsString("Idempotency-Key header is required")))
    }

    private fun publicFixture(
        tenantStatus: String = "active",
        propertyStatus: String = "active",
        propertyActive: Boolean = true,
        tenantModuleEnabled: Boolean = true,
        propertyModuleEnabled: Boolean = true,
    ): PublicFixture {
        return PublicFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            roomTypeId = UUID.randomUUID(),
            tenantStatus = tenantStatus,
            propertyStatus = propertyStatus,
            propertyActive = propertyActive,
            tenantModuleEnabled = tenantModuleEnabled,
            propertyModuleEnabled = propertyModuleEnabled,
        )
    }

    private fun insertPublicFixture(fixture: PublicFixture) {
        jdbcTemplate.update(
            """
            INSERT INTO plans (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.planId,
            "Plan ${fixture.planId}",
            "plan-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, schema_name, plan_id, status)
            VALUES (?, ?, ?, ?, ?, 'active')
            """.trimIndent(),
            fixture.tenantId,
            "Tenant ${fixture.tenantId}",
            "tenant-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, code, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            fixture.propertyId,
            fixture.tenantId,
            "Property ${fixture.propertyId}",
            "P${fixture.propertyId.toString().take(8)}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO room_types (id, tenant_id, property_id, name, code, base_price)
            VALUES (?, ?, ?, ?, ?, 100000)
            """.trimIndent(),
            fixture.roomTypeId,
            fixture.tenantId,
            fixture.propertyId,
            "Standard ${fixture.roomTypeId}",
            "STD${fixture.roomTypeId.toString().take(5)}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
            VALUES (?, 'booking_engine', ?, true)
            """.trimIndent(),
            fixture.tenantId,
            fixture.tenantModuleEnabled,
        )
        jdbcTemplate.update(
            """
            INSERT INTO property_modules (tenant_id, property_id, module_id, is_enabled, is_configured)
            VALUES (?, ?, 'booking_engine', ?, true)
            """.trimIndent(),
            fixture.tenantId,
            fixture.propertyId,
            fixture.propertyModuleEnabled,
        )
        if (fixture.propertyStatus != "active" || !fixture.propertyActive) {
            jdbcTemplate.update(
                """
                UPDATE properties
                SET status = ?,
                    is_active = ?
                WHERE id = ?
                """.trimIndent(),
                fixture.propertyStatus,
                fixture.propertyActive,
                fixture.propertyId,
            )
        }
        if (fixture.tenantStatus != "active") {
            jdbcTemplate.update(
                """
                UPDATE tenants
                SET status = ?
                WHERE id = ?
                """.trimIndent(),
                fixture.tenantStatus,
                fixture.tenantId,
            )
        }
    }

    private fun insertBookingSession(fixture: PublicFixture): UUID {
        val sessionId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO booking_sessions (
                id,
                tenant_id,
                property_id,
                check_in_date,
                check_out_date,
                guest_name,
                guest_email,
                status
            )
            VALUES (?, ?, ?, ?, ?, 'Payment Guest', 'pay@example.com', 'payment_pending')
            """.trimIndent(),
            sessionId,
            fixture.tenantId,
            fixture.propertyId,
            CHECK_IN_DATE,
            CHECK_OUT_DATE,
        )
        return sessionId
    }

    private fun paymentAttemptCount(
        fixture: PublicFixture,
        sessionId: UUID,
    ): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM booking_payment_attempts
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND session_id = ?
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
                fixture.propertyId,
                sessionId,
            ),
        )
    }

    private fun bookingSessionJson(fixture: PublicFixture): String {
        return """
        {
          "roomTypeId": "${fixture.roomTypeId}",
          "checkInDate": "$CHECK_IN_DATE",
          "checkOutDate": "$CHECK_OUT_DATE",
          "guestName": "Public Guest",
          "guestEmail": "public@example.com"
        }
        """.trimIndent()
    }

    private fun paymentJson(sessionId: UUID): String {
        return """
        {
          "sessionId": "$sessionId",
          "provider": "VODACOM_MPESA",
          "paymentMethod": "MOBILE_MONEY",
          "phoneNumber": "+255700000000",
          "accountNumber": null,
          "amount": 100000.00
        }
        """.trimIndent()
    }

    private data class PublicFixture(
        val planId: UUID,
        val tenantId: UUID,
        val propertyId: UUID,
        val roomTypeId: UUID,
        val tenantStatus: String,
        val propertyStatus: String,
        val propertyActive: Boolean,
        val tenantModuleEnabled: Boolean,
        val propertyModuleEnabled: Boolean,
    )

    private companion object {
        val CHECK_IN_DATE: LocalDate = LocalDate.of(2030, 1, 1)
        val CHECK_OUT_DATE: LocalDate = LocalDate.of(2030, 1, 3)
    }
}
