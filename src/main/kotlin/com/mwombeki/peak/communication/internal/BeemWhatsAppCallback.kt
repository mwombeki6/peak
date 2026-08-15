package com.mwombeki.peak.communication.internal

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import tools.jackson.databind.JsonNode

/**
 * Peak's half of Beem Moja's documented `callback_url`.
 *
 * Beem's send contract on `POST https://apichatcore.beem.africa/v1/chatapi` includes
 * an optional callback URL. Their public docs do not publish an HMAC header for
 * Moja delivery reports, so Peak issues a URL whose last path segment is
 * HMAC-SHA256(Beem secret, transaction_id) and verifies that on receive. That is
 * signature verification of a capability URL Peak minted, not a header Peak invented
 * for Beem to send.
 *
 * Inbound WhatsApp chat is classified and dropped. Peak does not create reservations,
 * payments, or a guest inbox from a received body.
 */
object BeemWhatsAppCallback {
    const val PATH_PREFIX = "/api/v1/communication/webhooks/beem/whatsapp"

    fun signature(secretKey: String, transactionId: UUID): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(secretKey.toByteArray(StandardCharsets.UTF_8), HMAC))
        return HexFormat.of().formatHex(
            mac.doFinal(transactionId.toString().toByteArray(StandardCharsets.UTF_8)),
        )
    }

    fun matches(secretKey: String, transactionId: UUID, provided: String): Boolean {
        val expected = signature(secretKey, transactionId).toByteArray(StandardCharsets.UTF_8)
        val actual = provided.trim().lowercase().toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(expected, actual)
    }

    fun callbackUrl(publicBase: String, transactionId: UUID, secretKey: String): String {
        val base = publicBase.trim().trimEnd('/')
        require(base.isNotEmpty()) { "Beem WhatsApp callback base URL is required" }
        return "$base$PATH_PREFIX/$transactionId/${signature(secretKey, transactionId)}"
    }

    fun classify(payload: JsonNode): BeemWhatsAppCallbackKind {
        if (isInboundChat(payload)) {
            return BeemWhatsAppCallbackKind.INBOUND_MESSAGE
        }
        if (deliveryStatus(payload) != null) {
            return BeemWhatsAppCallbackKind.DELIVERY_RECEIPT
        }
        return BeemWhatsAppCallbackKind.UNKNOWN
    }

    fun parseDelivery(payload: JsonNode): BeemWhatsAppDeliveryReceipt {
        return requireNotNull(parseDeliveryOrNull(payload)) {
            "Beem WhatsApp payload is not a delivery receipt"
        }
    }

    fun parseDeliveryOrNull(payload: JsonNode): BeemWhatsAppDeliveryReceipt? {
        if (classify(payload) != BeemWhatsAppCallbackKind.DELIVERY_RECEIPT) {
            return null
        }
        val status = requireNotNull(deliveryStatus(payload))
        val transactionId = payload.firstText("transaction_id", "transactionId")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val messageId = payload.firstText("message_id", "messageId")
        return BeemWhatsAppDeliveryReceipt(
            transactionId = transactionId,
            messageId = messageId,
            status = status,
            deliveryState = GuestWhatsAppDeliveryState.fromBeemStatus(status),
        )
    }

    private fun isInboundChat(payload: JsonNode): Boolean {
        val hasBody = payload.firstText("text", "message", "body") != null
        val messageType = payload.firstText("message_type", "messageType")
        val looksLikeChat = messageType != null &&
            messageType.lowercase() !in setOf("delivery", "dlr", "status")
        return (hasBody || looksLikeChat) && deliveryStatus(payload) == null
    }

    private fun deliveryStatus(payload: JsonNode): String? {
        val raw = payload.firstText("status", "delivery_status", "dlr_status") ?: return null
        return raw.lowercase().takeIf { it in BEEM_DELIVERY_STATUSES }
    }

    private fun JsonNode.firstText(vararg names: String): String? {
        names.forEach { name ->
            val value = path(name).asString("").trim()
            if (value.isNotEmpty()) {
                return value
            }
        }
        return null
    }

    private const val HMAC = "HmacSHA256"
    private val BEEM_DELIVERY_STATUSES = setOf(
        "sent",
        "delivered",
        "read",
        "failed",
        "undelivered",
        "rejected",
    )
}

enum class BeemWhatsAppCallbackKind {
    DELIVERY_RECEIPT,
    INBOUND_MESSAGE,
    UNKNOWN,
}

enum class GuestWhatsAppDeliveryState {
    SENDING,
    DELIVERED,
    FAILED,
    ;

    companion object {
        fun fromBeemStatus(status: String): GuestWhatsAppDeliveryState = when (status.lowercase()) {
            "delivered", "read" -> DELIVERED
            "failed", "undelivered", "rejected" -> FAILED
            else -> SENDING
        }
    }
}

data class BeemWhatsAppDeliveryReceipt(
    val transactionId: UUID?,
    val messageId: String?,
    val status: String,
    val deliveryState: GuestWhatsAppDeliveryState,
)
