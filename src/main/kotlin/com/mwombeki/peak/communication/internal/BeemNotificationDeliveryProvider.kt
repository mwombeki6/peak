package com.mwombeki.peak.communication.internal

import com.mwombeki.peak.shared.outbound.BoundedJsonHttpClient
import com.mwombeki.peak.shared.outbound.OutboundEndpointPolicy
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Sends SMS and WhatsApp through Beem.
 *
 * Contract is [docs.beem.africa](https://docs.beem.africa/index.html) and the
 * Moja send sample on [beem.africa/conversational-api](https://beem.africa/conversational-api/):
 *
 *  * SMS: `POST https://apisms.beem.africa/v1/send` with HTTP Basic
 *    (`api_key`:`secret_key`). Body is `source_addr`, `encoding`, `message`,
 *    and `recipients[{recipient_id, dest_addr}]`. Success is HTTP 200 with
 *    `code` 100 and `request_id`.
 *  * WhatsApp (Moja session text): `POST https://apichatcore.beem.africa/v1/chatapi`
 *    with the same Basic auth. Body is `from`, `to`, `channel=whatsapp`,
 *    `message_type=text`, `text`, a UUIDv4 `transaction_id`, and optional
 *    `callback_url`. That path only delivers inside an active 24-hour
 *    session. Peak does not call a template-broadcast URL: Beem's public
 *    WhatsApp template sample has no documented host. Inbound chat is not
 *    a product; `callback_url` is only a delivery receipt.
 *
 * Routing, not [supports], decides which channel this deployment actually
 * uses. WhatsApp stays unconfigured until a from-number is set.
 */
@ConfigurationProperties(prefix = "peak.communication.providers.beem")
data class BeemProperties(
    val enabled: Boolean = false,
    val smsBaseUrl: String = "https://apisms.beem.africa",
    val whatsappBaseUrl: String = "https://apichatcore.beem.africa",
    val apiKey: String = "",
    val secretKey: String = "",
    /** Active Sender ID. Alphanumeric, at most 11 characters, no leading `+`. */
    val sourceAddr: String = "",
    /** WhatsApp Business number, international digits, no leading `+`. */
    val whatsappFrom: String = "",
    /**
     * Public HTTPS origin Peak asks Beem to POST delivery receipts to.
     * Required in production when WhatsApp is routed to Beem; empty means
     * Peak will not learn whether a guest message was delivered.
     */
    val whatsappCallbackUrl: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val requestTimeout: Duration = Duration.ofSeconds(10),
)

fun interface BeemHttpTransport {
    fun exchange(
        method: String,
        endpoint: URI,
        headers: Map<String, String>,
        payload: String?,
    ): String
}

@Component
@ConditionalOnProperty(
    prefix = "peak.communication.providers.beem",
    name = ["enabled"],
    havingValue = "true",
)
class JdkBeemHttpTransport(
    private val properties: BeemProperties,
    outboundEndpointPolicy: OutboundEndpointPolicy,
) : BeemHttpTransport {
    private val client = BoundedJsonHttpClient(
        endpointPolicy = outboundEndpointPolicy,
        connectTimeout = properties.connectTimeout,
        providerLabel = "Beem",
    )

    override fun exchange(
        method: String,
        endpoint: URI,
        headers: Map<String, String>,
        payload: String?,
    ): String = client.exchange(
        method = method,
        endpoint = endpoint,
        requestTimeout = properties.requestTimeout,
        headers = headers,
        payload = payload,
    )
}

@Component
@ConditionalOnProperty(
    prefix = "peak.communication.providers.beem",
    name = ["enabled"],
    havingValue = "true",
)
class BeemNotificationDeliveryProvider(
    private val transport: BeemHttpTransport,
    private val objectMapper: ObjectMapper,
    private val properties: BeemProperties,
) : NotificationDeliveryProvider {
    override val providerCode = "beem"

    override fun supports(channel: String): Boolean = when (channel.trim().lowercase()) {
        "sms" -> properties.sourceAddr.isNotBlank()
        "whatsapp" -> properties.whatsappFrom.isNotBlank()
        else -> false
    }

    override fun send(command: NotificationDeliveryCommand): NotificationDeliveryResult {
        return when (command.channel.trim().lowercase()) {
            "sms" -> sendSms(command)
            "whatsapp" -> sendWhatsApp(command)
            else -> error("Beem does not deliver ${command.channel}")
        }
    }

    private fun sendSms(command: NotificationDeliveryCommand): NotificationDeliveryResult {
        val sourceAddr = properties.sourceAddr.trim()
        require(sourceAddr.isNotEmpty()) { "peak.communication.providers.beem.source-addr is required" }
        val destAddr = internationalDigits(command.recipient)
        val body = mapOf(
            "source_addr" to sourceAddr,
            "encoding" to 0,
            "message" to command.content,
            "recipients" to listOf(
                mapOf(
                    "recipient_id" to 1,
                    "dest_addr" to destAddr,
                ),
            ),
        )
        val response = postJson(smsEndpoint(), body)
        val root = objectMapper.readTree(response)
        val code = root.path("code").asInt(0)
        check(root.path("successful").asBoolean(false) && code == SMS_SUBMITTED) {
            "Beem SMS was not submitted: code=$code ${root.path("message").asString("")}"
        }
        val requestId = root.path("request_id").asString("").trim()
        check(requestId.isNotEmpty()) { "Beem SMS response carried no request_id" }
        return NotificationDeliveryResult(providerMessageId = requestId)
    }

    /**
     * Session text only. Beem's own docs restrict this to an active 24-hour
     * window; Peak does not invent a template-broadcast fallback here.
     */
    private fun sendWhatsApp(command: NotificationDeliveryCommand): NotificationDeliveryResult {
        val from = internationalDigits(properties.whatsappFrom)
        require(from.isNotEmpty()) {
            "peak.communication.providers.beem.whatsapp-from is required"
        }
        val transactionId = command.outboxEventId
        val body = buildMap<String, Any> {
            put("from", from)
            put("to", internationalDigits(command.recipient))
            put("channel", "whatsapp")
            put("transaction_id", transactionId.toString())
            put("message_type", "text")
            put("text", command.content)
            val callbackBase = properties.whatsappCallbackUrl.trim()
            if (callbackBase.isNotEmpty()) {
                put(
                    "callback_url",
                    BeemWhatsAppCallback.callbackUrl(
                        publicBase = callbackBase,
                        transactionId = transactionId,
                        secretKey = properties.secretKey,
                    ),
                )
            }
        }
        val response = postJson(whatsappEndpoint(), body)
        val root = objectMapper.readTree(response)
        check(root.path("message").asString("").equals("success", ignoreCase = true)) {
            "Beem WhatsApp was not accepted: ${root.path("message").asString("")}"
        }
        return NotificationDeliveryResult(
            providerMessageId = transactionId.toString(),
            awaitingReceipt = properties.whatsappCallbackUrl.isNotBlank(),
        )
    }

    private fun postJson(endpoint: URI, body: Map<String, Any>): String {
        return transport.exchange(
            method = "POST",
            endpoint = endpoint,
            headers = mapOf(
                "Authorization" to basicAuth(),
            ),
            payload = objectMapper.writeValueAsString(body),
        )
    }

    private fun basicAuth(): String {
        val raw = "${properties.apiKey}:${properties.secretKey}"
            .toByteArray(StandardCharsets.UTF_8)
        return "Basic ${Base64.getEncoder().encodeToString(raw)}"
    }

    private fun smsEndpoint(): URI = join(properties.smsBaseUrl, SMS_PATH)

    private fun whatsappEndpoint(): URI = join(properties.whatsappBaseUrl, WHATSAPP_PATH)

    private companion object {
        const val SMS_PATH = "/v1/send"
        const val WHATSAPP_PATH = "/v1/chatapi"
        const val SMS_SUBMITTED = 100

        fun internationalDigits(value: String): String =
            value.trim().removePrefix("+").filter(Char::isDigit)

        fun join(baseUrl: String, path: String): URI {
            val base = baseUrl.trim().trimEnd('/')
            require(base.isNotEmpty()) { "Beem base URL is required" }
            return URI.create("$base$path")
        }
    }
}
