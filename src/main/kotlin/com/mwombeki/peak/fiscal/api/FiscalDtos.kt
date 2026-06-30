package com.mwombeki.peak.fiscal.api

import java.time.Instant
import java.util.*

/**
 * Request to submit an invoice for fiscalization.
 */
data class FiscalSubmissionRequest(
    val invoiceId: UUID,
    val propertyId: UUID
)

/**
 * Fiscal receipt details.
 */
data class FiscalReceiptResponse(
    val id: UUID,
    val invoiceId: UUID,
    val receiptNumber: String,
    val fiscalCode: String?,
    val verificationCode: String?,
    val qrCodeUrl: String?,
    val status: String,
    val submittedAt: Instant
)
