package com.mwombeki.peak.communication.internal

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

/**
 * Beem's Moja send contract includes `callback_url`. Peak uses that URL only to
 * learn whether a guest WhatsApp was delivered. Inbound chat bodies are not a
 * product here: they are acknowledged and dropped.
 */
class BeemWhatsAppDeliveryReceiptTests {

    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun aDeliveryReceiptCarriesStatusAndNotMessageText() {
        val payload = objectMapper.readTree(
            """
            {"transaction_id":"4f6eec48-4140-4ce8-80c5-cef0e189578c",
             "message_id":"wamid.abc","status":"delivered","timestamp":"1710000000"}
            """.trimIndent(),
        )

        assertEquals(BeemWhatsAppCallbackKind.DELIVERY_RECEIPT, BeemWhatsAppCallback.classify(payload))
        val receipt = BeemWhatsAppCallback.parseDelivery(payload)
        assertEquals(
            UUID.fromString("4f6eec48-4140-4ce8-80c5-cef0e189578c"),
            receipt.transactionId,
        )
        assertEquals("delivered", receipt.status)
        assertEquals(GuestWhatsAppDeliveryState.DELIVERED, receipt.deliveryState)
    }

    @Test
    fun aReadReceiptStillMeansTheGuestGotTheMessage() {
        val payload = objectMapper.readTree("""{"transaction_id":"${UUID.randomUUID()}","status":"read"}""")
        assertEquals(
            GuestWhatsAppDeliveryState.DELIVERED,
            BeemWhatsAppCallback.parseDelivery(payload).deliveryState,
        )
    }

    @Test
    fun aFailedReceiptIsAFailedDelivery() {
        val payload = objectMapper.readTree("""{"transaction_id":"${UUID.randomUUID()}","status":"failed"}""")
        assertEquals(
            GuestWhatsAppDeliveryState.FAILED,
            BeemWhatsAppCallback.parseDelivery(payload).deliveryState,
        )
    }

    @Test
    fun anInboundChatPayloadIsRefusedAsAProductAndDoesNotParseAsDelivery() {
        val payload = objectMapper.readTree(
            """
            {"from":"255701000001","to":"255701000000","channel":"whatsapp",
             "message_type":"text","text":"book me a room for Friday",
             "transaction_id":"${UUID.randomUUID()}"}
            """.trimIndent(),
        )

        assertEquals(BeemWhatsAppCallbackKind.INBOUND_MESSAGE, BeemWhatsAppCallback.classify(payload))
        assertNull(
            BeemWhatsAppCallback.parseDeliveryOrNull(payload),
            "Peak must not keep inbound guest chat bodies or treat them as a booking",
        )
    }

    @Test
    fun aGuessableCallbackPathIsRejected() {
        val transactionId = UUID.fromString("4f6eec48-4140-4ce8-80c5-cef0e189578c")
        assertFalse(
            BeemWhatsAppCallback.matches(
                secretKey = "secret-key",
                transactionId = transactionId,
                provided = "deadbeef",
            ),
        )
        val authentic = BeemWhatsAppCallback.signature("secret-key", transactionId)
        assertTrue(BeemWhatsAppCallback.matches("secret-key", transactionId, authentic))
        assertFalse(
            BeemWhatsAppCallback.matches("other-secret", transactionId, authentic),
            "the signature is bound to Beem's secret-key, not a public path token",
        )
    }
}
