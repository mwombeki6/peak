package com.mwombeki.peak.platformbilling.api

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.springframework.modulith.NamedInterface

/**
 * What a Peak operator needs to see about subscription revenue.
 *
 * Read-only on purpose. Anything that changes a customer's commercial position — writing off
 * a debt, confirming a payment by hand, enabling a payment rail — is a decision with money
 * attached and should go through a reviewed path with an audit trail, not a convenience
 * endpoint added because someone was on a support call.
 */
@NamedInterface("api")
interface PlatformBillingAdminPort {
    /**
     * Commercial standing for every tenant, with the three facts stated separately.
     *
     * During suspension `tenant_subscriptions.status` reads `past_due` and the row stays
     * service-granting deliberately, because that is what keeps the restriction allowances
     * reachable so a suspended hotel can still check a guest out. Read alone, that column
     * says "broadly fine". A support engineer will eventually try to fix it. So standing,
     * relationship and payment status are reported as three answers rather than one.
     */
    fun commercialStanding(limit: Int = 200): List<TenantCommercialStanding>

    /**
     * Payments whose outcome Peak could not determine.
     *
     * The queue that must not be allowed to grow silently: every row is a customer who may
     * have been debited for something they have not received.
     */
    fun paymentsRequiringReconciliation(limit: Int = 200): List<StuckPayment>

    fun receipts(limit: Int = 200): List<IssuedReceipt>
}

@NamedInterface("api")
data class TenantCommercialStanding(
    val tenantId: UUID,
    val tenantName: String,
    /** active, restricted or suspended — how much of what they own they may use. */
    val commercialStanding: String,
    /** The raw subscription row, shown so nobody has to guess what it says. */
    val subscriptionRowStatus: String?,
    /** Why that row still looks service-granting during a restriction. */
    val serviceRelationship: String,
    val operationalPolicy: String,
    val paidThrough: Instant?,
    val paymentStatus: String,
    val renewalOfferStatus: String?,
    val outstandingAmount: BigDecimal?,
    val outstandingCurrency: String?,
)

@NamedInterface("api")
data class StuckPayment(
    val tenantId: UUID,
    val tenantName: String?,
    val purchaseId: UUID,
    val attemptId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val provider: String,
    /** Masked: an operator needs to recognise the number, not to read it. */
    val payerMsisdnMasked: String?,
    val internalReference: String,
    val providerReference: String?,
    val startedAt: Instant,
    val lastStatusCheckedAt: Instant?,
    val statusCheckCount: Int,
    val lastProviderStatus: String?,
    val lastStatusError: String?,
)

@NamedInterface("api")
data class IssuedReceipt(
    val receiptNumber: String,
    val tenantId: UUID,
    val purchaseId: UUID,
    val issuedAt: Instant,
    val totalAmount: BigDecimal,
    val currency: String,
)
