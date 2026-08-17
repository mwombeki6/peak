package com.mwombeki.peak.communication.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.communication.api.GuestNotificationCommand
import com.mwombeki.peak.communication.api.GuestNotificationPort
import com.mwombeki.peak.communication.api.GuestNotificationPurposes
import com.mwombeki.peak.communication.api.GuestWhatsAppChannelReceipt
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
        "peak.communication.routing.whatsapp=local",
        "peak.communication.providers.beem.secret-key=guest-whatsapp-test-secret",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class GuestWhatsAppEdgeIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var guestNotificationPort: GuestNotificationPort

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @Test
    fun frontDeskRegistersAGuestWhatsAppChannelThatStaysOffTheTenantContactList() {
        val fixture = insertAuthorizedFixture()

        val result = mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/guests/${fixture.guestId}/whatsapp-channel")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "whatsapp": "+255701000001",
                      "policyVersion": "guest-whatsapp-v1"
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-guest-whatsapp-register", "idem-guest-whatsapp-register"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.guestId").value(fixture.guestId.toString()))
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val receipt = objectMapper.readValue(
            result.response.contentAsString,
            GuestWhatsAppChannelReceipt::class.java,
        )
        val purposes = jdbcTemplate.queryForList(
            """
            SELECT purpose
            FROM communication_consents
            WHERE tenant_id = ? AND contact_id = ? AND contact_channel_id = ? AND status = 'active'
            """.trimIndent(),
            String::class.java,
            fixture.tenantId,
            receipt.contactId,
            receipt.channelId,
        )
        assertEquals(GuestNotificationPurposes.ALL, purposes.toSet())

        mockMvc.perform(
            get("/api/v1/communication/contacts")
                .secure(true)
                .headersFor(fixture, "corr-guest-whatsapp-contact-list"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[*].id", not(hasItem(receipt.contactId.toString()))))
    }

    @Test
    fun aConsentedGuestReceivesAWhatsAppOutboxNoticeWhenTheChannelIsRouted() {
        val fixture = insertAuthorizedFixture()
        registerWhatsApp(fixture)
        bindTenant(fixture)
        val receipt = try {
            guestNotificationPort.notifyIfReachable(
                GuestNotificationCommand(
                    tenantId = fixture.tenantId,
                    propertyId = fixture.propertyId,
                    guestId = fixture.guestId,
                    purpose = GuestNotificationPurposes.RESERVATION,
                    aggregateType = "reservations",
                    aggregateId = UUID.randomUUID(),
                    variables = mapOf(
                        "propertyName" to "Peak House",
                        "confirmationNumber" to "RSV-TEST",
                        "checkInDate" to "2026-08-20",
                    ),
                ),
            )
        } finally {
            requestContextHolder.clear()
        }

        assertNotNull(receipt)
        val eventType = jdbcTemplate.queryForObject(
            """
            SELECT event_type
            FROM outbox_events
            WHERE id = ?
            """.trimIndent(),
            String::class.java,
            receipt.eventId,
        )
        assertEquals("communication.notification.whatsapp", eventType)
    }

    @Test
    fun aBeemDeliveryReceiptMarksTheGuestNoticeDelivered() {
        val fixture = insertAuthorizedFixture()
        val transactionId = UUID.randomUUID()
        val deliveryRequestId = insertBeemAttempt(fixture, transactionId, status = "sending")
        val signature = BeemWhatsAppCallback.signature(SECRET, transactionId)

        mockMvc.perform(
            post("/api/v1/communication/webhooks/beem/whatsapp/$transactionId/$signature")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"transaction_id":"$transactionId","message_id":"wamid.test","status":"delivered"}
                    """.trimIndent(),
                )
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-beem-whatsapp-dlr"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted").value(true))
            .andExpect(jsonPath("$.kind").value("delivery_receipt"))
            .andExpect(jsonPath("$.status").value("delivered"))

        val requestStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM communication_delivery_requests WHERE id = ?",
            String::class.java,
            deliveryRequestId,
        )
        assertEquals("delivered", requestStatus)
    }

    @Test
    fun inboundWhatsAppChatIsAcknowledgedAndDoesNotCreateAReservationOrPayment() {
        val fixture = insertAuthorizedFixture()
        val reservationsBefore = count("reservations", fixture.tenantId)
        val paymentsBefore = count("payment_transactions", fixture.tenantId)
        val transactionId = UUID.randomUUID()
        val signature = BeemWhatsAppCallback.signature(SECRET, transactionId)

        mockMvc.perform(
            post("/api/v1/communication/webhooks/beem/whatsapp/$transactionId/$signature")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"from":"255701000001","to":"255701000000","channel":"whatsapp",
                     "message_type":"text","text":"book me a room for Friday",
                     "transaction_id":"$transactionId"}
                    """.trimIndent(),
                )
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-beem-whatsapp-inbound"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.kind").value("inbound_ignored"))

        assertEquals(reservationsBefore, count("reservations", fixture.tenantId))
        assertEquals(paymentsBefore, count("payment_transactions", fixture.tenantId))
        val storedBodies = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM communication_delivery_requests
            WHERE tenant_id = ?
              AND recipient LIKE '%book me a room%'
            """.trimIndent(),
            Int::class.java,
            fixture.tenantId,
        )
        assertEquals(0, storedBodies)
    }

    @Test
    fun aGuessableCallbackSignatureIsRejected() {
        val transactionId = UUID.randomUUID()
        mockMvc.perform(
            post("/api/v1/communication/webhooks/beem/whatsapp/$transactionId/deadbeef")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"delivered","transaction_id":"$transactionId"}""")
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-beem-whatsapp-bad-hmac"),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun tenantIdentityIsRefusedOnThePublicBeemCallback() {
        val fixture = insertAuthorizedFixture()
        val transactionId = UUID.randomUUID()
        val signature = BeemWhatsAppCallback.signature(SECRET, transactionId)

        mockMvc.perform(
            post("/api/v1/communication/webhooks/beem/whatsapp/$transactionId/$signature")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"delivered","transaction_id":"$transactionId"}""")
                .headersFor(fixture, "corr-beem-whatsapp-as-tenant"),
        )
            .andExpect(status().isForbidden)
    }

    private fun registerWhatsApp(fixture: GuestWhatsAppFixture) {
        mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/guests/${fixture.guestId}/whatsapp-channel")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"whatsapp":"+255701000001","policyVersion":"guest-whatsapp-v1"}
                    """.trimIndent(),
                )
                .headersFor(
                    fixture,
                    "corr-guest-whatsapp-setup-${fixture.guestId}",
                    "idem-guest-whatsapp-setup-${fixture.guestId}",
                ),
        )
            .andExpect(status().isOk)
    }

    private fun insertBeemAttempt(
        fixture: GuestWhatsAppFixture,
        transactionId: UUID,
        status: String,
    ): UUID {
        val deliveryRequestId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO communication_delivery_requests (
                id, tenant_id, property_id, original_outbox_event_id, current_outbox_event_id,
                channel_type, recipient, recipient_fingerprint, content_fingerprint, status
            )
            VALUES (?, ?, ?, ?, ?, 'whatsapp', '+255701000001', 'fp-recipient', 'fp-content', ?)
            """.trimIndent(),
            deliveryRequestId,
            fixture.tenantId,
            fixture.propertyId,
            transactionId,
            transactionId,
            status,
        )
        jdbcTemplate.update(
            """
            INSERT INTO communication_delivery_attempts (
                tenant_id, delivery_request_id, outbox_event_id, attempt_number, provider, status
            )
            VALUES (?, ?, ?, 1, 'beem', ?)
            """.trimIndent(),
            fixture.tenantId,
            deliveryRequestId,
            transactionId,
            status,
        )
        return deliveryRequestId
    }

    private fun insertAuthorizedFixture(): GuestWhatsAppFixture {
        val fixture = GuestWhatsAppFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            tenantUserId = UUID.randomUUID(),
            tenantRoleId = UUID.randomUUID(),
            propertyRoleId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            guestId = UUID.randomUUID(),
        )
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            fixture.planId,
            "Guest WhatsApp Plan ${fixture.planId}",
            "guest-wa-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, schema_name, plan_id, status)
            VALUES (?, ?, ?, ?, ?, 'active')
            """.trimIndent(),
            fixture.tenantId,
            "Guest WhatsApp Tenant ${fixture.tenantId}",
            "guest-wa-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            fixture.tenantUserId,
            fixture.tenantId,
            "Guest WhatsApp Clerk",
            "guest-wa-${fixture.tenantUserId}@example.com",
        )
        jdbcTemplate.update(
            "INSERT INTO tenant_roles (id, tenant_id, name, code) VALUES (?, ?, ?, ?)",
            fixture.tenantRoleId,
            fixture.tenantId,
            "Guest WhatsApp Tenant Role",
            "guest-wa-tenant-${fixture.tenantRoleId}",
        )
        jdbcTemplate.update(
            "INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id) VALUES (?, ?, ?)",
            fixture.tenantUserId,
            fixture.tenantId,
            fixture.tenantRoleId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, location, code, type, status, is_active)
            VALUES (?, ?, 'Peak House', 'Dar es Salaam', ?, 'HOTEL', 'active', true)
            """.trimIndent(),
            fixture.propertyId,
            fixture.tenantId,
            "GWA-${fixture.propertyId.toString().take(8)}",
        )
        listOf("communications", "reservations").forEach { moduleId ->
            jdbcTemplate.update(
                """
                INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
                VALUES (?, ?, true, true)
                """.trimIndent(),
                fixture.tenantId,
                moduleId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO property_modules (tenant_id, property_id, module_id, is_enabled, is_configured)
                VALUES (?, ?, ?, true, true)
                """.trimIndent(),
                fixture.tenantId,
                fixture.propertyId,
                moduleId,
            )
        }
        jdbcTemplate.update(
            """
            INSERT INTO roles (id, tenant_id, name, is_system, is_active)
            VALUES (?, ?, 'Guest WhatsApp Property Role', false, true)
            """.trimIndent(),
            fixture.propertyRoleId,
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.tenantUserId,
            fixture.propertyId,
            fixture.propertyRoleId,
            fixture.tenantId,
        )
        listOf("guests.manage", "communications.view", "communications.manage").forEach { code ->
            val permissionId = UUID.randomUUID()
            jdbcTemplate.update(
                "INSERT INTO permissions (id, tenant_id, code, description) VALUES (?, ?, ?, ?)",
                permissionId,
                fixture.tenantId,
                code,
                "Permission $code",
            )
            jdbcTemplate.update(
                """
                INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
                SELECT ?, ?
                FROM permission_catalog
                WHERE code = ?
                  AND is_tenant_permission = true
                  AND access_scope IN ('tenant', 'both')
                """.trimIndent(),
                fixture.tenantRoleId,
                permissionId,
                code,
            )
            jdbcTemplate.update(
                """
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, ?
                FROM permission_catalog
                WHERE code = ?
                  AND is_tenant_permission = true
                  AND access_scope IN ('property', 'both')
                """.trimIndent(),
                fixture.propertyRoleId,
                permissionId,
                code,
            )
        }
        jdbcTemplate.update(
            """
            INSERT INTO guests (id, tenant_id, full_name, origin_property_id)
            VALUES (?, ?, 'Amina Guest', ?)
            """.trimIndent(),
            fixture.guestId,
            fixture.tenantId,
            fixture.propertyId,
        )
        return fixture
    }

    private fun bindTenant(fixture: GuestWhatsAppFixture) {
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Tenant(fixture.tenantId, fixture.tenantUserId),
                correlationId = "corr-guest-whatsapp-notify",
                idempotencyKey = null,
                httpMethod = "POST",
                requestPath = "/api/v1/properties/${fixture.propertyId}/reservations",
            ),
        )
    }

    private fun count(table: String, tenantId: UUID): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM $table WHERE tenant_id = ?",
                Int::class.java,
                tenantId,
            ),
        )
    }

    private fun MockHttpServletRequestBuilder.headersFor(
        fixture: GuestWhatsAppFixture,
        correlationId: String,
        idempotencyKey: String? = null,
    ): MockHttpServletRequestBuilder {
        header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
        header(PeakRequestHeaders.TENANT_USER_ID, fixture.tenantUserId.toString())
        header(PeakRequestHeaders.CORRELATION_ID, correlationId)
        idempotencyKey?.let { header(PeakRequestHeaders.IDEMPOTENCY_KEY, it) }
        return this
    }

    private data class GuestWhatsAppFixture(
        val planId: UUID,
        val tenantId: UUID,
        val tenantUserId: UUID,
        val tenantRoleId: UUID,
        val propertyRoleId: UUID,
        val propertyId: UUID,
        val guestId: UUID,
    )

    private companion object {
        const val SECRET = "guest-whatsapp-test-secret"
    }
}
