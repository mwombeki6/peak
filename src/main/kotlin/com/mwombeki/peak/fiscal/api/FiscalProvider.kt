package com.mwombeki.peak.fiscal.api

import java.math.BigDecimal
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface FiscalProvider {
    val providerCode: String
    fun submit(command: FiscalSubmissionCommand): FiscalSubmissionResult
}

@NamedInterface("api")
data class FiscalSubmissionCommand(
    val receiptId: UUID,
    val invoiceId: UUID,
    val invoiceNumber: String,
    val taxpayerIdentifier: String,
    val deviceSerial: String?,
    val endpointUrl: String,
    val credential: String,
    val currency: String,
    val subtotal: BigDecimal,
    val taxTotal: BigDecimal,
    val total: BigDecimal,
    val items: List<FiscalInvoiceItem>,
    val correctionOfReceiptId: UUID? = null,
)

@NamedInterface("api")
data class FiscalInvoiceItem(
    val description: String,
    val amount: BigDecimal,
    val taxAmount: BigDecimal,
)

@NamedInterface("api")
data class FiscalSubmissionResult(
    val accepted: Boolean,
    val providerDocumentId: String?,
    val receiptNumber: String?,
    val fiscalCode: String?,
    val verificationCode: String?,
    val qrCodeUrl: String?,
    val responseMetadata: Map<String, Any?> = emptyMap(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
)
