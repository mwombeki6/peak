package com.mwombeki.peak.payments.api

import java.math.BigDecimal
import java.time.Instant
import java.util.*


enum class PaymentMethod {
    CASH,
    MOBILE_MONEY,
    CLICKPESA
}

/**
 * Canonical payment statuses.
 */
enum class PaymentStatus {
    CREATED,
    INITIATED,
    PENDING,
    POSTED,
    FAILED,
    EXPIRED,
    REVERSED,
    REFUNDED,
    RECONCILED
}

data class PaymentTransactionResponse(
    val id: UUID,
    val tenantId: UUID,
    val propertyId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val method: PaymentMethod,
    val status: PaymentStatus,
    val providerReference: String?,
    val createdAt: Instant,
    val postedAt: Instant?
)

data class CashPaymentRequest(
    val amount: BigDecimal,
    val currency: String = "TZS",
    val posSessionId: UUID,
    val folioId: UUID,
    val propertyId: UUID
)

data class ManualMobileMoneyRequest(
    val amount: BigDecimal,
    val currency: String = "TZS",
    val providerReference: String, // The M-Pesa/TigoPesa transaction ID
    val posSessionId: UUID,
    val folioId: UUID,
    val propertyId: UUID
)
