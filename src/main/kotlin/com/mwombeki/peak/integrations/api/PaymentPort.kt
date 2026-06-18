package com.mwombeki.peak.integrations.api

import java.util.UUID

enum class PaymentProvider {
    VODACOM_MPESA,
    TIGO_PESA,
    AIRTEL_MONEY,
    HALOPESA,
    AZAMPESA,
    NMB,
    CRDB,
    NBC
}

enum class PaymentMethod {
    MOBILE_MONEY,
    BANK_TRANSFER
}

data class InitiatePaymentRequest(
    val sessionId: UUID,
    val provider: PaymentProvider,
    val paymentMethod: PaymentMethod,
    val phoneNumber: String?, // Optional for bank transfer
    val accountNumber: String?, // For bank transfer
    val amount: Double,
)

data class PaymentStatusResponse(
    val referenceId: String,
    val status: String,
    val message: String,
)

interface PaymentPort {
    fun initiatePayment(request: InitiatePaymentRequest): PaymentStatusResponse
}