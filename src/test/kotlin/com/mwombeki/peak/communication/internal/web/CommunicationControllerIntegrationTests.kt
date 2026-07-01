package com.mwombeki.peak.communication.internal.web

import com.jayway.jsonpath.JsonPath
import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.communication.api.ChannelVerificationReceipt
import com.mwombeki.peak.communication.api.ChannelVerificationRequestReceipt
import com.mwombeki.peak.communication.api.ContactMutationReceipt
import com.mwombeki.peak.communication.api.DeliveryRetryReceipt
import com.mwombeki.peak.communication.api.NotificationEnqueueReceipt
import com.mwombeki.peak.communication.api.TemplateMutationReceipt
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.internal.OutboxWorkerProcessor
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CommunicationControllerIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var outboxWorkerProcessor: OutboxWorkerProcessor

    @Test
    fun createsContactRequestsAndVerifiesChannelWithAuditOutboxAndIdempotency() {
        val fixture = communicationFixture()
        insertAuthorizedFixture(fixture)

        val createResult = mockMvc.perform(
            post("/api/v1/communication/contacts")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "fullName": "Operations Manager",
                      "jobTitle": "Operations",
                      "email": "ops-${fixture.tenantId}@example.com",
                      "phone": "+255712345678"
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-communication-contact-create", "idem-communication-contact-create"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contactId").exists())
            .andExpect(jsonPath("$.channelIds", hasSize<Any>(2)))
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val contact = objectMapper.readValue(
            createResult.response.contentAsString,
            ContactMutationReceipt::class.java,
        )
        val emailChannelId = contact.channelIds.first()

        mockMvc.perform(
            post("/api/v1/communication/contacts")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "fullName": "Operations Manager",
                      "jobTitle": "Operations",
                      "email": "ops-${fixture.tenantId}@example.com",
                      "phone": "+255712345678"
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-communication-contact-replay", "idem-communication-contact-create"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contactId").value(contact.contactId.toString()))
            .andExpect(jsonPath("$.replayed").value(true))

        mockMvc.perform(
            get("/api/v1/communication/contacts")
                .secure(true)
                .headersFor(fixture, "corr-communication-contact-list"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[*].id", hasItem(contact.contactId.toString())))
            .andExpect(jsonPath("$[0].channels[*].id", hasItem(emailChannelId.toString())))
            .andExpect(jsonPath("$[0].channels[*].verificationStatus", hasItem("unverified")))

        val requestVerificationResult = mockMvc.perform(
            post("/api/v1/communication/channels/$emailChannelId/request-verification")
                .secure(true)
                .headersFor(
                    fixture,
                    "corr-communication-verification-request",
                    "idem-communication-verification-request",
                ),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.channelId").value(emailChannelId.toString()))
            .andExpect(jsonPath("$.notificationEventId").exists())
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val verificationRequest = objectMapper.readValue(
            requestVerificationResult.response.contentAsString,
            ChannelVerificationRequestReceipt::class.java,
        )

        mockMvc.perform(
            post("/api/v1/communication/channels/$emailChannelId/request-verification")
                .secure(true)
                .headersFor(
                    fixture,
                    "corr-communication-verification-request-replay",
                    "idem-communication-verification-request",
                ),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.notificationEventId").value(verificationRequest.notificationEventId.toString()))
            .andExpect(jsonPath("$.replayed").value(true))

        assertEquals("pending", channelVerificationStatus(emailChannelId))
        assertEquals(
            1,
            auditCount(fixture.tenantId, "communication.channel.verification_requested", emailChannelId),
        )
        assertEquals(
            1,
            outboxCount(fixture.tenantId, "communication.channel.verification.requested", emailChannelId),
        )

        val token = "verified-token-${UUID.randomUUID()}"
        jdbcTemplate.update(
            """
            UPDATE contact_channels
            SET verification_status = 'pending',
                verification_token_hash = ?,
                verification_expires_at = now() + interval '1 hour'
            WHERE id = ?
            """.trimIndent(),
            sha256Hex(token),
            emailChannelId,
        )

        val verifyResult = mockMvc.perform(
            post("/api/v1/communication/channels/$emailChannelId/verify")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token": "$token"}""")
                .headersFor(fixture, "corr-communication-verify", "idem-communication-verify"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.channelId").value(emailChannelId.toString()))
            .andExpect(jsonPath("$.verified").value(true))
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val verified = objectMapper.readValue(
            verifyResult.response.contentAsString,
            ChannelVerificationReceipt::class.java,
        )

        mockMvc.perform(
            post("/api/v1/communication/channels/$emailChannelId/verify")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token": "$token"}""")
                .headersFor(fixture, "corr-communication-verify-replay", "idem-communication-verify"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.channelId").value(verified.channelId.toString()))
            .andExpect(jsonPath("$.verified").value(true))
            .andExpect(jsonPath("$.replayed").value(true))

        assertEquals("verified", channelVerificationStatus(emailChannelId))
        assertEquals(1, auditCount(fixture.tenantId, "communication.channel.verified", emailChannelId))
    }

    @Test
    fun createsTemplateAndEnqueuesNotificationWithAuditOutboxAndIdempotency() {
        val fixture = communicationFixture()
        insertAuthorizedFixture(fixture)
        insertProperty(fixture)
        insertConsentedChannel(fixture)

        val templateResult = mockMvc.perform(
            post("/api/v1/communication/templates")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Arrival Alert ${fixture.tenantId}",
                      "subject": "Arrival alert",
                      "content": "Guest {{guestName}} has arrived.",
                      "type": "EMAIL"
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-communication-template-create", "idem-communication-template-create"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.templateId").exists())
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val template = objectMapper.readValue(
            templateResult.response.contentAsString,
            TemplateMutationReceipt::class.java,
        )

        mockMvc.perform(
            post("/api/v1/communication/templates")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Arrival Alert ${fixture.tenantId}",
                      "subject": "Arrival alert",
                      "content": "Guest {{guestName}} has arrived.",
                      "type": "EMAIL"
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-communication-template-replay", "idem-communication-template-create"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.templateId").value(template.templateId.toString()))
            .andExpect(jsonPath("$.replayed").value(true))

        assertEquals(1, auditCount(fixture.tenantId, "communication.template.created", template.templateId))
        assertEquals(1, outboxCount(fixture.tenantId, "communication.template.created", template.templateId))

        val notificationResult = mockMvc.perform(
            post("/api/v1/communication/notifications")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "propertyId": "${fixture.propertyId}",
                      "contactChannelId": "${fixture.contactChannelId}",
                      "purpose": "critical_operational_alerts",
                      "subject": "Operational alert",
                      "content": "A test alert was emitted."
                    }
                    """.trimIndent(),
                )
                .headersFor(
                    fixture,
                    "corr-communication-notification-enqueue",
                    "idem-communication-notification-enqueue",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eventId").exists())
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val notification = objectMapper.readValue(
            notificationResult.response.contentAsString,
            NotificationEnqueueReceipt::class.java,
        )
        val deliveryRequestId = assertNotNull(notification.deliveryRequestId)

        mockMvc.perform(
            post("/api/v1/communication/notifications")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "propertyId": "${fixture.propertyId}",
                      "contactChannelId": "${fixture.contactChannelId}",
                      "purpose": "critical_operational_alerts",
                      "subject": "Operational alert",
                      "content": "A test alert was emitted."
                    }
                    """.trimIndent(),
                )
                .headersFor(
                    fixture,
                    "corr-communication-notification-replay",
                    "idem-communication-notification-enqueue",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eventId").value(notification.eventId.toString()))
            .andExpect(jsonPath("$.replayed").value(true))

        mockMvc.perform(
            get("/api/v1/communication/delivery-requests")
                .secure(true)
                .headersFor(fixture, "corr-communication-delivery-list"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[*].id", hasItem(deliveryRequestId.toString())))

        mockMvc.perform(
            get("/api/v1/communication/delivery-requests/$deliveryRequestId")
                .secure(true)
                .headersFor(fixture, "corr-communication-delivery-get"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(deliveryRequestId.toString()))
            .andExpect(jsonPath("$.currentOutboxEventId").value(notification.eventId.toString()))
            .andExpect(jsonPath("$.status").value("queued"))
            .andExpect(jsonPath("$.recipientFingerprint").exists())

        outboxWorkerProcessor.processBatchBlocking(OutboxDestination.NOTIFICATION)

        mockMvc.perform(
            get("/api/v1/communication/delivery-requests/$deliveryRequestId")
                .secure(true)
                .headersFor(fixture, "corr-communication-delivery-delivered"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("delivered"))
            .andExpect(jsonPath("$.attemptCount").value(1))
            .andExpect(jsonPath("$.deliveredAt").exists())

        mockMvc.perform(
            get("/api/v1/communication/delivery-requests/$deliveryRequestId/attempts")
                .secure(true)
                .headersFor(fixture, "corr-communication-delivery-attempts"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].outboxEventId").value(notification.eventId.toString()))
            .andExpect(jsonPath("$[0].provider").value("local"))
            .andExpect(jsonPath("$[0].status").value("delivered"))

        jdbcTemplate.update(
            """
            UPDATE communication_delivery_requests
            SET status = 'failed',
                failed_at = now(),
                last_error = 'manual retry test'
            WHERE id = ?
            """.trimIndent(),
            deliveryRequestId,
        )

        val retryResult = mockMvc.perform(
            post("/api/v1/communication/delivery-requests/$deliveryRequestId/retry")
                .secure(true)
                .headersFor(fixture, "corr-communication-delivery-retry", "idem-communication-delivery-retry"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deliveryRequestId").value(deliveryRequestId.toString()))
            .andExpect(jsonPath("$.eventId").exists())
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val retry = objectMapper.readValue(
            retryResult.response.contentAsString,
            DeliveryRetryReceipt::class.java,
        )

        mockMvc.perform(
            post("/api/v1/communication/delivery-requests/$deliveryRequestId/retry")
                .secure(true)
                .headersFor(fixture, "corr-communication-delivery-retry-replay", "idem-communication-delivery-retry"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eventId").value(retry.eventId.toString()))
            .andExpect(jsonPath("$.replayed").value(true))

        mockMvc.perform(
            get("/api/v1/communication/delivery-requests/$deliveryRequestId")
                .secure(true)
                .headersFor(fixture, "corr-communication-delivery-retry-status"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.currentOutboxEventId").value(retry.eventId.toString()))
            .andExpect(jsonPath("$.status").value("queued"))

        assertEquals(
            1,
            auditCount(fixture.tenantId, "communication.notification.enqueued", notification.eventId),
        )
        assertEquals(
            2,
            outboxCount(fixture.tenantId, "communication.notification.email", fixture.propertyId),
        )
        assertEquals(
            1,
            auditCount(fixture.tenantId, "communication.delivery.retry_requested", deliveryRequestId),
        )
    }

    @Test
    fun deniesNotificationRouteWithoutSendPermission() {
        val fixture = communicationFixture()
        insertFixtureWithoutPermissions(fixture)
        grantPermissionToActor(fixture, "communications.manage")

        mockMvc.perform(
            post("/api/v1/communication/notifications")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "contactChannelId": "${fixture.contactChannelId}",
                      "purpose": "critical_operational_alerts",
                      "subject": "Denied",
                      "content": "Denied"
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-communication-denied", "idem-communication-denied"),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
    }

    @Test
    fun blocksQueuedNotificationWhenConsentIsRevokedBeforeDelivery() {
        val fixture = communicationFixture()
        insertAuthorizedFixture(fixture)
        insertProperty(fixture)
        insertConsentedChannel(fixture)
        val result = mockMvc.perform(
            post("/api/v1/communication/notifications")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "propertyId": "${fixture.propertyId}",
                      "contactChannelId": "${fixture.contactChannelId}",
                      "purpose": "critical_operational_alerts",
                      "subject": "Consent test",
                      "content": "This message must not be delivered after revocation."
                    }
                    """.trimIndent(),
                )
                .headersFor(
                    fixture,
                    "corr-communication-consent-revocation",
                    "idem-communication-consent-revocation",
                ),
        )
            .andExpect(status().isOk)
            .andReturn()
        val receipt = objectMapper.readValue(
            result.response.contentAsString,
            NotificationEnqueueReceipt::class.java,
        )
        jdbcTemplate.update(
            """
            INSERT INTO communication_consents (
                tenant_id,
                contact_id,
                contact_channel_id,
                purpose,
                status,
                policy_version,
                capture_source,
                revoked_at
            )
            VALUES (?, ?, ?, 'critical_operational_alerts', 'revoked', 'test-v2', 'api', now())
            """.trimIndent(),
            fixture.tenantId,
            fixture.contactId,
            fixture.contactChannelId,
        )

        outboxWorkerProcessor.processBatchBlocking(OutboxDestination.NOTIFICATION)

        assertEquals(
            "failed",
            jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?",
                String::class.java,
                receipt.eventId,
            ),
        )
        assertEquals(
            "failed",
            jdbcTemplate.queryForObject(
                "SELECT status FROM communication_delivery_requests WHERE id = ?",
                String::class.java,
                receipt.deliveryRequestId,
            ),
        )
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM communication_delivery_attempts
                WHERE delivery_request_id = ?
                """.trimIndent(),
                Int::class.java,
                receipt.deliveryRequestId,
            ),
        )
    }

    @Test
    fun configuresConsentAwareBusinessContactAndCompletesTenantReadiness() {
        val fixture = communicationFixture()
        insertAuthorizedFixture(fixture)
        grantPermissionToActor(fixture, "tenant.profile.view")
        insertTenantReadinessPrerequisites(fixture)

        val createResult = mockMvc.perform(
            post("/api/v1/communication/contacts")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "fullName": "Managing Director",
                      "jobTitle": "Managing Director",
                      "email": "director-${fixture.tenantId}@example.com"
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-readiness-contact", "idem-readiness-contact"),
        )
            .andExpect(status().isOk)
            .andReturn()
        val contact = objectMapper.readValue(
            createResult.response.contentAsString,
            ContactMutationReceipt::class.java,
        )
        val channelId = contact.channelIds.single()

        jdbcTemplate.update(
            """
            UPDATE contact_channels
            SET verification_status = 'verified',
                verified_at = now(),
                verification_method = 'test-fixture'
            WHERE id = ?
            """.trimIndent(),
            channelId,
        )

        mockMvc.perform(
            post("/api/v1/communication/contacts/${contact.contactId}/roles")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "roleCode": "owner_managing_director",
                      "primary": true
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-contact-role", "idem-contact-role"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.roleCode").value("owner_managing_director"))
            .andExpect(jsonPath("$.primary").value(true))
            .andExpect(jsonPath("$.changed").value(true))

        mockMvc.perform(
            post("/api/v1/communication/contacts/${contact.contactId}/channels/$channelId/consents")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "purpose": "operational_reports",
                      "policyVersion": "phase2-v1",
                      "status": "active"
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-contact-consent", "idem-contact-consent"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.purpose").value("operational_reports"))
            .andExpect(jsonPath("$.status").value("active"))

        val reportRequest = """
            {
              "contactId": "${contact.contactId}",
              "channelId": "$channelId",
              "reportCode": "monthly_executive_summary",
              "subscriptionName": "Executive Management Pack",
              "frequency": "monthly",
              "timezone": "Africa/Dar_es_Salaam",
              "deliveryFormat": "pdf"
            }
        """.trimIndent()
        val reportResult = mockMvc.perform(
            post("/api/v1/communication/report-recipients")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reportRequest)
                .headersFor(fixture, "corr-report-recipient", "idem-report-recipient"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val recipientId: String = JsonPath.read(
            reportResult.response.contentAsString,
            "$.recipientId",
        )

        mockMvc.perform(
            post("/api/v1/communication/report-recipients")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reportRequest)
                .headersFor(fixture, "corr-report-recipient-replay", "idem-report-recipient"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.recipientId").value(recipientId))
            .andExpect(jsonPath("$.replayed").value(true))

        mockMvc.perform(
            get("/api/v1/communication/contacts")
                .secure(true)
                .headersFor(fixture, "corr-readiness-contacts-list"),
        )
            .andExpect(status().isOk)
            .andExpect(
                jsonPath(
                    "$[?(@.id == '${contact.contactId}')].roles[*].roleCode",
                    hasItem("owner_managing_director"),
                ),
            )
            .andExpect(
                jsonPath(
                    "$[?(@.id == '${contact.contactId}')].consents[*].purpose",
                    hasItem("operational_reports"),
                ),
            )

        mockMvc.perform(
            get("/api/v1/communication/report-recipients")
                .secure(true)
                .headersFor(fixture, "corr-report-recipients-list"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].recipientId").value(recipientId))
            .andExpect(jsonPath("$[0].hasActiveConsent").value(true))
            .andExpect(jsonPath("$[0].maskedAddress").exists())

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/readiness")
                .secure(true)
                .headersFor(fixture, "corr-tenant-readiness-complete"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isReady").value(true))
            .andExpect(jsonPath("$.missingRequirements", hasSize<Any>(0)))
    }

    private fun communicationFixture(): CommunicationFixture {
        return CommunicationFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            tenantUserId = UUID.randomUUID(),
            tenantRoleId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            contactId = UUID.randomUUID(),
            contactChannelId = UUID.randomUUID(),
        )
    }

    private fun insertAuthorizedFixture(fixture: CommunicationFixture) {
        insertFixtureWithoutPermissions(fixture)
        grantPermissionToActor(fixture, "communications.manage")
        grantPermissionToActor(fixture, "communications.view")
        grantPermissionToActor(fixture, "communications.send")
    }

    private fun insertFixtureWithoutPermissions(fixture: CommunicationFixture) {
        jdbcTemplate.update(
            """
            INSERT INTO plans (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.planId,
            "Communication Plan ${fixture.planId}",
            "communication-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, schema_name, plan_id, status)
            VALUES (?, ?, ?, ?, ?, 'active')
            """.trimIndent(),
            fixture.tenantId,
            "Communication Tenant ${fixture.tenantId}",
            "communication-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
            VALUES (?, 'communications', true, true)
            """.trimIndent(),
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            fixture.tenantUserId,
            fixture.tenantId,
            "Communication Admin ${fixture.tenantUserId}",
            "communication-admin-${fixture.tenantUserId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.tenantRoleId,
            fixture.tenantId,
            "Communication Admin ${fixture.tenantRoleId}",
            "communication-admin-${fixture.tenantRoleId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.tenantUserId,
            fixture.tenantId,
            fixture.tenantRoleId,
        )
    }

    private fun grantPermissionToActor(
        fixture: CommunicationFixture,
        permissionCode: String,
    ) {
        val permissionId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO permissions (id, tenant_id, code, description)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            permissionId,
            fixture.tenantId,
            permissionCode,
            "Permission $permissionCode",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
            VALUES (?, ?)
            """.trimIndent(),
            fixture.tenantRoleId,
            permissionId,
        )
    }

    private fun insertProperty(fixture: CommunicationFixture) {
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, location, code, type, status, is_active)
            VALUES (?, ?, ?, 'Dar es Salaam', ?, 'HOTEL', 'active', true)
            """.trimIndent(),
            fixture.propertyId,
            fixture.tenantId,
            "Communication Property ${fixture.propertyId}",
            "COM-${fixture.propertyId.toString().take(8)}",
        )
    }

    private fun insertConsentedChannel(fixture: CommunicationFixture) {
        jdbcTemplate.update(
            """
            INSERT INTO tenant_contacts (id, tenant_id, full_name, status)
            VALUES (?, ?, ?, 'active')
            """.trimIndent(),
            fixture.contactId,
            fixture.tenantId,
            "Operations Contact ${fixture.contactId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO contact_channels (
                id,
                tenant_id,
                contact_id,
                channel_type,
                address,
                normalized_address,
                verification_status,
                verified_at,
                is_active
            )
            VALUES (?, ?, ?, 'email', ?, ?, 'verified', now(), true)
            """.trimIndent(),
            fixture.contactChannelId,
            fixture.tenantId,
            fixture.contactId,
            "ops-${fixture.tenantId}@example.com",
            "ops-${fixture.tenantId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO communication_consents (
                tenant_id,
                contact_id,
                contact_channel_id,
                purpose,
                status,
                policy_version,
                capture_source
            )
            VALUES (?, ?, ?, 'critical_operational_alerts', 'active', 'test-v1', 'api')
            """.trimIndent(),
            fixture.tenantId,
            fixture.contactId,
            fixture.contactChannelId,
        )
    }

    private fun insertTenantReadinessPrerequisites(fixture: CommunicationFixture) {
        val platformUserId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, full_name, email, status)
            VALUES (?, 'Readiness Verifier', ?, 'active')
            """.trimIndent(),
            platformUserId,
            "readiness-verifier-$platformUserId@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_profiles (
                tenant_id,
                legal_name,
                entity_type,
                business_phone,
                business_email,
                verification_status,
                verified_at,
                verified_by_platform_user_id
            )
            VALUES (?, ?, 'limited_company', '+255712345678', ?, 'verified', now(), ?)
            """.trimIndent(),
            fixture.tenantId,
            "Communication Tenant Limited",
            "business-${fixture.tenantId}@example.com",
            platformUserId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (
                tenant_id,
                module_id,
                is_enabled,
                is_configured,
                source,
                configured_at
            )
            VALUES (?, 'tenant_admin', true, true, 'system', now())
            """.trimIndent(),
            fixture.tenantId,
        )
    }

    private fun channelVerificationStatus(channelId: UUID): String {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT verification_status FROM contact_channels WHERE id = ?",
                String::class.java,
                channelId,
            ),
        )
    }

    private fun auditCount(
        tenantId: UUID,
        action: String,
        entityId: UUID,
    ): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action = ?
                  AND entity_id = ?
                """.trimIndent(),
                Int::class.java,
                tenantId,
                action,
                entityId,
            ),
        )
    }

    private fun outboxCount(
        tenantId: UUID,
        eventType: String,
        aggregateId: UUID,
    ): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM outbox_events
                WHERE tenant_id = ?
                  AND event_type = ?
                  AND aggregate_id = ?
                """.trimIndent(),
                Int::class.java,
                tenantId,
                eventType,
                aggregateId,
            ),
        )
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.headersFor(
        fixture: CommunicationFixture,
        correlationId: String,
        idempotencyKey: String? = null,
    ): org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder {
        header(PeakRequestHeaders.CORRELATION_ID, correlationId)
        header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
        header(PeakRequestHeaders.TENANT_USER_ID, fixture.tenantUserId.toString())
        idempotencyKey?.let { header(PeakRequestHeaders.IDEMPOTENCY_KEY, it) }
        return this
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") {
            "%02x".format(it.toInt() and 0xff)
        }
    }

    private data class CommunicationFixture(
        val planId: UUID,
        val tenantId: UUID,
        val tenantUserId: UUID,
        val tenantRoleId: UUID,
        val propertyId: UUID,
        val contactId: UUID,
        val contactChannelId: UUID,
    )
}
