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
     * Pushes a PIN prompt to the payer for an open purchase.
     *
     * This never completes the purchase. Mobile money has no mandate, so payment is only
     * ever confirmed by a signed provider callback, applied in the worker. A result of
     * PENDING means the prompt was sent, not that anything was paid.
     */
    fun pay(
        tenantId: UUID,
        purchaseId: UUID,
        request: PayPurchaseRequest,
    ): PaymentAttemptResponse

    fun purchase(tenantId: UUID, purchaseId: UUID): PurchaseResponse?

    fun purchases(tenantId: UUID, limit: Int = 50): List<PurchaseResponse>

    /**
     * The tenant's own receipts for what they have paid Peak.
     *
     * Peak already issued these, with a sequential number, and only Peak could read them. For
     * a Tanzanian business the receipt is what their bookkeeping and their own filing rest on,
     * so withholding it is not a missing feature but a document held hostage.
     */
    fun receipts(tenantId: UUID, limit: Int = 50): List<Receipt>
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
    /**
     * @param headers the callback's HTTP headers. Some providers sign in a header rather
     *   than in the body, so verification needs them; one that signs in the body ignores
     *   them harmlessly.
     */
    fun receive(
        providerCode: String,
        payload: String,
        headers: Map<String, String> = emptyMap(),
    ): PlatformBillingWebhookReceipt
}

@NamedInterface("api")
data class PlatformBillingWebhookReceipt(
    val accepted: Boolean,
    val duplicate: Boolean,
    val attemptId: UUID?,
)
