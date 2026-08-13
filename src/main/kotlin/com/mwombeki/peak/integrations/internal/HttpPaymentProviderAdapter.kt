package com.mwombeki.peak.integrations.internal

// Provider-owned HTTP gateway implementation lives outside the payment domain.
import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.ProviderCollectionCommand
import com.mwombeki.peak.payment.api.ProviderCollectionResult
import com.mwombeki.peak.payment.api.ProviderWebhookNotification
import com.mwombeki.peak.shared.outbound.BoundedJsonHttpClient
import com.mwombeki.peak.shared.outbound.OutboundEndpointPolicy
import java.net.URI
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
    private val client = BoundedJsonHttpClient(
        endpointPolicy = outboundEndpointPolicy,
        connectTimeout = properties.connectTimeout,
        providerLabel = "Payment provider",
    )

    override fun post(
        endpoint: URI,
        credential: String,
        idempotencyKey: String,
        payload: String,
    ): String {
        return client.post(
            endpoint = endpoint,
            requestTimeout = properties.requestTimeout,
            credential = credential,
            idempotencyKey = idempotencyKey,
            payload = payload,
        )
    }
}

@Component
class HttpPaymentProviderAdapter(
    private val objectMapper: ObjectMapper,
    private val transport: PaymentGatewayHttpTransport,
) : PaymentProvider {
    override val providerCode = "http_gateway"

    override fun initiate(command: ProviderCollectionCommand): ProviderCollectionResult {
        val endpoint = requireHttpsEndpoint(command.endpointUrl)
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "transactionId" to command.transactionId,
                "internalReference" to command.internalReference,
                "merchantId" to command.clientId,
                "payerIdentifier" to command.payerIdentifier,
                "amount" to command.amount,
                "currency" to command.currency,
                // Forwarded when supplied. Peak stores the network a caller confirmed, so
                // dropping it here would mean recording a routing decision and then not
                // acting on it — the payment would go wherever the gateway guessed.
            ) + (command.providerChannel?.let { mapOf("mobileNetwork" to it) } ?: emptyMap()),
        )
        val response = transport.post(
            endpoint = endpoint,
            credential = command.apiKey,
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
            eventKey = node.path("eventId").asString(
                node.requiredText("providerReference"),
            ),
            eventType = node.path("eventType").asString(
                "collection.updated",
            ),
            internalReference = node.requiredText("internalReference"),
            providerReference = node.requiredText("providerReference"),
            status = node.requiredText("status").lowercase(),
            amount = node.requiredText("amount").toBigDecimal(),
            feeAmount = node.path("feeAmount").asString("0").toBigDecimal(),
            currency = node.requiredText("currency").uppercase(),
            clientId = node.path("clientId").asString(null),
            providerTimestamp = node.path("updatedAt").asString(null)
                ?.let(java.time.Instant::parse),
            checksumMethod = node.path("checksumMethod").asString(null),
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
