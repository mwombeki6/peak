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

@NamedInterface("api")
data class PayPurchaseRequest(
    val payerMsisdn: String,
    /** Provider channel such as Mpesa or Airtel; the adapter validates it. */
    val channel: String?,
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

@NamedInterface("api")
data class PaymentAttemptResponse(
    val id: UUID,
    val purchaseId: UUID,
    val attemptNo: Int,
    val provider: String,
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
    EXPIRED("expired"),
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
