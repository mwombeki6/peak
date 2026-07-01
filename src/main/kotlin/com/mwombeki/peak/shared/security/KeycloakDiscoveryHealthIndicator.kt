package com.mwombeki.peak.shared.security

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
    prefix = "peak.security.http.jwt",
    name = ["enabled"],
    havingValue = "true",
)
class KeycloakDiscoveryHealthIndicator(
    private val properties: HttpSecurityProperties,
    private val objectMapper: ObjectMapper,
) : HealthIndicator {
    private val client = HttpClient.newBuilder()
        .connectTimeout(properties.jwt.discoveryHealthTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @Volatile
    private var cached: CachedHealth? = null

    @Synchronized
    override fun health(): Health {
        val now = Instant.now()
        cached?.takeIf { now.isBefore(it.expiresAt) }?.let { return it.health }

        val health = checkDiscovery()
        cached = CachedHealth(
            health = health,
            expiresAt = now.plus(properties.jwt.discoveryHealthCacheTtl),
        )
        return health
    }

    private fun checkDiscovery(): Health {
        return try {
            val expectedIssuer = properties.jwt.issuerUri
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.isNotEmpty() }
                ?: return Health.down()
                    .withDetail("reason", "issuer_not_configured")
                    .build()
            val endpoint = URI.create(
                "$expectedIssuer/.well-known/openid-configuration",
            )
            val request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.jwt.discoveryHealthTimeout)
                .header("Accept", "application/json")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                if (response.statusCode() !in 200..299) {
                    return Health.down()
                        .withDetail("reason", "discovery_http_error")
                        .withDetail("status", response.statusCode())
                        .build()
                }
                val bytes = body.readNBytes(MAX_DISCOVERY_BYTES + 1)
                if (bytes.size > MAX_DISCOVERY_BYTES) {
                    return Health.down()
                        .withDetail("reason", "discovery_document_too_large")
                        .build()
                }
                val document = objectMapper.readTree(String(bytes, StandardCharsets.UTF_8))
                val issuer = document.path("issuer").asString().trim().trimEnd('/')
                val jwksUri = document.path("jwks_uri").asString().trim()
                return if (issuer != expectedIssuer || jwksUri.isEmpty()) {
                    Health.down()
                        .withDetail("reason", "discovery_contract_mismatch")
                        .build()
                } else {
                    Health.up().withDetail("issuer", issuer).build()
                }
            }
        } catch (ex: Exception) {
            Health.down()
                .withDetail("reason", "discovery_unavailable")
                .build()
        }
    }

    private data class CachedHealth(
        val health: Health,
        val expiresAt: Instant,
    )

    private companion object {
        const val MAX_DISCOVERY_BYTES = 64 * 1024
    }
}
