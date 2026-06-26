package com.mwombeki.peak.communication.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.communication.api.NotificationEnqueueReceipt
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.internal.OutboxWorkerProcessor
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper

@Import(
    TestcontainersConfiguration::class,
    NotificationDeliveryProcessorIntegrationTests.FailingProviderConfiguration::class,
)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
        "peak.communication.delivery.local-provider.enabled=false",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class NotificationDeliveryProcessorIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var outboxWorkerProcessor: OutboxWorkerProcessor

    @Test
    fun recordsFailedProviderAttemptAndLeavesDeliveryRetryable() {
        val fixture = deliveryFixture()
        insertAuthorizedFixture(fixture)
        insertProperty(fixture)

        val enqueueResult = mockMvc.perform(
            post("/api/v1/communication/notifications")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "propertyId": "${fixture.propertyId}",
                      "channel": "EMAIL",
                      "recipient": "provider-failure-${fixture.tenantId}@example.com",
                      "subject": "Provider failure",
                      "content": "This provider intentionally fails in tests."
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-delivery-provider-failure", "idem-delivery-provider-failure"),
        )
            .andExpect(status().isOk)
            .andReturn()

        val receipt = objectMapper.readValue(
            enqueueResult.response.contentAsString,
            NotificationEnqueueReceipt::class.java,
        )
        val deliveryRequestId = assertNotNull(receipt.deliveryRequestId)

        outboxWorkerProcessor.processBatchBlocking(OutboxDestination.NOTIFICATION)

        mockMvc.perform(
            get("/api/v1/communication/delivery-requests/$deliveryRequestId")
                .secure(true)
                .headersFor(fixture, "corr-delivery-provider-failure-status"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("failed"))
            .andExpect(jsonPath("$.attemptCount").value(greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.lastError").value("provider unavailable"))

        mockMvc.perform(
            get("/api/v1/communication/delivery-requests/$deliveryRequestId/attempts")
                .secure(true)
                .headersFor(fixture, "corr-delivery-provider-failure-attempts"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].provider").value("failing-test"))
            .andExpect(jsonPath("$[0].status").value("failed"))
            .andExpect(jsonPath("$[0].errorMessage").value("provider unavailable"))
    }

    private fun deliveryFixture(): DeliveryFixture {
        return DeliveryFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            tenantUserId = UUID.randomUUID(),
            tenantRoleId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
        )
    }

    private fun insertAuthorizedFixture(fixture: DeliveryFixture) {
        jdbcTemplate.update(
            """
            INSERT INTO plans (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.planId,
            "Delivery Plan ${fixture.planId}",
            "delivery-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, schema_name, plan_id, status)
            VALUES (?, ?, ?, ?, ?, 'active')
            """.trimIndent(),
            fixture.tenantId,
            "Delivery Tenant ${fixture.tenantId}",
            "delivery-${fixture.tenantId}",
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
            "Delivery Admin ${fixture.tenantUserId}",
            "delivery-admin-${fixture.tenantUserId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.tenantRoleId,
            fixture.tenantId,
            "Delivery Admin ${fixture.tenantRoleId}",
            "delivery-admin-${fixture.tenantRoleId}",
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
        grantPermissionToActor(fixture, "communications.view")
        grantPermissionToActor(fixture, "communications.send")
    }

    private fun grantPermissionToActor(
        fixture: DeliveryFixture,
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

    private fun insertProperty(fixture: DeliveryFixture) {
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, location, code, type, status, is_active)
            VALUES (?, ?, ?, 'Dar es Salaam', ?, 'HOTEL', 'active', true)
            """.trimIndent(),
            fixture.propertyId,
            fixture.tenantId,
            "Delivery Property ${fixture.propertyId}",
            "DLV-${fixture.propertyId.toString().take(8)}",
        )
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.headersFor(
        fixture: DeliveryFixture,
        correlationId: String,
        idempotencyKey: String? = null,
    ): org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder {
        header(PeakRequestHeaders.CORRELATION_ID, correlationId)
        header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
        header(PeakRequestHeaders.TENANT_USER_ID, fixture.tenantUserId.toString())
        idempotencyKey?.let { header(PeakRequestHeaders.IDEMPOTENCY_KEY, it) }
        return this
    }

    private data class DeliveryFixture(
        val planId: UUID,
        val tenantId: UUID,
        val tenantUserId: UUID,
        val tenantRoleId: UUID,
        val propertyId: UUID,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class FailingProviderConfiguration {
        @Bean
        fun failingNotificationDeliveryProvider(): NotificationDeliveryProvider {
            return object : NotificationDeliveryProvider {
                override val providerCode = "failing-test"

                override fun supports(channel: String): Boolean = true

                override fun send(command: NotificationDeliveryCommand): NotificationDeliveryResult {
                    throw IllegalStateException("provider unavailable")
                }
            }
        }
    }
}
