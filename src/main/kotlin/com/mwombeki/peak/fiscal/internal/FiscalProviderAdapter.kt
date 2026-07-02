package com.mwombeki.peak.fiscal.internal

import java.math.BigDecimal
import java.util.UUID

interface FiscalProviderAdapter {
    val providerCode: String
    fun submit(command: FiscalSubmissionCommand): FiscalSubmissionResult
}

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
)

data class FiscalInvoiceItem(
    val description: String,
    val amount: BigDecimal,
    val taxAmount: BigDecimal,
)

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
