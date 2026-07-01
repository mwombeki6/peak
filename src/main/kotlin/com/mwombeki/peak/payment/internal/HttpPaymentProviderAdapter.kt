package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.shared.outbound.OutboundEndpointPolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@ConfigurationProperties(prefix = "peak.payment.providers.http-gateway")
data class HttpPaymentProviderProperties(
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val requestTimeout: Duration = Duration.ofSeconds(15),
)

fun interface PaymentGatewayHttpTransport {
    fun post(
        endpoint: URI,
        credential: String,
        idempotencyKey: String,
        payload: String,
    ): String
}

@Component
class JdkPaymentGatewayHttpTransport(
    private val properties: HttpPaymentProviderProperties,
    private val outboundEndpointPolicy: OutboundEndpointPolicy,
) : PaymentGatewayHttpTransport {
    private val client = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun post(
        endpoint: URI,
        credential: String,
        idempotencyKey: String,
        payload: String,
    ): String {
        val allowedEndpoint = outboundEndpointPolicy.requireAllowedProviderEndpoint(endpoint)
        val request = HttpRequest.newBuilder(allowedEndpoint)
            .timeout(properties.requestTimeout)
            .header("Authorization", "Bearer $credential")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            check(response.statusCode() in 200..299) {
                "Payment provider returned HTTP ${response.statusCode()}"
            }
            val bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1)
            check(bytes.size <= MAX_RESPONSE_BYTES) {
                "Payment provider response exceeds 64 KiB"
            }
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}

@Component
class HttpPaymentProviderAdapter(
    private val objectMapper: ObjectMapper,
    private val transport: PaymentGatewayHttpTransport,
) : PaymentProviderAdapter {
    override val providerCode = "http_gateway"

    override fun initiate(command: ProviderCollectionCommand): ProviderCollectionResult {
        val endpoint = requireHttpsEndpoint(command.endpointUrl)
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "transactionId" to command.transactionId,
                "internalReference" to command.internalReference,
                "merchantId" to command.merchantId,
                "payerIdentifier" to command.payerIdentifier,
                "amount" to command.amount,
                "currency" to command.currency,
            ),
        )
        val response = transport.post(
            endpoint = endpoint,
            credential = command.credential,
            idempotencyKey = command.transactionId.toString(),
            payload = payload,
        )
        val node = objectMapper.readTree(response)
        return ProviderCollectionResult(
            providerReference = node.requiredText("providerReference"),
            status = node.requiredText("status").lowercase(),
        )
    }

    override fun parseWebhook(payload: String): ProviderWebhookNotification {
        val node = objectMapper.readTree(payload)
        return ProviderWebhookNotification(
            internalReference = node.requiredText("internalReference"),
            providerReference = node.requiredText("providerReference"),
            status = node.requiredText("status").lowercase(),
            amount = node.requiredText("amount").toBigDecimal(),
            feeAmount = node.path("feeAmount").asString("0").toBigDecimal(),
            currency = node.requiredText("currency").uppercase(),
            metadata = mapOf(
                "providerEventType" to node.path("eventType").asString("collection.updated"),
            ),
        )
    }

    private fun requireHttpsEndpoint(value: String?): URI {
        val uri = URI.create(value?.trim().orEmpty())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Payment provider endpoint must be an absolute HTTPS URL"
        }
        return uri
    }

    private fun JsonNode.requiredText(field: String): String {
        return path(field).asString().trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Payment provider response field $field is required")
    }
}
