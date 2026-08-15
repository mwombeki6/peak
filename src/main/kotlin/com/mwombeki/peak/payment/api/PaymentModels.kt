package com.mwombeki.peak.payment.api

import com.mwombeki.peak.shared.exception.BusinessException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.modulith.NamedInterface

data class OpenCashSessionRequest(
    val openingFloat: BigDecimal = BigDecimal.ZERO,
    val notes: String? = null,
)

data class CloseCashSessionRequest(
    val actualCash: BigDecimal,
    val notes: String? = null,
)

data class CashSessionResponse(
    val id: UUID,
    val propertyId: UUID,
    val cashierId: UUID,
    val status: String,
    val openingFloat: BigDecimal,
    val expectedCash: BigDecimal,
    val actualCash: BigDecimal?,
    val variance: BigDecimal?,
    val openedAt: Instant,
    val closedAt: Instant?,
    val replayed: Boolean = false,
)

data class CollectCashPaymentRequest(
    val folioId: UUID,
    val cashSessionId: UUID,
    val amount: BigDecimal,
    val notes: String? = null,
)

data class InitiateMobileMoneyRequest(
    val folioId: UUID,
    val providerAccountId: UUID,
    val phoneNumber: String,
    val amount: BigDecimal,
    /**
     * The network to push the prompt to — Airtel, Tigo, Halopesa, Azampesa or Mpesa.
     *
     * Supplied by the caller and never derived from the phone number. Tanzania has mobile
     * number portability, so a prefix records which operator was originally allocated the
     * range rather than who serves the number today; deriving from it would route some
     * payments to the wrong operator, and the failure would look to the hotel like a guest
     * who did not pay. An interface may reasonably preselect from the prefix, but what Peak
     * sends must be what someone confirmed.
     *
     * Optional only because a provider that infers the network itself, as ClickPesa does,
     * does not need it. Whether it is required is the adapter's business, checked before
     * anything is queued.
     */
    val mobileNetwork: String? = null,
)

data class RecordManualMobileMoneyPaymentRequest(
    val folioId: UUID,
    val providerAccountId: UUID,
    val referenceNumber: String,
    val phoneNumber: String? = null,
    val amount: BigDecimal,
    val notes: String? = null,
)

@NamedInterface("api")
data class CollectPosCashPaymentRequest(
    val posOrderId: UUID,
    val amount: BigDecimal,
)

@NamedInterface("api")
data class InitiatePosMobileMoneyRequest(
    val posOrderId: UUID,
    val providerAccountId: UUID,
    val phoneNumber: String,
    val amount: BigDecimal,
    /** See [InitiateMobileMoneyRequest.mobileNetwork]. A POS collection is the same push. */
    val mobileNetwork: String? = null,
)

data class ReversePaymentRequest(
    val reason: String,
    val externalReference: String? = null,
    val cashSessionId: UUID? = null,
)

data class RefundPaymentRequest(
    val amount: BigDecimal,
    val reason: String,
    val cashSessionId: UUID? = null,
    val providerEvidence: String? = null,
)

@NamedInterface("api")
enum class PaymentStatus(val databaseValue: String) {
    CREATED("created"),
    INITIATED("initiated"),
    PENDING("pending"),
    POSTED("posted"),
    FAILED("failed"),
    EXPIRED("expired"),
    REVERSED("reversed"),
    PARTIALLY_REFUNDED("partially_refunded"),
    REFUNDED("refunded"),
    RECONCILED("reconciled");

    companion object {
        fun fromDatabase(value: String): PaymentStatus {
            return entries.firstOrNull { it.databaseValue == value.lowercase() }
                ?: throw IllegalArgumentException(
                    "Unknown payment status: $value",
                )
        }
    }
}

@NamedInterface("api")
data class PaymentTransactionResponse(
    val id: UUID,
    val propertyId: UUID,
    val folioId: UUID?,
    val posOrderId: UUID? = null,
    val providerAccountId: UUID?,
    val transactionType: String,
    val providerReference: String?,
    val internalReference: String,
    val amount: BigDecimal,
    val feeAmount: BigDecimal,
    val currency: String,
    val status: PaymentStatus,
    val initiatedAt: Instant,
    val postedAt: Instant?,
    val confirmedAt: Instant?,
    val failedAt: Instant?,
    val expiresAt: Instant? = null,
    val refundedAmount: BigDecimal = BigDecimal.ZERO,
    val refundOfTransactionId: UUID? = null,
    val reversalOfTransactionId: UUID? = null,
    val replayed: Boolean = false,
)

data class ConfigurePaymentProviderRequest(
    val providerCode: String,
    val providerName: String,
    val accountName: String,
    val endpointUrl: String? = null,
    val merchantId: String? = null,
    val clientId: String? = null,
    val walletNumber: String? = null,
    val secretRef: String? = null,
    val webhookSecretRef: String? = null,
    val apiKeySecretRef: String? = null,
    val checksumKeySecretRef: String? = null,
    val environment: String = "sandbox",
    val sandboxCertifiedAt: Instant? = null,
    val sandboxEvidenceRef: String? = null,
    val isDefault: Boolean = false,
)

data class PaymentProviderAccountResponse(
    val id: UUID,
    val propertyId: UUID,
    val providerCode: String,
    val providerName: String,
    val accountName: String,
    val merchantId: String?,
    val clientId: String? = null,
    val walletNumber: String?,
    val isDefault: Boolean,
    val isActive: Boolean,
    val environment: String = "sandbox",
    val sandboxCertifiedAt: Instant? = null,
    val sandboxEvidenceRef: String? = null,
    val lifecycleStatus: String = "configured",
    val eligibleForCollection: Boolean = false,
    val replayed: Boolean = false,
)

data class CertifyPaymentProviderRequest(
    val sandboxCertifiedAt: Instant,
    val sandboxEvidenceRef: String,
)

data class ReconciliationItemRequest(
    val providerReference: String,
    val itemDate: Instant,
    val providerAmount: BigDecimal,
)

data class CreatePaymentReconciliationRequest(
    val providerAccountId: UUID,
    val reconciliationDate: LocalDate,
    val statementReference: String,
    val openingBalance: BigDecimal = BigDecimal.ZERO,
    val items: List<ReconciliationItemRequest>,
    val notes: String? = null,
)

data class PaymentReconciliationResponse(
    val id: UUID,
    val propertyId: UUID,
    val providerAccountId: UUID,
    val reconciliationDate: LocalDate,
    val statementReference: String,
    val providerTotal: BigDecimal,
    val systemTotal: BigDecimal,
    val variance: BigDecimal,
    val status: String,
    val replayed: Boolean = false,
)

data class ImportPaymentReconciliationRequest(
    val providerAccountId: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val currency: String = "TZS",
)

data class PaymentReconciliationImportResponse(
    val importId: UUID,
    val providerAccountId: UUID,
    val status: String,
    val replayed: Boolean = false,
)

data class PaymentWebhookReceipt(
    val providerEventId: String,
    val transactionId: UUID?,
    val status: PaymentStatus,
    val replayed: Boolean,
)

open class PaymentException(
    message: String,
    status: HttpStatus,
    code: String,
) : BusinessException(message, status, code)

class PaymentNotFoundException(message: String) :
    PaymentException(message, HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND")

class PaymentConflictException(message: String) :
    PaymentException(message, HttpStatus.CONFLICT, "PAYMENT_CONFLICT")

class PaymentAccessDeniedException(message: String) :
    PaymentException(message, HttpStatus.FORBIDDEN, "PAYMENT_ACCESS_DENIED")

class PaymentRejectedException(message: String) :
    PaymentException(message, HttpStatus.BAD_REQUEST, "PAYMENT_REJECTED")

class PaymentProviderUnavailableException(message: String) :
    PaymentException(message, HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_PROVIDER_UNAVAILABLE")

@NamedInterface("api")
data class PaymentNightAuditSummary(
    val nonTerminalTransactions: Int,
)

@NamedInterface("api")
data class PaymentCloseSnapshotSummary(
    val paymentsByMethod: Map<String, BigDecimal>,
    val cashVariance: BigDecimal,
    val providerReconciliation: BigDecimal,
    val refunds: BigDecimal,
    val reversals: BigDecimal,
    val allocationDifference: BigDecimal,
)
