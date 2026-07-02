package com.mwombeki.peak.phase3

import com.mwombeki.peak.TestcontainersConfiguration
import kotlin.test.assertTrue
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.springdoc.webmvc.api.OpenApiWebMvcResource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletRequest
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class Phase3OpenApiIntegrationTests @Autowired constructor(
    private val openApiResource: OpenApiWebMvcResource,
    private val objectMapper: ObjectMapper,
) {

    @Test
    fun `publishes every Phase 3 closure route`() {
        val request = MockHttpServletRequest().apply {
            requestURI = "/v3/api-docs"
            servletPath = "/v3/api-docs"
            scheme = "http"
            serverName = "localhost"
            serverPort = 8080
        }
        val document = openApiResource.openapiJson(
            request,
            "/v3/api-docs",
            Locale.ROOT,
        ).toString(StandardCharsets.UTF_8)
        val paths = objectMapper.readTree(document).path("paths")
        val expected = mapOf(
            "/api/v1/properties/{propertyId}/payments/transactions/" +
                    "{transactionId}/refund" to "post",
            "/api/v1/properties/{propertyId}/payments/reconciliations" to "get",
            "/api/v1/properties/{propertyId}/payments/reconciliations/" +
                    "{reconciliationId}" to "get",
            "/api/v1/properties/{propertyId}/payments/reconciliations/import" to "post",
            "/api/v1/properties/{propertyId}/invoices/{invoiceId}/void" to "post",
            "/api/v1/properties/{propertyId}/invoices/{invoiceId}/credit-notes" to "post",
            "/api/v1/properties/{propertyId}/checkouts/{stayId}/unpaid-override" to "post",
            "/api/v1/properties/{propertyId}/night-audit/{runId}/issues/" +
                    "{issueId}/override" to "post",
            "/api/v1/properties/{propertyId}/night-audit/{runId}/complete" to "post",
            "/api/v1/payments/webhooks/clickpesa/{providerAccountId}" to "post",
        )
        expected.forEach { (path, method) ->
            assertTrue(
                paths.path(path).has(method),
                "OpenAPI is missing $method $path",
            )
        }
    }
}
