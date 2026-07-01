package com.mwombeki.peak.fiscal.internal

import java.time.Instant
import java.util.UUID

data class FiscalReceipt(
    val id: UUID,
    val tenantId: UUID,
    val propertyId: UUID,
    val invoiceId: UUID,
    val status: String,
    val fiscalReference: String? = null,
    val signedPayload: String? = null,
    val errorMessage: String? = null,
    val attempts: Int = 0,
    val lastAttemptAt: Instant? = null,
    val verifiedAt: Instant? = null,
    val overridden: Boolean = false,
    val overrideReason: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

enum class FiscalStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    FAILED,
    OVERRIDDEN
}
