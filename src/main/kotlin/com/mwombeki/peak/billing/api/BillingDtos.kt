package com.mwombeki.peak.billing.api

import com.mwombeki.peak.shared.exception.BusinessException
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus

data class FolioResponse(
    val id: UUID,
    val tenantId: UUID,
    val propertyId: UUID,
    val reservationId: UUID?,
    val status: String,
    val currencyCode: String,
    val subtotal: BigDecimal,
    val taxAmount: BigDecimal,
    val serviceCharge: BigDecimal,
    val tourismLevy: BigDecimal,
    val totalAmount: BigDecimal,
    val totalPaid: BigDecimal,
    val balanceDue: BigDecimal,
    val charges: List<FolioChargeResponse> = emptyList(),
    val payments: List<FolioPaymentResponse> = emptyList(),
    val invoices: List<InvoiceResponse> = emptyList(),
)

data class FolioChargeResponse(
    val id: UUID,
    val folioId: UUID,
    val propertyId: UUID,
    val revenueCenterId: UUID?,
    val chargeType: String,
    val description: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val subtotal: BigDecimal,
    val taxRate: BigDecimal,
    val taxAmount: BigDecimal,
    val amount: BigDecimal,
    val status: String,
    val postedAt: Instant,
)

data class FolioPaymentResponse(
    val id: UUID,
    val folioId: UUID,
    val propertyId: UUID?,
    val paymentMethod: String,
    val amount: BigDecimal,
    val referenceNumber: String?,
    val status: String,
    val paidAt: Instant?,
)

data class InvoiceResponse(
    val id: UUID,
    val folioId: UUID,
    val propertyId: UUID?,
    val invoiceNumber: String?,
    val subtotal: BigDecimal,
    val vatTotal: BigDecimal,
    val serviceCharge: BigDecimal,
    val tourismLevy: BigDecimal,
    val total: BigDecimal,
    val status: String,
    val issuedAt: Instant?,
)

data class PostChargeRequest(
    val chargeType: String = "MISC",
    val description: String,
    val revenueCenterId: UUID? = null,
    val quantity: BigDecimal = BigDecimal.ONE,
    val unitPrice: BigDecimal,
    val taxRate: BigDecimal? = null,
    val sourceType: String? = null,
    val sourceId: UUID? = null,
)

data class ReverseChargeRequest(
    val reason: String,
)

data class IssueInvoiceRequest(
    val dueDateDays: Int = 0,
)

data class ConfirmedPaymentRequest(
    val folioId: UUID,
    val paymentMethod: String,
    val amount: BigDecimal,
    val referenceNumber: String? = null,
    val idempotencyKey: String? = null,
    val notes: String? = null,
)

data class PostPaymentRequest(
    val paymentMethod: String,
    val amount: BigDecimal,
    val referenceNumber: String? = null,
    val notes: String? = null,
)

data class BillingMutationReceipt(
    val propertyId: UUID,
    val folioId: UUID,
    val resourceType: String,
    val resourceId: UUID,
    val changed: Boolean,
    val replayed: Boolean,
)

data class CheckoutFinancialState(
    val folioId: UUID,
    val totalAmount: BigDecimal,
    val totalPaid: BigDecimal,
    val balanceDue: BigDecimal,
    val hasIssuedInvoice: Boolean,
    val hasAcceptedFiscalReceipt: Boolean,
)

sealed class BillingException(
    message: String,
    status: HttpStatus,
    code: String,
) : BusinessException(message = message, status = status, errorCode = code)

class BillingNotFoundException(message: String) : BillingException(
    message = message,
    status = HttpStatus.NOT_FOUND,
    code = "BILLING_NOT_FOUND",
)

class BillingConflictException(message: String) : BillingException(
    message = message,
    status = HttpStatus.CONFLICT,
    code = "BILLING_CONFLICT",
)

class BillingInProgressException(message: String) : BillingException(
    message = message,
    status = HttpStatus.CONFLICT,
    code = "BILLING_COMMAND_IN_PROGRESS",
)
