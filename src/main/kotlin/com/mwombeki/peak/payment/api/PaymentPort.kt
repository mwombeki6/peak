package com.mwombeki.peak.payment.api

import java.time.LocalDate
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface PaymentPort {
    fun openCashSession(propertyId: UUID, request: OpenCashSessionRequest): CashSessionResponse
    fun currentCashSession(propertyId: UUID): CashSessionResponse?
    fun closeCashSession(
        propertyId: UUID,
        cashSessionId: UUID,
        request: CloseCashSessionRequest,
    ): CashSessionResponse

    fun collectCash(propertyId: UUID, request: CollectCashPaymentRequest): PaymentTransactionResponse
    fun initiateMobileMoney(
        propertyId: UUID,
        request: InitiateMobileMoneyRequest,
    ): PaymentTransactionResponse
    fun recordManualMobileMoney(
        propertyId: UUID,
        request: RecordManualMobileMoneyPaymentRequest,
    ): PaymentTransactionResponse
    fun collectPosCash(
        tenantId: UUID,
        propertyId: UUID,
        request: CollectPosCashPaymentRequest,
        idempotencyKeyId: UUID,
    ): PaymentTransactionResponse
    fun initiatePosMobileMoney(
        tenantId: UUID,
        propertyId: UUID,
        request: InitiatePosMobileMoneyRequest,
        idempotencyKeyId: UUID,
    ): PaymentTransactionResponse
    fun reversePayment(
        propertyId: UUID,
        transactionId: UUID,
        request: ReversePaymentRequest,
    ): PaymentTransactionResponse
    fun refundPayment(
        propertyId: UUID,
        transactionId: UUID,
        request: RefundPaymentRequest,
    ): PaymentTransactionResponse

    fun getTransaction(propertyId: UUID, transactionId: UUID): PaymentTransactionResponse?
    fun listTransactions(propertyId: UUID, limit: Int = 100): List<PaymentTransactionResponse>
    fun configureProvider(
        propertyId: UUID,
        request: ConfigurePaymentProviderRequest,
    ): PaymentProviderAccountResponse

    fun listProviderAccounts(propertyId: UUID): List<PaymentProviderAccountResponse>
    fun createReconciliation(
        propertyId: UUID,
        request: CreatePaymentReconciliationRequest,
    ): PaymentReconciliationResponse

    fun approveReconciliation(
        propertyId: UUID,
        reconciliationId: UUID,
    ): PaymentReconciliationResponse
    fun listReconciliations(
        propertyId: UUID,
        limit: Int = 100,
    ): List<PaymentReconciliationResponse>
    fun getReconciliation(
        propertyId: UUID,
        reconciliationId: UUID,
    ): PaymentReconciliationResponse?
    fun importReconciliation(
        propertyId: UUID,
        request: ImportPaymentReconciliationRequest,
    ): PaymentReconciliationImportResponse
}

@NamedInterface("api")
interface PaymentWebhookPort {
    /**
     * @param headers the callback's HTTP headers, for a provider that signs in one rather
     *   than in the body. A provider that signs in the body ignores them.
     */
    fun receive(
        providerAccountId: UUID,
        payload: String,
        headers: Map<String, String> = emptyMap(),
    ): PaymentWebhookReceipt
}

@NamedInterface("api")
interface PaymentStatusPort {
    fun nightAuditSummary(
        tenantId: UUID,
        propertyId: UUID,
    ): PaymentNightAuditSummary

    fun closeSnapshotSummary(
        tenantId: UUID,
        propertyId: UUID,
        businessDate: LocalDate,
    ): PaymentCloseSnapshotSummary
}
