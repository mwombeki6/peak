package com.mwombeki.peak.communication.internal

import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

/**
 * Beem's send contract, driven through a stub transport.
 *
 * The request shape is the thing that has to be right: SMS is
 * `POST /v1/send` with Basic auth and `dest_addr` without a `+`; WhatsApp is
 * Moja session text on `POST /v1/chatapi`. Inbound is not implemented here,
 * and these tests would fail if a receive URL started appearing.
 */
class BeemNotificationDeliveryProviderTests {

    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun smsUsesTheDocumentedSendEndpointAndBasicAuth() {
        val transport = StubTransport(smsResponse)
        val command = command(channel = "sms", recipient = "+255784825785")
        val result = provider(transport).send(command)

        assertEquals("67", result.providerMessageId)
        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertEquals("/v1/send", call.endpoint.path)
        assertEquals("apisms.beem.africa", call.endpoint.host)
        assertEquals(
            "Basic YXBpLWtleTpzZWNyZXQta2V5",
            call.headers["Authorization"],
            "docs.beem.africa: Authorization is Basic base64(api_key:secret_key)",
        )

        val body = objectMapper.readTree(requireNotNull(call.payload))
        assertEquals("PEAK", body.path("source_addr").asString(""))
        assertEquals(0, body.path("encoding").asInt())
        assertEquals("Your staff PIN is ready", body.path("message").asString(""))
        val recipient = body.path("recipients")[0]
        assertEquals(1, recipient.path("recipient_id").asInt())
        assertEquals(
            "255784825785",
            recipient.path("dest_addr").asString(""),
            "Beem dest_addr is international digits with no leading +",
        )
    }

    @Test
    fun whatsappUsesMojaSessionTextAndDoesNotCallAReceiveUrl() {
        val transport = StubTransport(whatsappResponse)
        val outboxEventId = UUID.fromString("4f6eec48-4140-4ce8-80c5-cef0e189578c")
        val result = provider(transport).send(
            command(channel = "whatsapp", recipient = "+255701000001", outboxEventId = outboxEventId),
        )

        assertEquals(outboxEventId.toString(), result.providerMessageId)
        val call = transport.calls.single()
        assertEquals("/v1/chatapi", call.endpoint.path)
        assertEquals("apichatcore.beem.africa", call.endpoint.host)
        assertFalse(
            call.endpoint.path.contains("webhook") ||
                call.endpoint.path.contains("receive") ||
                call.endpoint.path.contains("inbound"),
            "this adapter is send-only: ${call.endpoint}",
        )

        val body = objectMapper.readTree(requireNotNull(call.payload))
        assertEquals("255701000000", body.path("from").asString(""))
        assertEquals("255701000001", body.path("to").asString(""))
        assertEquals("whatsapp", body.path("channel").asString(""))
        assertEquals("text", body.path("message_type").asString(""))
        assertEquals(outboxEventId.toString(), body.path("transaction_id").asString(""))
        assertEquals("Your staff PIN is ready", body.path("text").asString(""))
        assertTrue(
            body.path("callback_url").isMissingNode,
            "without a public callback base, Peak cannot ask Beem for a delivery receipt",
        )
    }

    @Test
    fun whatsappIncludesTheDocumentedCallbackUrlWhenAPublicBaseIsConfigured() {
        val transport = StubTransport(whatsappResponse)
        val outboxEventId = UUID.fromString("4f6eec48-4140-4ce8-80c5-cef0e189578c")
        provider(
            transport,
            properties().copy(whatsappCallbackUrl = "https://api.peak.example"),
        ).send(command(channel = "whatsapp", recipient = "+255701000001", outboxEventId = outboxEventId))

        val body = objectMapper.readTree(requireNotNull(transport.calls.single().payload))
        val callbackUrl = body.path("callback_url").asString("")
        assertTrue(
            callbackUrl.startsWith(
                "https://api.peak.example/api/v1/communication/webhooks/beem/whatsapp/",
            ),
            callbackUrl,
        )
        assertTrue(
            callbackUrl.contains(outboxEventId.toString()),
            "Beem correlates the receipt with transaction_id; the callback path must carry it: $callbackUrl",
        )
        val signature = callbackUrl.substringAfterLast('/')
        assertTrue(
            BeemWhatsAppCallback.matches(
                secretKey = "secret-key",
                transactionId = outboxEventId,
                provided = signature,
            ),
            "the path signature is Peak's proof the URL was issued; Beem's docs give callback_url, not an HMAC header",
        )
    }

    @Test
    fun anInvalidSenderIdIsRefusedRatherThanAccepted() {
        val transport = StubTransport(
            """{"code":111,"message":"Invalid Sender ID"}""",
        )
        val failure = assertFailsWith<IllegalStateException> {
            provider(transport).send(command(channel = "sms"))
        }
        assertTrue(requireNotNull(failure.message).contains("111"), failure.message)
    }

    @Test
    fun whatsappIsUnavailableUntilAFromNumberIsConfigured() {
        val provider = provider(
            StubTransport(),
            properties().copy(whatsappFrom = ""),
        )
        assertTrue(provider.supports("sms"))
        assertFalse(provider.supports("whatsapp"))
        assertFalse(provider.supports("email"))
    }

    private fun provider(
        transport: StubTransport,
        properties: BeemProperties = properties(),
    ) = BeemNotificationDeliveryProvider(transport, objectMapper, properties)

    private fun properties() = BeemProperties(
        enabled = true,
        apiKey = "api-key",
        secretKey = "secret-key",
        sourceAddr = "PEAK",
        whatsappFrom = "255701000000",
    )

    private fun command(
        channel: String,
        recipient: String = "+255784825785",
        outboxEventId: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111"),
    ) = NotificationDeliveryCommand(
        deliveryRequestId = UUID.fromString("22222222-2222-4222-8222-222222222222"),
        outboxEventId = outboxEventId,
        tenantId = UUID.fromString("33333333-3333-4333-8333-333333333333"),
        propertyId = null,
        channel = channel,
        recipient = recipient,
        subject = null,
        content = "Your staff PIN is ready",
    )

    private class StubTransport(
        private val response: String = smsResponse,
    ) : BeemHttpTransport {
        val calls = mutableListOf<Call>()

        override fun exchange(
            method: String,
            endpoint: URI,
            headers: Map<String, String>,
            payload: String?,
        ): String {
            calls += Call(method, endpoint, headers, payload)
            return response
        }
    }

    private data class Call(
        val method: String,
        val endpoint: URI,
        val headers: Map<String, String>,
        val payload: String?,
    )

    private companion object {
        const val smsResponse = """
            {"successful": true, "request_id": 67, "code": 100,
             "message": "Message Submitted Successfully", "valid": 1,
             "invalid": 0, "duplicates": 0}
        """
        const val whatsappResponse = """{"message": "success"}"""
    }
}
