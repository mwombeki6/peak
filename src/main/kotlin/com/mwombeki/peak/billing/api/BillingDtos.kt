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

data class VoidInvoiceRequest(
    val reason: String,
)

data class CreateCreditNoteRequest(
    val reason: String,
    val lines: List<CreditNoteLineRequest>,
)

data class CreditNoteLineRequest(
    val invoiceItemId: UUID,
    val amount: BigDecimal,
    val taxAmount: BigDecimal = BigDecimal.ZERO,
)

data class CreditNoteResponse(
    val id: UUID,
    val propertyId: UUID,
    val invoiceId: UUID,
    val creditNoteNumber: String,
    val reason: String,
    val subtotal: BigDecimal,
    val taxAmount: BigDecimal,
    val totalAmount: BigDecimal,
    val status: String,
    val fiscalStatus: String,
    val issuedAt: Instant?,
    val replayed: Boolean = false,
)

data class ConfirmedPaymentRequest(
    val folioId: UUID,
    val paymentMethod: String,
    val amount: BigDecimal,
    val paymentTransactionId: UUID,
    val cashSessionId: UUID? = null,
    val processedBy: UUID? = null,
    val referenceNumber: String? = null,
    val idempotencyKey: String? = null,
    val notes: String? = null,
)

data class ConfirmedPaymentReversalRequest(
    val originalPaymentTransactionId: UUID,
    val reversalPaymentTransactionId: UUID,
    val processedBy: UUID,
    val reason: String,
    val referenceNumber: String? = null,
    val cashSessionId: UUID? = null,
)

data class ConfirmedPaymentRefundRequest(
    val originalPaymentTransactionId: UUID,
    val refundPaymentTransactionId: UUID,
    val amount: BigDecimal,
    val processedBy: UUID,
    val reason: String,
    val referenceNumber: String,
    val cashSessionId: UUID? = null,
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
    val invoiceId: UUID?,
    val totalAmount: BigDecimal,
    val totalPaid: BigDecimal,
    val balanceDue: BigDecimal,
    val hasIssuedInvoice: Boolean,
)

data class BillingNightAuditSummary(
    val openUnpaidFolios: Int,
    val openUnpaidBalance: BigDecimal = BigDecimal.ZERO,
    val openUnpaidFolioIds: List<UUID> = emptyList(),
    val foliosMissingIssuedInvoice: Int,
    val pendingFolioPayments: Int,
    val issuedInvoiceIds: List<UUID>,
)

data class RevenueCenterCloseTotal(
    val revenueCenterId: UUID?,
    val amount: BigDecimal,
)

data class BillingCloseSnapshotSummary(
    val currency: String,
    val revenueByCenter: List<RevenueCenterCloseTotal>,
    val roomRevenue: BigDecimal,
    val posRevenue: BigDecimal,
    val taxTotal: BigDecimal,
    val grossTotal: BigDecimal,
    val netTotal: BigDecimal,
    val revenueJournalDifference: BigDecimal,
)

data class FiscalInvoiceSnapshot(
    val id: UUID,
    val number: String,
    val currency: String,
    val subtotal: BigDecimal,
    val taxTotal: BigDecimal,
    val total: BigDecimal,
    val status: String,
    val items: List<FiscalInvoiceLineSnapshot>,
)

data class FiscalInvoiceLineSnapshot(
    val id: UUID,
    val description: String,
    val amount: BigDecimal,
    val taxAmount: BigDecimal,
)

data class FiscalCreditNoteSnapshot(
    val id: UUID,
    val invoiceId: UUID,
    val number: String,
    val subtotal: BigDecimal,
    val taxTotal: BigDecimal,
    val total: BigDecimal,
    val status: String,
    val fiscalStatus: String,
    val lines: List<FiscalInvoiceLineSnapshot>,
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
