package com.mwombeki.peak.payment.api

import java.util.UUID

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
    fun reversePayment(
        propertyId: UUID,
        transactionId: UUID,
        request: ReversePaymentRequest,
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
}

interface PaymentWebhookPort {
    fun receive(
        providerAccountId: UUID,
        providerEventId: String,
        timestamp: String,
        signature: String,
        payload: String,
    ): PaymentWebhookReceipt
}
