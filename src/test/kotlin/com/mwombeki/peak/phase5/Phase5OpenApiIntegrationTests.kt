package com.mwombeki.peak.phase5

import com.mwombeki.peak.TestcontainersConfiguration
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springdoc.webmvc.api.OpenApiWebMvcResource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletRequest
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class Phase5OpenApiIntegrationTests @Autowired constructor(
    private val openApiResource: OpenApiWebMvcResource,
    private val objectMapper: ObjectMapper,
) {
    @Test
    fun `publishes Phase 5 close and reporting routes`() {
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
            "/api/v1/properties/{propertyId}/night-audit/{runId}/close-snapshot" to "get",
            "/api/v1/tenants/{tenantId}/reporting/settings" to "put",
            "/api/v1/properties/{propertyId}/reporting/settings" to "put",
            "/api/v1/tenants/{tenantId}/reports/catalog" to "get",
            "/api/v1/properties/{propertyId}/report-subscriptions" to "post",
            "/api/v1/tenants/{tenantId}/report-subscriptions/{subscriptionId}/recipients" to "post",
            "/api/v1/properties/{propertyId}/reports/{reportCode}/runs" to "post",
            "/api/v1/tenants/{tenantId}/report-runs" to "get",
            "/api/v1/tenants/{tenantId}/report-runs/{runId}/download-link" to "post",
            "/api/v1/tenants/{tenantId}/report-runs/{runId}/deliveries" to "get",
            "/api/v1/tenants/{tenantId}/report-deliveries/{deliveryId}/retry" to "post",
        )
        expected.forEach { (path, method) ->
            assertTrue(
                paths.path(path).has(method),
                "OpenAPI is missing $method $path",
            )
        }
    }
}
