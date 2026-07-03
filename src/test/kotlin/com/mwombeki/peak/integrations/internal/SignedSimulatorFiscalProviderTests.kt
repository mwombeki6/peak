package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.fiscal.api.FiscalInvoiceItem
import com.mwombeki.peak.fiscal.api.FiscalSubmissionCommand
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class SignedSimulatorFiscalProviderTests {
    private val provider = SignedSimulatorFiscalProvider(
        JsonMapper.builder().build(),
    )

    @Test
    fun `returns deterministic signed acceptance and rejection responses`() {
        val accepted = provider.submit(command("INV-100"))
        val replay = provider.submit(command("INV-100"))
        val rejected = provider.submit(command("INV-REJECT"))

        assertTrue(accepted.accepted)
        assertEquals(accepted, replay)
        assertEquals("HMAC-SHA256", accepted.responseMetadata["signatureMethod"])
        assertFalse(rejected.accepted)
        assertEquals("SIMULATED_REJECTION", rejected.errorCode)
    }

    @Test
    fun `models timeout retry and credit note correction scenarios`() {
        assertFailsWith<IllegalStateException> {
            provider.submit(command("INV-TIMEOUT"))
        }

        val retry = command("INV-RETRY")
        assertFailsWith<IllegalStateException> {
            provider.submit(retry)
        }
        assertTrue(provider.submit(retry).accepted)

        val correction = command(
            invoiceNumber = "CN-100",
            correctionOfReceiptId = UUID.randomUUID(),
        )
        val corrected = provider.submit(correction)
        assertTrue(corrected.accepted)
        assertTrue(corrected.providerDocumentId!!.startsWith("SIM-CN-"))
        assertEquals("credit_note", corrected.responseMetadata["scenario"])
    }

    private fun command(
        invoiceNumber: String,
        correctionOfReceiptId: UUID? = null,
    ): FiscalSubmissionCommand {
        val stableId = UUID.nameUUIDFromBytes(invoiceNumber.toByteArray())
        return FiscalSubmissionCommand(
            receiptId = stableId,
            invoiceId = UUID.nameUUIDFromBytes("invoice-$invoiceNumber".toByteArray()),
            invoiceNumber = invoiceNumber,
            taxpayerIdentifier = "123-456-789",
            deviceSerial = "SIM-001",
            endpointUrl = "https://fiscal.invalid/$invoiceNumber",
            credential = "simulator-signing-secret",
            currency = "TZS",
            subtotal = BigDecimal("1000.00"),
            taxTotal = BigDecimal("180.00"),
            total = BigDecimal("1180.00"),
            items = listOf(
                FiscalInvoiceItem(
                    description = "Room",
                    amount = BigDecimal("1000.00"),
                    taxAmount = BigDecimal("180.00"),
                ),
            ),
            correctionOfReceiptId = correctionOfReceiptId,
        )
    }
}
