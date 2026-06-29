package com.mwombeki.peak.communication.internal

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@ConfigurationProperties(prefix = "peak.communication.delivery.http-provider")
data class HttpNotificationDeliveryProperties(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val requestTimeout: Duration = Duration.ofSeconds(10),
)

@Component
@ConditionalOnProperty(
    prefix = "peak.communication.delivery.http-provider",
    name = ["enabled"],
    havingValue = "true",
)
class HttpNotificationDeliveryProvider(
    private val properties: HttpNotificationDeliveryProperties,
    private val objectMapper: ObjectMapper,
) : NotificationDeliveryProvider {
    private val endpoint = URI.create("${properties.baseUrl.trimEnd('/')}/v1/messages")
    private val client = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override val providerCode = "http-gateway"

    override fun supports(channel: String): Boolean {
        return channel in SUPPORTED_CHANNELS
    }

    override fun send(command: NotificationDeliveryCommand): NotificationDeliveryResult {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "deliveryRequestId" to command.deliveryRequestId,
                "outboxEventId" to command.outboxEventId,
                "tenantId" to command.tenantId,
                "propertyId" to command.propertyId,
                "channel" to command.channel,
                "recipient" to command.recipient,
                "subject" to command.subject,
                "content" to command.content,
            ),
        )
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(properties.requestTimeout)
            .header("Authorization", "Bearer ${properties.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Idempotency-Key", command.outboxEventId.toString())
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        check(response.statusCode() in 200..299) {
            "Communication provider returned HTTP ${response.statusCode()}"
        }

        @Suppress("UNCHECKED_CAST")
        val responseBody = objectMapper.readValue(response.body(), Map::class.java) as Map<String, Any?>
        val messageId = (responseBody["messageId"] ?: responseBody["id"])
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("Communication provider response did not include messageId")

        return NotificationDeliveryResult(providerMessageId = messageId)
    }

    private companion object {
        val SUPPORTED_CHANNELS = setOf("email", "sms", "whatsapp", "voice_phone")
    }
}
