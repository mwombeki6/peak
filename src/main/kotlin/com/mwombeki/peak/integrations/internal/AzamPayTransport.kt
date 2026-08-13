package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.shared.outbound.BoundedJsonHttpClient
import com.mwombeki.peak.shared.outbound.OutboundEndpointPolicy
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * AzamPay splits authentication and payments across two hosts, so one base URL is not
 * enough: tokens are minted on the authenticator host while checkout and the public key
 * live on the payments host. The payments host arrives per-call as the command's
 * `endpointUrl`; the authenticator host is configured here.
 */
@ConfigurationProperties(prefix = "peak.payment.providers.azampay")
data class AzamPayProperties(
    /** Base URL of the authenticator host, e.g. https://authenticator.azampay.co.tz. */
    val authenticatorUrl: String = "",
    /**
     * Base URL of the payments host.
     *
     * Configured rather than read from a callback, which is the whole point: a callback is
     * unauthenticated until its signature verifies, so letting it name the host we fetch
     * the verifying key from would let an attacker supply their own key and sign anything.
     *
     * Note the verifying key does **not** come from here. It is served by the authenticator
     * host at `/api/Token/PublicKey`; the payments host answers 404 for every public-key
     * path tried against the live sandbox.
     */
    val paymentsUrl: String = "",
    /**
     * The registered application name.
     *
     * Configuration rather than a per-call credential because this adapter serves exactly
     * one merchant — Peak's own subscription collection. Guest payments settle through the
     * property's own rails, so there is no second AzamPay identity to carry.
     */
    val appName: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val requestTimeout: Duration = Duration.ofSeconds(20),
    /** Renew a token this long before it actually expires. */
    val tokenRefreshSkew: Duration = Duration.ofMinutes(2),
) {
    init {
        require(!tokenRefreshSkew.isNegative) {
            "peak.payment.providers.azampay.token-refresh-skew cannot be negative"
        }
    }
}

/**
 * The seam. Every outbound AzamPay call goes through this so tests can drive the adapter
 * without a network, and so the real implementation stays the only place that touches HTTP.
 */
fun interface AzamPayHttpTransport {
    fun exchange(
        method: String,
        endpoint: URI,
        headers: Map<String, String>,
        payload: String?,
    ): String
}

@Component
class JdkAzamPayHttpTransport(
    private val properties: AzamPayProperties,
    outboundEndpointPolicy: OutboundEndpointPolicy,
) : AzamPayHttpTransport {
    private val client = BoundedJsonHttpClient(
        endpointPolicy = outboundEndpointPolicy,
        connectTimeout = properties.connectTimeout,
        providerLabel = "AzamPay",
    )

    override fun exchange(
        method: String,
        endpoint: URI,
        headers: Map<String, String>,
        payload: String?,
    ): String {
        return client.exchange(
            method = method,
            endpoint = endpoint,
            requestTimeout = properties.requestTimeout,
            headers = headers,
            payload = payload,
        )
    }
}

/**
 * Mints and caches the bearer token AzamPay requires on every payments call.
 *
 * Cached because a token is valid for hours and minting one per checkout would add a round
 * trip to the customer's wait, and because AzamPay rate-limits the authenticator. Renewed
 * early by [AzamPayProperties.tokenRefreshSkew] so a token never expires mid-flight.
 */
@Component
class AzamPayTokenProvider(
    private val transport: AzamPayHttpTransport,
    private val objectMapper: ObjectMapper,
    private val properties: AzamPayProperties,
    private val clock: Clock,
) {
    private val cached = AtomicReference<CachedToken?>(null)

    fun token(clientId: String, clientSecret: String): String {
        val now = clock.instant()
        cached.get()?.let { current ->
            if (current.matches(clientId) && current.usableAt(now, properties.tokenRefreshSkew)) {
                return current.accessToken
            }
        }

        val minted = mint(clientId, clientSecret)
        cached.set(minted)
        return minted.accessToken
    }

    /** Drops the cached token so the next call mints a fresh one. */
    fun invalidate() {
        cached.set(null)
    }

    private fun mint(clientId: String, clientSecret: String): CachedToken {
        require(properties.appName.isNotBlank()) {
            "peak.payment.providers.azampay.app-name must be configured"
        }
        val endpoint = azamPayEndpoint(properties.authenticatorUrl, "/AppRegistration/GenerateToken")
        val response = transport.exchange(
            method = "POST",
            endpoint = endpoint,
            headers = emptyMap(),
            payload = objectMapper.writeValueAsString(
                mapOf(
                    "appName" to properties.appName,
                    "clientId" to clientId,
                    "clientSecret" to clientSecret,
                ),
            ),
        )

        val node = objectMapper.readTree(response)
        val data = node.path("data")
        val accessToken = data.path("accessToken").asString("").trim()
        require(accessToken.isNotEmpty()) {
            "AzamPay token response did not contain an access token"
        }

        // AzamPay returns an ISO-8601 expiry. Treat an unparseable or absent one as a short
        // life rather than as forever: a token believed valid past its expiry fails every
        // checkout until the process restarts.
        val expiresAt = data.path("expire").asString(null)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: clock.instant().plus(FALLBACK_TOKEN_LIFETIME)

        return CachedToken(clientId = clientId, accessToken = accessToken, expiresAt = expiresAt)
    }

    private data class CachedToken(
        val clientId: String,
        val accessToken: String,
        val expiresAt: Instant,
    ) {
        fun matches(candidate: String): Boolean = clientId == candidate

        fun usableAt(now: Instant, skew: Duration): Boolean = now.isBefore(expiresAt.minus(skew))
    }

    private companion object {
        val FALLBACK_TOKEN_LIFETIME: Duration = Duration.ofMinutes(30)
    }
}

/**
 * Joins a configured base URL to a path, refusing anything that is not absolute HTTPS.
 *
 * Shared by the token, checkout and public-key calls so none of them can be pointed at a
 * plaintext or relative endpoint by configuration alone.
 */
internal fun azamPayEndpoint(baseUrl: String?, path: String): URI {
    val trimmed = baseUrl?.trim().orEmpty().trimEnd('/')
    require(trimmed.isNotEmpty()) { "AzamPay endpoint base URL is required" }
    val uri = URI.create(trimmed + path)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
        "AzamPay endpoint must be an absolute HTTPS URL"
    }
    return uri
}
