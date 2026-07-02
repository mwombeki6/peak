package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.payment.api.ProviderCollectionCommand
import java.math.BigDecimal
import java.net.URI
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class HttpPaymentProviderAdapterTests {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `initiates collection through canonical HTTPS contract`() {
        lateinit var captured: CapturedRequest
        val adapter = HttpPaymentProviderAdapter(
            mapper,
            PaymentGatewayHttpTransport { endpoint, credential, key, payload ->
                captured = CapturedRequest(endpoint, credential, key, payload)
                """{"providerReference":"MM-123","status":"pending"}"""
            },
        )
        val transactionId = UUID.randomUUID()

        val result = adapter.initiate(
            ProviderCollectionCommand(
                transactionId = transactionId,
                internalReference = "PAY-123",
                endpointUrl = "https://gateway.example.test/v1/collections",
                clientId = "merchant-1",
                payerIdentifier = "255712345678",
                amount = BigDecimal("12500.00"),
                currency = "TZS",
                apiKey = "secret-token",
                checksumKey = "checksum-token",
            ),
        )

        assertEquals("MM-123", result.providerReference)
        assertEquals("pending", result.status)
        assertEquals(URI("https://gateway.example.test/v1/collections"), captured.endpoint)
        assertEquals("secret-token", captured.credential)
        assertEquals(transactionId.toString(), captured.idempotencyKey)
        assertTrue(captured.payload.contains("\"internalReference\":\"PAY-123\""))
        assertTrue(captured.payload.contains("\"amount\":12500.00"))
    }

    @Test
    fun `rejects non HTTPS payment endpoint before transport`() {
        val adapter = HttpPaymentProviderAdapter(
            mapper,
            PaymentGatewayHttpTransport { _, _, _, _ -> error("transport must not be called") },
        )

        assertFailsWith<IllegalArgumentException> {
            adapter.initiate(
                ProviderCollectionCommand(
                    transactionId = UUID.randomUUID(),
                    internalReference = "PAY-123",
                    endpointUrl = "http://gateway.example.test/collections",
                    clientId = "merchant-1",
                    payerIdentifier = "255712345678",
                    amount = BigDecimal.ONE,
                    currency = "TZS",
                    apiKey = "secret-token",
                    checksumKey = "checksum-token",
                ),
            )
        }
    }

    @Test
    fun `parses canonical webhook without trusting tenant scope`() {
        val adapter = HttpPaymentProviderAdapter(
            mapper,
            PaymentGatewayHttpTransport { _, _, _, _ -> error("unused") },
        )

        val notification = adapter.parseWebhook(
            """
            {
              "internalReference": "PAY-123",
              "providerReference": "MM-123",
              "status": "confirmed",
              "amount": "12500.00",
              "feeAmount": "250.00",
              "currency": "TZS"
            }
            """.trimIndent(),
        )

        assertEquals("PAY-123", notification.internalReference)
        assertEquals("MM-123", notification.providerReference)
        assertEquals(BigDecimal("12500.00"), notification.amount)
        assertEquals(BigDecimal("250.00"), notification.feeAmount)
    }

    private data class CapturedRequest(
        val endpoint: URI,
        val credential: String,
        val idempotencyKey: String,
        val payload: String,
    )
}
