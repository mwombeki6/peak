package com.mwombeki.peak.fiscal.internal

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

@ConfigurationProperties(prefix = "peak.fiscal.providers.http-gateway")
data class HttpFiscalProviderProperties(
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val requestTimeout: Duration = Duration.ofSeconds(20),
)

fun interface FiscalGatewayHttpTransport {
    fun post(
        endpoint: URI,
        credential: String,
        idempotencyKey: String,
        payload: String,
    ): String
}

@Component
class JdkFiscalGatewayHttpTransport(
    private val properties: HttpFiscalProviderProperties,
    private val outboundEndpointPolicy: OutboundEndpointPolicy,
) : FiscalGatewayHttpTransport {
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
                "Fiscal provider returned HTTP ${response.statusCode()}"
            }
            val bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1)
            check(bytes.size <= MAX_RESPONSE_BYTES) {
                "Fiscal provider response exceeds 64 KiB"
            }
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}

@Component
class HttpFiscalProviderAdapter(
    private val objectMapper: ObjectMapper,
    private val transport: FiscalGatewayHttpTransport,
) : FiscalProviderAdapter {
    override val providerCode = "http_gateway"

    override fun submit(command: FiscalSubmissionCommand): FiscalSubmissionResult {
        val endpoint = requireHttpsEndpoint(command.endpointUrl)
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "receiptId" to command.receiptId,
                "invoiceId" to command.invoiceId,
                "invoiceNumber" to command.invoiceNumber,
                "taxpayerIdentifier" to command.taxpayerIdentifier,
                "deviceSerial" to command.deviceSerial,
                "currency" to command.currency,
                "subtotal" to command.subtotal,
                "taxTotal" to command.taxTotal,
                "total" to command.total,
                "items" to command.items,
            ),
        )
        val response = transport.post(
            endpoint = endpoint,
            credential = command.credential,
            idempotencyKey = command.receiptId.toString(),
            payload = payload,
        )
        val node = objectMapper.readTree(response)
        return FiscalSubmissionResult(
            accepted = node.path("accepted").asBoolean(false),
            providerDocumentId = node.optionalText("providerDocumentId"),
            receiptNumber = node.optionalText("receiptNumber"),
            fiscalCode = node.optionalText("fiscalCode"),
            verificationCode = node.optionalText("verificationCode"),
            qrCodeUrl = node.optionalText("qrCodeUrl"),
            responseMetadata = mapOf(
                "contractVersion" to node.path("contractVersion").asString("1"),
            ),
            errorCode = node.optionalText("errorCode"),
            errorMessage = node.optionalText("errorMessage"),
        )
    }

    private fun requireHttpsEndpoint(value: String): URI {
        val uri = URI.create(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Fiscal provider endpoint must be an absolute HTTPS URL"
        }
        return uri
    }

    private fun JsonNode.optionalText(field: String): String? {
        return path(field).asString().trim().takeIf { it.isNotEmpty() }
    }
}
