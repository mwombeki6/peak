package com.mwombeki.peak.contract

import com.mwombeki.peak.TestcontainersConfiguration
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springdoc.webmvc.api.OpenApiWebMvcResource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletRequest
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class OpenApiContractIntegrationTests @Autowired constructor(
    private val openApiResource: OpenApiWebMvcResource,
    private val objectMapper: ObjectMapper,
) {

    @Test
    fun `V1 contract is additive and publishes effective security requirements`() {
        val current = currentDocument()
        Files.createDirectories(BUILD_CONTRACT.parent)
        Files.writeString(
            BUILD_CONTRACT,
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(current) + "\n",
        )

        if (System.getProperty(WRITE_BASELINE_PROPERTY).toBoolean()) {
            Files.createDirectories(BASELINE.parent)
            Files.writeString(
                BASELINE,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(current) + "\n",
            )
        }

        assertTrue(Files.exists(BASELINE), "OpenAPI baseline is missing: $BASELINE")
        val baseline = objectMapper.readTree(BASELINE.toFile())
        val violations = backwardCompatibilityViolations(baseline, current)
        assertTrue(
            violations.isEmpty(),
            violations.joinToString(
                separator = "\n",
                prefix = "Breaking V1 OpenAPI changes:\n",
            ),
        )

        assertEffectiveSecurity(current)
        assertPublishedTillRoutes(current)
    }

    private fun assertPublishedTillRoutes(document: JsonNode) {
        val paths = document.path("paths")
        val expected = mapOf(
            "/api/v1/properties/{propertyId}/pos-config/menu-items" to "get",
            "/api/v1/properties/{propertyId}/pos-config/menu-categories" to "get",
            "/api/v1/devices/pairing-requests" to "post",
            "/api/v1/tenants/{tenantId}/devices/pairing-approvals" to "post",
            "/api/v1/tenants/{tenantId}/devices/{deviceId}/revoke" to "post",
            "/api/v1/devices/challenges" to "post",
            "/api/v1/staff/sessions" to "post",
        )
        expected.forEach { (path, method) ->
            assertTrue(
                paths.path(path).has(method),
                "OpenAPI is missing $method $path",
            )
        }
        val session = document.path("components").path("schemas")
            .path("StaffSessionHttpResponse").path("properties")
        listOf("tenantId", "userId", "outletId", "token", "sessionClass", "deviceId", "propertyId")
            .forEach { field ->
                assertTrue(session.has(field), "StaffSessionHttpResponse is missing $field")
            }
        val challenge = document.path("components").path("schemas")
            .path("DeviceChallengeHttpResponse").path("properties")
        listOf("challengeId", "nonce", "expiresAt").forEach { field ->
            assertTrue(challenge.has(field), "DeviceChallengeHttpResponse is missing $field")
        }
    }

    private fun currentDocument(): JsonNode {
        val request = MockHttpServletRequest().apply {
            requestURI = "/v3/api-docs"
            servletPath = "/v3/api-docs"
            scheme = "https"
            serverName = "api.peak.example"
            serverPort = 443
        }
        val bytes = openApiResource.openapiJson(
            request,
            "/v3/api-docs",
            Locale.ROOT,
        )
        return objectMapper.readTree(bytes.toString(StandardCharsets.UTF_8))
    }

    private fun backwardCompatibilityViolations(
        baseline: JsonNode,
        current: JsonNode,
    ): List<String> {
        val violations = mutableListOf<String>()
        baseline.path("paths").properties().forEach { pathEntry ->
            val currentPath = current.path("paths").path(pathEntry.key)
            if (currentPath.isMissingNode) {
                violations += "Removed path ${pathEntry.key}"
            } else {
                pathEntry.value.properties()
                    .filter { it.key in HTTP_METHODS }
                    .forEach { operation ->
                        if (!currentPath.has(operation.key)) {
                            violations += "Removed operation ${operation.key.uppercase()} ${pathEntry.key}"
                        }
                    }
            }
        }

        baseline.path("components").path("schemas").properties().forEach { schemaEntry ->
            val currentSchema = current.path("components").path("schemas").path(schemaEntry.key)
            if (currentSchema.isMissingNode) {
                violations += "Removed schema ${schemaEntry.key}"
            } else {
                val baselineProperties = schemaEntry.value.path("properties").propertyNames().asSequence().toSet()
                val currentProperties = currentSchema.path("properties").propertyNames().asSequence().toSet()
                (baselineProperties - currentProperties).forEach { property ->
                    violations += "Removed schema property ${schemaEntry.key}.$property"
                }
                val baselineRequired = stringValues(schemaEntry.value.path("required"))
                val currentRequired = stringValues(currentSchema.path("required"))
                (baselineRequired - currentRequired).forEach { property ->
                    violations += "Made required property optional ${schemaEntry.key}.$property"
                }
            }
        }
        return violations
    }

    private fun stringValues(node: JsonNode): Set<String> {
        return buildSet {
            node.forEach { child -> add(child.asString()) }
        }
    }

    private fun assertEffectiveSecurity(document: JsonNode) {
        assertTrue(document.path("components").path("securitySchemes").has("bearerAuth"))
        assertEquals(
            "bearerAuth",
            document.path("security").first().propertyNames().toList().single(),
        )

        val webhookSecurity = document.path("paths")
            .path("/api/v1/payments/webhooks/clickpesa/{providerAccountId}")
            .path("post")
            .path("security")
        assertTrue(webhookSecurity.isArray && webhookSecurity.isEmpty)

        document.path("paths").properties().forEach { pathEntry ->
            pathEntry.value.properties()
                .filter { it.key in HTTP_METHODS }
                .forEach { operation ->
                    if (isPublicWebhookPath(pathEntry.key)) {
                        val security = operation.value.path("security")
                        assertTrue(
                            security.isArray && security.isEmpty,
                            "${operation.key.uppercase()} ${pathEntry.key} must publish empty security",
                        )
                    } else {
                        val security = operation.value.path("security")
                        assertTrue(
                            security.isMissingNode || security.any { it.has("bearerAuth") },
                            "${operation.key.uppercase()} ${pathEntry.key} weakens bearer security",
                        )
                    }
                }
        }
    }

    private companion object {
        const val WRITE_BASELINE_PROPERTY = "peak.openapi.write-baseline"
        val BASELINE: Path = Path.of("src/test/resources/contracts/openapi-v1.json")
        val BUILD_CONTRACT: Path = Path.of("build/contracts/openapi-v1.json")
        val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options")

        fun isPublicWebhookPath(path: String): Boolean {
            return path.startsWith("/api/v1/payments/webhooks/") ||
                path.startsWith("/api/v1/platform-billing/webhooks/") ||
                path.startsWith("/api/v1/communication/webhooks/")
        }
    }
}

