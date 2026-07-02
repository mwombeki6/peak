package com.mwombeki.peak.payment.internal

import java.math.BigDecimal
import java.util.UUID

interface PaymentProviderAdapter {
    val providerCode: String

    fun initiate(command: ProviderCollectionCommand): ProviderCollectionResult

    fun parseWebhook(payload: String): ProviderWebhookNotification
}

data class ProviderCollectionCommand(
    val transactionId: UUID,
    val internalReference: String,
    val endpointUrl: String?,
    val merchantId: String?,
    val payerIdentifier: String,
    val amount: BigDecimal,
    val currency: String,
    val credential: String,
)

data class ProviderCollectionResult(
    val providerReference: String,
    val status: String,
)

data class ProviderWebhookNotification(
    val internalReference: String,
    val providerReference: String,
    val status: String,
    val amount: BigDecimal,
    val feeAmount: BigDecimal = BigDecimal.ZERO,
    val currency: String,
    val metadata: Map<String, Any?> = emptyMap(),
)
