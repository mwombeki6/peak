package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.shared.outbound.BoundedJsonHttpClient
import com.mwombeki.peak.shared.outbound.OutboundEndpointPolicy
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
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
 *
 * **One entry per merchant identity, not one entry.** Peak collects into its own AzamPay
 * account for subscriptions and — once each property carries its own merchant registration —
 * into a different account per hotel. A single-entry cache was never able to serve the wrong
 * hotel's token, because it compared the client id before using it, but it re-minted on every
 * alternation between two properties and a rotation on one hotel's credentials discarded every
 * other hotel's live token. Neither is a security defect; both make the isolation an accident
 * of one comparison rather than a property of the design.
 *
 * The key is the identity AzamPay itself authenticates: the authenticator it was minted
 * against, the registered application, and the client. Nothing else can change which token
 * comes back, and a token minted for one identity is unreachable from another.
 */
@Component
class AzamPayTokenProvider(
    private val transport: AzamPayHttpTransport,
    private val objectMapper: ObjectMapper,
    private val properties: AzamPayProperties,
    private val clock: Clock,
) {
    private val cached = ConcurrentHashMap<MerchantIdentity, CachedToken>()

    /**
     * @param appName the merchant's own AzamPay application registration. Defaults to the
     *   configured one, which is Peak's; a property collecting into its own account must pass
     *   its own, because a token minted under Peak's registration carries Peak's authority.
     */
    fun token(clientId: String, clientSecret: String, appName: String? = null): String {
        val identity = MerchantIdentity(
            authenticatorUrl = properties.authenticatorUrl,
            appName = appName?.trim().orEmpty().ifEmpty { properties.appName },
            clientId = clientId,
        )
        val now = clock.instant()
        cached[identity]?.let { current ->
            if (current.usableAt(now, properties.tokenRefreshSkew)) {
                return current.accessToken
            }
        }

        val minted = mint(identity, clientSecret)
        cached[identity] = minted
        return minted.accessToken
    }

    /**
     * Drops one merchant's token. Scoped so rotating one hotel's credentials cannot force
     * every other hotel to re-mint against a rate-limited authenticator.
     */
    fun invalidate(clientId: String, appName: String? = null) {
        cached.keys.removeIf {
            it.clientId == clientId &&
                (appName == null || it.appName == appName.trim())
        }
    }

    private fun mint(identity: MerchantIdentity, clientSecret: String): CachedToken {
        require(identity.appName.isNotBlank()) {
            "An AzamPay app name is required; configure " +
                "peak.payment.providers.azampay.app-name for Peak's own account, or carry " +
                "the property's registered app name on the command"
        }
        val endpoint = azamPayEndpoint(
            identity.authenticatorUrl,
            "/AppRegistration/GenerateToken",
        )
        val response = transport.exchange(
            method = "POST",
            endpoint = endpoint,
            headers = emptyMap(),
            payload = objectMapper.writeValueAsString(
                mapOf(
                    "appName" to identity.appName,
                    "clientId" to identity.clientId,
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

        return CachedToken(accessToken = accessToken, expiresAt = expiresAt)
    }

    /**
     * Exactly what AzamPay authenticates. Anything absent from this cannot change which token
     * comes back, and anything present in it must not share a cache entry.
     */
    private data class MerchantIdentity(
        val authenticatorUrl: String?,
        val appName: String,
        val clientId: String,
    )

    private data class CachedToken(
        val accessToken: String,
        val expiresAt: Instant,
    ) {
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
