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
        val allowedEndpoint = policy.requireAllowedProviderEndpoint(endpoint)
        val request = HttpRequest.newBuilder(allowedEndpoint)
            .timeout(requestTimeout)
            .header("Authorization", "Bearer $credential")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
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
