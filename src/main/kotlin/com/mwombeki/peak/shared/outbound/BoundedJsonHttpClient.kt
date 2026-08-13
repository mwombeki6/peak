package com.mwombeki.peak.shared.outbound

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import org.springframework.modulith.NamedInterface

@NamedInterface("outbound")
class BoundedJsonHttpClient(
    endpointPolicy: OutboundEndpointPolicy,
    connectTimeout: Duration,
    private val providerLabel: String,
) {
    private val policy = endpointPolicy
    private val client = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun post(
        endpoint: URI,
        requestTimeout: Duration,
        credential: String,
        idempotencyKey: String,
        payload: String,
    ): String {
        return exchange(
            method = "POST",
            endpoint = endpoint,
            requestTimeout = requestTimeout,
            headers = mapOf(
                "Authorization" to "Bearer $credential",
                "Idempotency-Key" to idempotencyKey,
            ),
            payload = payload,
        )
    }

    /**
     * The general form, for a provider that needs its own headers or a GET.
     *
     * Everything goes through here rather than through a second HTTP client so that the
     * endpoint allow-list, the redirect refusal and the response ceiling apply to every
     * outbound provider call. A parallel client would bypass all three silently.
     */
    fun exchange(
        method: String,
        endpoint: URI,
        requestTimeout: Duration,
        headers: Map<String, String> = emptyMap(),
        payload: String? = null,
    ): String {
        require(method == "GET" || method == "POST") {
            "$providerLabel supports only GET and POST, not $method"
        }
        require(method == "POST" || payload == null) {
            "$providerLabel cannot send a body on a $method"
        }

        val allowedEndpoint = policy.requireAllowedProviderEndpoint(endpoint)
        val builder = HttpRequest.newBuilder(allowedEndpoint)
            .timeout(requestTimeout)
            .header("Accept", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }

        if (payload == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(payload))
        }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            check(response.statusCode() in 200..299) {
                "$providerLabel returned HTTP ${response.statusCode()}"
            }
            val bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1)
            check(bytes.size <= MAX_RESPONSE_BYTES) {
                "$providerLabel response exceeds 64 KiB"
            }
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
