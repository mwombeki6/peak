package com.mwombeki.peak.fiscal.internal

import java.math.BigDecimal
import java.net.URI
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class HttpFiscalProviderAdapterTests {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `submits receipt through canonical HTTPS contract`() {
        lateinit var captured: CapturedRequest
        val adapter = HttpFiscalProviderAdapter(
            mapper,
            FiscalGatewayHttpTransport { endpoint, credential, key, payload ->
                captured = CapturedRequest(endpoint, credential, key, payload)
                """
                {
                  "accepted": true,
                  "providerDocumentId": "DOC-123",
                  "receiptNumber": "REC-123",
                  "fiscalCode": "FISCAL-123",
                  "verificationCode": "VERIFY-123",
                  "qrCodeUrl": "https://verify.example.test/REC-123"
                }
                """.trimIndent()
            },
        )
        val receiptId = UUID.randomUUID()

        val result = adapter.submit(command(receiptId))

        assertTrue(result.accepted)
        assertEquals("DOC-123", result.providerDocumentId)
        assertEquals(URI("https://fiscal.example.test/v1/receipts"), captured.endpoint)
        assertEquals("secret-token", captured.credential)
        assertEquals(receiptId.toString(), captured.idempotencyKey)
        assertTrue(captured.payload.contains("\"invoiceNumber\":\"INV-123\""))
        assertTrue(captured.payload.contains("\"total\":11800.00"))
    }

    @Test
    fun `rejects non HTTPS fiscal endpoint before transport`() {
        val adapter = HttpFiscalProviderAdapter(
            mapper,
            FiscalGatewayHttpTransport { _, _, _, _ -> error("transport must not be called") },
        )

        assertFailsWith<IllegalArgumentException> {
            adapter.submit(command(UUID.randomUUID(), "http://fiscal.example.test/receipts"))
        }
    }

    private fun command(
        receiptId: UUID,
        endpoint: String = "https://fiscal.example.test/v1/receipts",
    ) = FiscalSubmissionCommand(
        receiptId = receiptId,
        invoiceId = UUID.randomUUID(),
        invoiceNumber = "INV-123",
        taxpayerIdentifier = "123-456-789",
        deviceSerial = "DEVICE-1",
        endpointUrl = endpoint,
        credential = "secret-token",
        currency = "TZS",
        subtotal = BigDecimal("10000.00"),
        taxTotal = BigDecimal("1800.00"),
        total = BigDecimal("11800.00"),
        items = listOf(
            FiscalInvoiceItem(
                description = "Room",
                amount = BigDecimal("10000.00"),
                taxAmount = BigDecimal("1800.00"),
            ),
        ),
    )

    private data class CapturedRequest(
        val endpoint: URI,
        val credential: String,
        val idempotencyKey: String,
        val payload: String,
    )
}
