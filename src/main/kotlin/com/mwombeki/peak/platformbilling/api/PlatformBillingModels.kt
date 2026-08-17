package com.mwombeki.peak.platformbilling.api

import com.mwombeki.peak.shared.exception.BusinessException
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.modulith.NamedInterface

/**
 * A sellable thing, which is deliberately not a technical module.
 *
 * A base product corresponds to a plan and a tier; an add-on grants entitlements without
 * one, because a tenant may hold only a single service-granting subscription. What a
 * product contains can change over time, which is why a purchase records its own snapshot
 * rather than pointing at the catalog.
 */
@NamedInterface("api")
data class ProductSummary(
    val code: String,
    val name: String,
    val description: String?,
    val kind: ProductKind,
    val isPerProperty: Boolean,
    val requiresProductCode: String?,
    val entitlements: List<String>,
    val prices: List<ProductPrice>,
)

@NamedInterface("api")
enum class ProductKind(val databaseValue: String) {
    BASE("base"),
    ADDON("addon"),
    ;

    companion object {
        fun fromDatabase(value: String): ProductKind =
            entries.firstOrNull { it.databaseValue == value }
                ?: throw IllegalArgumentException("Unknown product kind: $value")
    }
}

@NamedInterface("api")
data class ProductPrice(
    val termMonths: Int,
    val currency: String,
    val amount: BigDecimal,
)

@NamedInterface("api")
data class QuoteRequest(
    val lines: List<QuoteLineRequest>,
    val termMonths: Int,
)

@NamedInterface("api")
data class QuoteLineRequest(
    val productCode: String,
    /** Ignored for tenant-scoped products; required for a per-property add-on. */
    val propertyIds: List<UUID> = emptyList(),
)

@NamedInterface("api")
data class Quote(
    val lines: List<QuoteLine>,
    val termMonths: Int,
    val currency: String,
    val totalAmount: BigDecimal,
    val periodStartsAt: Instant,
    val periodEndsAt: Instant,
    val expiresAt: Instant,
    /**
     * How this amount could be paid.
     *
     * A quote is always priced, even when no rail can currently carry it: what a customer
     * may buy and how they may pay are independent questions, and conflating them once led
     * to refusing to price a perfectly sellable annual contract because it would not fit
     * down a USSD prompt.
     */
    val paymentMethods: List<PaymentMethodOption> = emptyList(),
)

@NamedInterface("api")
data class QuoteLine(
    val productCode: String,
    val productName: String,
    val quantity: Int,
    val coveredPropertyIds: List<UUID>,
    val unitAmount: BigDecimal,
    val amount: BigDecimal,
)

@NamedInterface("api")
data class PurchaseResponse(
    val id: UUID,
    val status: PurchaseStatus,
    val currency: String,
    val termMonths: Int,
    val totalAmount: BigDecimal,
    val periodStartsAt: Instant,
    val periodEndsAt: Instant,
    val quoteExpiresAt: Instant,
    val lines: List<QuoteLine>,
    val replayed: Boolean = false,
)

@NamedInterface("api")
enum class PurchaseStatus(val databaseValue: String) {
    QUOTED("quoted"),
    AWAITING_PAYMENT("awaiting_payment"),
    PAID("paid"),
    FAILED("failed"),
    EXPIRED("expired"),
    CANCELLED("cancelled"),
    ;

    companion object {
        fun fromDatabase(value: String): PurchaseStatus =
            entries.firstOrNull { it.databaseValue == value }
                ?: throw IllegalArgumentException("Unknown purchase status: $value")
    }
}

/**
 * The rail money travels down, which is not the same thing as the provider.
 *
 * AzamPay offers several. They differ in what the payer must supply and, crucially, in what
 * a single transaction may carry — which is why an amount that cannot go by mobile money is
 * a payment-method question rather than a reason to refuse the sale.
 */
@NamedInterface("api")
enum class PaymentMethod(val databaseValue: String) {
    MOBILE_MONEY("mobile_money"),
    BANK("bank"),
    CARD("card"),
    ;

    companion object {
        fun fromDatabase(value: String): PaymentMethod =
            entries.firstOrNull { it.databaseValue == value }
                ?: throw IllegalArgumentException("Unknown payment method: $value")
    }
}

/**
 * A rail a given amount can actually travel down, with why it can or cannot.
 *
 * Returned alongside a quote so the customer is offered the methods that will work rather
 * than discovering the limit after committing.
 */
/**
 * How a customer experiences paying, which is not the same as the rail.
 *
 * Mobile money offered as a hosted checkout page and mobile money offered as a USSD push to
 * a known handset are different integrations against different endpoints, and only the
 * second is what "click Pay and answer your phone" means.
 */
@NamedInterface("api")
enum class CollectionFlow(val databaseValue: String) {
    DIRECT_PUSH("direct_push"),
    HOSTED_CHECKOUT("hosted_checkout"),
    ;

    companion object {
        fun fromDatabase(value: String): CollectionFlow =
            entries.firstOrNull { it.databaseValue == value }
                ?: throw IllegalArgumentException("Unknown collection flow: $value")
    }
}

@NamedInterface("api")
data class PaymentMethodOption(
    val provider: String,
    val method: PaymentMethod,
    val collectionFlow: CollectionFlow,
    val currency: String,
    val requiresMsisdn: Boolean,
    val eligible: Boolean,
    /** Present when [eligible] is false, phrased for the person reading it. */
    val ineligibleReason: String?,
    val maxAmount: BigDecimal?,
)

@NamedInterface("api")
data class PayPurchaseRequest(
    /** Required for mobile money, meaningless for a bank transfer. */
    val payerMsisdn: String? = null,
    /** Provider channel such as Mpesa or Airtel; the adapter validates it. */
    val channel: String? = null,
    val method: PaymentMethod = PaymentMethod.MOBILE_MONEY,
)

/**
 * A reminder that cover is running out, deliberately carrying no price.
 *
 * An offer is not a purchase and holds no checkout slot — see `RenewalOfferService` for why
 * that distinction is load-bearing rather than pedantic. It also carries no amount, so
 * accepting one prices against today's catalog rather than replaying last year's invoice.
 */
@NamedInterface("api")
data class RenewalOffer(
    val id: UUID,
    val coverEndsAt: Instant,
    val termMonths: Int,
    val status: RenewalOfferStatus,
    val renewsPurchaseId: UUID?,
)

@NamedInterface("api")
enum class RenewalOfferStatus(val databaseValue: String) {
    OFFERED("offered"),
    ACCEPTED("accepted"),
    DECLINED("declined"),
    EXPIRED("expired"),
    SUPERSEDED("superseded"),
    ;

    companion object {
        fun fromDatabase(value: String): RenewalOfferStatus =
            entries.firstOrNull { it.databaseValue == value }
                ?: throw IllegalArgumentException("Unknown renewal offer status: $value")
    }
}

/**
 * What Peak issued for a settled purchase.
 *
 * A commercial receipt for a subscription, not a fiscal receipt — those are guest-facing,
 * belong to the fiscal module, and answer to TRA rules that do not apply here.
 */
@NamedInterface("api")
/**
 * FBC's commercial receipt to a tenant for a Peak subscription purchase.
 *
 * **Not a TRA fiscal receipt.** It carries no TIN or VRN, no EFD identifiers, no tax
 * breakdown and no TRA verification code, and a sequential number that reads
 * `PEAK-RCP-2026-000123` should not be mistaken for one — that resemblance is the whole
 * danger. Fiscalizing FBC's own SaaS sales happens under FBC's taxpayer identity in a
 * workflow that does not exist yet, which is what [fiscalStatus] reports.
 *
 * The hotel's fiscal receipt to its guest is a different document, under a different
 * taxpayer, from a different allocator. The two must never share numbering.
 */
data class Receipt(
    val id: UUID,
    val purchaseId: UUID,
    val receiptNumber: String,
    val issuedAt: Instant,
    val totalAmount: BigDecimal,
    val currency: String,
    /**
     * Whether a TRA fiscal document exists for this sale. Rendered rather than assumed, so
     * a commercial receipt is never presented as fiscal evidence.
     */
    val fiscalStatus: FiscalStatus = FiscalStatus.NOT_APPLICABLE,
    /** The fiscal document's own identifier, once one exists. */
    val fiscalReference: String? = null,
) {
    /**
     * The fiscal state of **this sale**, never the state of FBC's fiscal integration.
     *
     * The distinction is not pedantic and is the one thing to get right before FBC
     * fiscalization is built. If `NOT_APPLICABLE` is ever allowed to absorb "the integration
     * is off", "it is not configured" or "TRA is unreachable", then a production
     * configuration fault silently produces `NOT_APPLICABLE` on a sale that was legally
     * required to be fiscalized — and it will read as a deliberate exemption forever after,
     * because nothing distinguishes the two.
     *
     * Those belong in a separate, FBC-wide concept, roughly:
     *
     * ```
     * FBC fiscal configuration        this receipt
     * ────────────────────────        ────────────
     * DISABLED                        NOT_APPLICABLE  this sale is genuinely exempt
     * REQUIRED                        PENDING         owed, not yet issued
     * OPTIONAL                        ISSUED          done, see fiscalReference
     *                                 FAILED          attempted and refused
     * ```
     *
     * Under a `REQUIRED` configuration, `NOT_APPLICABLE` must be unreachable: an outage makes
     * a sale `PENDING` or `FAILED`, both of which are recoverable states someone is expected
     * to act on. `NOT_APPLICABLE` says nobody needs to.
     */
    enum class FiscalStatus {
        /**
         * This sale is legitimately outside the fiscal workflow. Today that is every sale,
         * because no FBC fiscalization exists — but it must keep meaning "exempt" rather than
         * drifting into "unavailable".
         */
        NOT_APPLICABLE,

        /** Fiscalization is owed for this sale and has not completed. */
        PENDING,

        /** A fiscal document exists; [fiscalReference] identifies it. */
        ISSUED,

        /** Fiscalization was attempted and refused. Recoverable, and someone must act. */
        FAILED,
    }
}

@NamedInterface("api")
data class PaymentAttemptResponse(
    val id: UUID,
    val purchaseId: UUID,
    val attemptNo: Int,
    val provider: String,
    val method: PaymentMethod = PaymentMethod.MOBILE_MONEY,
    val status: PaymentAttemptStatus,
    val internalReference: String,
    /** Present only for a provider that hosts its own checkout page. */
    val redirectUrl: String?,
    val replayed: Boolean = false,
)

@NamedInterface("api")
enum class PaymentAttemptStatus(val databaseValue: String) {
    CREATED("created"),
    INITIATED("initiated"),
    PENDING("pending"),
    CONFIRMED("confirmed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    EXPIRED("expired"),

    /**
     * We do not know whether the money moved.
     *
     * Not a failure. The provider never came back and has not yet answered a status query,
     * so the payment may well have succeeded. This state deliberately still occupies the
     * one-open-attempt slot: offering the customer another payment button here is how a
     * subscription gets collected twice.
     */
    RECONCILIATION_REQUIRED("reconciliation_required"),
    ;

    companion object {
        fun fromDatabase(value: String): PaymentAttemptStatus =
            entries.firstOrNull { it.databaseValue == value }
                ?: throw IllegalArgumentException("Unknown payment attempt status: $value")
    }
}

@NamedInterface("api")
class PlatformBillingNotFoundException(message: String) : BusinessException(
    message = message,
    status = HttpStatus.NOT_FOUND,
    errorCode = "PLATFORM_BILLING_NOT_FOUND",
)

@NamedInterface("api")
class PlatformBillingConflictException(message: String) : BusinessException(
    message = message,
    status = HttpStatus.CONFLICT,
    errorCode = "PLATFORM_BILLING_CONFLICT",
)

/**
 * Raised when a quote is valid commercially but cannot be collected on the configured
 * rails — most often because mobile money caps a single transaction and a long term on a
 * large estate exceeds it. Carrying its own type keeps the remedy in the message rather
 * than surfacing an opaque provider rejection after the customer has committed.
 */
@NamedInterface("api")
class PlatformBillingUncollectableException(message: String) : BusinessException(
    message = message,
    status = HttpStatus.UNPROCESSABLE_CONTENT,
    errorCode = "PLATFORM_BILLING_UNCOLLECTABLE",
)
