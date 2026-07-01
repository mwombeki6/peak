package com.mwombeki.peak.payment.api

import com.mwombeki.peak.shared.exception.BusinessException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.http.HttpStatus

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
)

data class RecordManualMobileMoneyPaymentRequest(
    val folioId: UUID,
    val providerAccountId: UUID,
    val referenceNumber: String,
    val phoneNumber: String? = null,
    val amount: BigDecimal,
    val notes: String? = null,
)

data class ReversePaymentRequest(
    val reason: String,
    val externalReference: String? = null,
    val cashSessionId: UUID? = null,
)

data class PaymentTransactionResponse(
    val id: UUID,
    val propertyId: UUID,
    val folioId: UUID,
    val providerAccountId: UUID?,
    val transactionType: String,
    val providerReference: String?,
    val internalReference: String,
    val amount: BigDecimal,
    val feeAmount: BigDecimal,
    val currency: String,
    val status: String,
    val initiatedAt: Instant,
    val confirmedAt: Instant?,
    val failedAt: Instant?,
    val reversalOfTransactionId: UUID? = null,
    val replayed: Boolean = false,
)

data class ConfigurePaymentProviderRequest(
    val providerCode: String,
    val providerName: String,
    val accountName: String,
    val endpointUrl: String? = null,
    val merchantId: String? = null,
    val walletNumber: String? = null,
    val secretRef: String,
    val webhookSecretRef: String,
    val isDefault: Boolean = false,
)

data class PaymentProviderAccountResponse(
    val id: UUID,
    val propertyId: UUID,
    val providerCode: String,
    val providerName: String,
    val accountName: String,
    val merchantId: String?,
    val walletNumber: String?,
    val isDefault: Boolean,
    val isActive: Boolean,
    val replayed: Boolean = false,
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

data class PaymentWebhookReceipt(
    val providerEventId: String,
    val transactionId: UUID?,
    val status: String,
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

class PaymentRejectedException(message: String) :
    PaymentException(message, HttpStatus.BAD_REQUEST, "PAYMENT_REJECTED")

class PaymentProviderUnavailableException(message: String) :
    PaymentException(message, HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_PROVIDER_UNAVAILABLE")
