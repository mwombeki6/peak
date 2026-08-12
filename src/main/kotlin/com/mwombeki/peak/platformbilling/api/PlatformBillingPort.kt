package com.mwombeki.peak.platformbilling.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

/**
 * Self-service subscription and add-on purchase, from inside the product.
 *
 * The sequence is deliberately quote → purchase → pay rather than a single call. A quote
 * fixes the price, the currency, the term and the entitlements a purchase will grant, so
 * what the customer approves on their phone is what they were shown. Collapsing the steps
 * would let a catalog change between the two.
 */
@NamedInterface("api")
interface PlatformBillingPort {
    /** Everything sellable, with current prices for each term. */
    fun catalog(): List<ProductSummary>

    /**
     * Prices a selection without committing to it. Rejects a total that cannot be
     * collected on the configured rails, so the limit surfaces before the customer has
     * agreed to anything rather than as a provider error afterwards.
     */
    fun quote(tenantId: UUID, request: QuoteRequest): Quote

    /**
     * Records an immutable order. The price, period and entitlement set are frozen here;
     * later catalog changes do not reach a purchase already made.
     */
    fun createPurchase(tenantId: UUID, request: QuoteRequest): PurchaseResponse

    /**
     * Starts a collection against an open purchase, pushing a PIN prompt to the payer.
     *
     * This never completes the purchase. Mobile money has no mandate, so payment is only
     * ever confirmed by a signed provider callback, applied in the worker.
     */
    fun pay(
        tenantId: UUID,
        purchaseId: UUID,
        request: PayPurchaseRequest,
    ): PaymentAttemptResponse

    fun purchase(tenantId: UUID, purchaseId: UUID): PurchaseResponse?

    fun purchases(tenantId: UUID, limit: Int = 50): List<PurchaseResponse>
}

/**
 * Receives a provider callback for Peak's own collections.
 *
 * Separate from `PaymentWebhookPort`, which settles a *property's* guest payments against
 * a folio using that property's provider account. This settles *Peak's* revenue against
 * Peak's own merchant account, and the two must not share a route or a credential source.
 */
@NamedInterface("api")
interface PlatformBillingWebhookPort {
    fun receive(providerCode: String, payload: String): PlatformBillingWebhookReceipt
}

@NamedInterface("api")
data class PlatformBillingWebhookReceipt(
    val accepted: Boolean,
    val duplicate: Boolean,
    val attemptId: UUID?,
)
