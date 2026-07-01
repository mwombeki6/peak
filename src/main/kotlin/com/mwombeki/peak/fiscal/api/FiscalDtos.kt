package com.mwombeki.peak.fiscal.api

import java.time.Instant
import java.util.UUID

data class FiscalSubmissionRequest(
    val invoiceId: UUID,
    val tenantId: UUID,
    val propertyId: UUID,
)

data class FiscalReceiptResponse(
    val id: UUID,
    val invoiceId: UUID,
    val fiscalReference: String?,
    val status: String,
    val signedPayload: String?,
    val verifiedAt: Instant?,
    val errorMessage: String? = null,
)

data class FiscalOverrideRequest(
    val reason: String,
)
