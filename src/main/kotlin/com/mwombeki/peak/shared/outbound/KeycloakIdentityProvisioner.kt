package com.mwombeki.peak.shared.outbound

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@ConfigurationProperties(prefix = "peak.identity.keycloak")
data class KeycloakAdminProperties(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val realm: String = "",
    /** Service account with `manage-users` on the target realm, and nothing wider. */
    val clientId: String = "",
    val clientSecret: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val requestTimeout: Duration = Duration.ofSeconds(10),
)

/**
 * Talks to the Keycloak Admin REST API on Peak's behalf.
 *
 * This deliberately does not route through [BoundedJsonHttpClient]. That client guards calls to
 * *provider* endpoints, whose URLs are influenced by tenant-supplied configuration, so it
 * insists on HTTPS to an allow-listed public hostname — an SSRF control that is exactly right
 * there and unsatisfiable here, since Keycloak answers on an internal compose hostname. It also
 * speaks only GET and POST and returns a bare body, while provisioning needs PUT, DELETE, and
 * the `Location` header that is the only place Keycloak reports a new user's identifier.
 *
 * The protections that do transfer are kept: no redirects, bounded timeouts, a ceiling on the
 * response, and a base URL that comes from deployment configuration and is never assembled from
 * request data.
 */
@Component
@ConditionalOnProperty(
    prefix = "peak.identity.keycloak",
    name = ["enabled"],
    havingValue = "true",
)
class KeycloakIdentityProvisioner(
    private val properties: KeycloakAdminProperties,
    private val objectMapper: ObjectMapper,
) : IdentityProvisionerPort {

    private val client = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private val baseUrl = properties.baseUrl.trimEnd('/')

    @Volatile
    private var cachedToken: CachedToken? = null

    override fun isHealthy(): Boolean =
        runCatching { accessToken() }.isSuccess

    override fun provision(command: ProvisionIdentity): ProvisionedIdentity {
        val username = command.username.trim().lowercase()
        require(username.isNotEmpty()) { "Cannot provision an identity without a username" }
        val email = command.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        findByUsername(username)?.let { existing ->
            val linkedTo = existing.path("attributes")
                .path(PEAK_USER_ID_ATTRIBUTE)
                .firstOrNull()?.asString()?.trim()

            // A retry after a timeout must resolve to the identity it already created, but an
            // account that arrived by some other route must not be absorbed: whoever claimed
            // that username first would inherit whatever role Peak is about to grant. Only our
            // own mark makes an existing subject safe to adopt.
            if (linkedTo == command.peakUserId) {
                return ProvisionedIdentity(
                    subjectId = existing.path("id").asString(),
                    alreadyExisted = true,
                )
            }
            throw IdentityProvisioningException(
                "An identity already exists for that username and is not linked to this Peak user",
            )
        }

        val attributes = buildMap {
            put(PEAK_USER_ID_ATTRIBUTE, listOf(command.peakUserId))
            put(PEAK_TENANT_ID_ATTRIBUTE, listOf(command.tenantId))
            command.phoneNumber?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { put(PHONE_NUMBER_ATTRIBUTE, listOf(it)) }
        }
        val payload = objectMapper.writeValueAsString(
            buildMap<String, Any> {
                put("username", username)
                put("firstName", command.firstName)
                put("lastName", command.lastName)
                put("enabled", true)
                put("attributes", attributes)
                // Omitted rather than sent empty when absent. Keycloak treats "" as an address,
                // and a blank one collides with every other blank one on the realm's uniqueness
                // check — so the second hotelier without email would fail to provision.
                if (email != null) {
                    put("email", email)
                    put("emailVerified", false)
                }
            },
        )

        val response = send("POST", adminPath("users"), payload)
        if (response.statusCode == 409) {
            // Lost a race between the lookup above and this create. The winner is subject to
            // the same adoption rule, so resolve rather than assume it is ours.
            throw IdentityProvisioningException(
                "An identity for that username was created concurrently and is not linked to this Peak user",
            )
        }
        requireSuccess(response, "create identity")

        val subjectId = response.location
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: throw IdentityProvisioningException(
                "Keycloak created an identity but did not report its id",
            )
        return ProvisionedIdentity(subjectId = subjectId, alreadyExisted = false)
    }

    override fun sendActivationLink(command: SendActivationLink) {
        // Most Peak identities have no email, and Keycloak answers this call with 204 whether
        // or not it had an address to send to. Refusing up front is the difference between a
        // manager who is waiting for a link and a manager nobody can tell was never sent one.
        val subject = fetchSubject(command.subjectId)
        val hasEmail = subject.path("email").asString().isNotBlank()
        if (!hasEmail) {
            throw IdentityProvisioningException(
                "That identity has no email address, so it must activate through Peak's own path",
            )
        }

        val query = listOf(
            "client_id" to properties.clientId,
            "redirect_uri" to command.redirectUri,
            "lifespan" to command.lifetime.toSeconds().toString(),
        ).joinToString("&") { (name, value) -> "$name=${encode(value)}" }

        val response = send(
            method = "PUT",
            uri = adminPath("users/${encode(command.subjectId)}/execute-actions-email?$query"),
            payload = objectMapper.writeValueAsString(listOf("UPDATE_PASSWORD", "VERIFY_EMAIL")),
        )
        requireSuccess(response, "send activation link")
    }

    private fun fetchSubject(subjectId: String): JsonNode {
        val response = send("GET", adminPath("users/${encode(subjectId)}"), null)
        requireSuccess(response, "read identity")
        return objectMapper.readTree(response.body)
    }

    override fun disable(subjectId: String) {
        val response = send(
            method = "PUT",
            uri = adminPath("users/${encode(subjectId)}"),
            payload = objectMapper.writeValueAsString(mapOf("enabled" to false)),
        )
        requireSuccess(response, "disable identity")
    }

    override fun delete(subjectId: String) {
        val response = send("DELETE", adminPath("users/${encode(subjectId)}"), null)
        // Unwinding is often a retry of an unwind, so an identity that is already gone is the
        // outcome the caller wanted rather than a failure to report.
        if (response.statusCode == 404) return
        requireSuccess(response, "delete identity")
    }

    private fun findByUsername(username: String): JsonNode? {
        val response = send(
            method = "GET",
            uri = adminPath("users?username=${encode(username)}&exact=true&max=2"),
            payload = null,
        )
        requireSuccess(response, "look up identity")
        val found = objectMapper.readTree(response.body)
        // `exact=true` should make more than one impossible; if the realm ever disagrees, a
        // guess about which one is the person would be the wrong thing to make quietly.
        check(found.size() <= 1) { "Keycloak returned multiple identities for one username" }
        return found.firstOrNull()
    }

    @Synchronized
    private fun accessToken(): String {
        val now = Instant.now()
        cachedToken?.takeIf { now.isBefore(it.expiresAt) }?.let { return it.token }

        val form = listOf(
            "grant_type" to "client_credentials",
            "client_id" to properties.clientId,
            "client_secret" to properties.clientSecret,
        ).joinToString("&") { (name, value) -> "$name=${encode(value)}" }

        val request = HttpRequest.newBuilder(
            URI.create("$baseUrl/realms/${encode(properties.realm)}/protocol/openid-connect/token"),
        )
            .timeout(properties.requestTimeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()

        val response = exchange(request)
        requireSuccess(response, "obtain an admin token")

        val document = objectMapper.readTree(response.body)
        val token = document.path("access_token").asString()
        check(token.isNotBlank()) { "Keycloak returned no access token" }

        // Renew early. A token that expires mid-request would surface as an authorization
        // failure on an unrelated call, which reads like a permissions problem and is not one.
        val lifetime = document.path("expires_in").asLong(DEFAULT_TOKEN_LIFETIME_SECONDS)
        cachedToken = CachedToken(
            token = token,
            expiresAt = now.plusSeconds((lifetime - TOKEN_RENEWAL_MARGIN_SECONDS).coerceAtLeast(1L)),
        )
        return token
    }

    private fun send(method: String, uri: URI, payload: String?): KeycloakResponse {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(properties.requestTimeout)
            .header("Authorization", "Bearer ${accessToken()}")
            .header("Accept", "application/json")

        if (payload == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(payload))
        }
        return exchange(builder.build())
    }

    private fun exchange(request: HttpRequest): KeycloakResponse =
        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                val bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1)
                check(bytes.size <= MAX_RESPONSE_BYTES) { "Keycloak response exceeds 64 KiB" }
                KeycloakResponse(
                    statusCode = response.statusCode(),
                    location = response.headers().firstValue("Location").orElse(null),
                    body = String(bytes, StandardCharsets.UTF_8),
                )
            }
        } catch (ex: IdentityProvisioningException) {
            throw ex
        } catch (ex: Exception) {
            throw IdentityProvisioningException("Keycloak could not be reached", ex)
        }

    private fun requireSuccess(response: KeycloakResponse, action: String) {
        if (response.statusCode !in 200..299) {
            // The status is the whole diagnosis and the body may echo an email address, so the
            // body stays out of the message.
            throw IdentityProvisioningException(
                "Keycloak refused to $action with HTTP ${response.statusCode}",
            )
        }
    }

    private fun adminPath(suffix: String): URI =
        URI.create("$baseUrl/admin/realms/${encode(properties.realm)}/$suffix")

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private data class CachedToken(val token: String, val expiresAt: Instant)

    /**
     * The three things a caller here needs, read under a size ceiling.
     *
     * A plain carrier rather than an [HttpResponse] implementation because the body must be
     * read incrementally to enforce that ceiling, and the location header is the only place
     * Keycloak reports a newly created subject.
     */
    private data class KeycloakResponse(
        val statusCode: Int,
        val location: String?,
        val body: String,
    )

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
        const val DEFAULT_TOKEN_LIFETIME_SECONDS = 60L
        const val TOKEN_RENEWAL_MARGIN_SECONDS = 10L
        const val PEAK_USER_ID_ATTRIBUTE = "peakUserId"
        const val PEAK_TENANT_ID_ATTRIBUTE = "peakTenantId"
        const val PHONE_NUMBER_ATTRIBUTE = "peakPhoneNumber"
    }
}
