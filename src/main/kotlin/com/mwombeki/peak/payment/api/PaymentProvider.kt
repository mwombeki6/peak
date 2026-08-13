package com.mwombeki.peak.payment.api

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface PaymentProvider {
    val providerCode: String

    fun initiate(command: ProviderCollectionCommand): ProviderCollectionResult

    fun queryStatus(command: ProviderStatusQuery): ProviderStatusResult {
        throw UnsupportedOperationException(
            "$providerCode does not support status queries",
        )
    }

    fun statement(command: ProviderStatementQuery): ProviderStatementResult {
        throw UnsupportedOperationException(
            "$providerCode does not support statement queries",
        )
    }

    fun parseWebhook(payload: String): ProviderWebhookNotification

    fun verifyAndParseWebhook(
        payload: String,
        checksumKey: String,
        checksumRequired: Boolean,
    ): ProviderWebhookNotification {
        return parseWebhook(payload)
    }
}

@NamedInterface("api")
data class ProviderCollectionCommand(
    val transactionId: UUID,
    val internalReference: String,
    val endpointUrl: String?,
    val clientId: String,
    val payerIdentifier: String,
    val amount: BigDecimal,
    val currency: String,
    val apiKey: String,
    val checksumKey: String,
    /**
     * The mobile network to push to, where the provider will not infer it from the MSISDN.
     * AzamPay requires one of Airtel, Tigo, Halopesa, Azampesa or Mpesa; ClickPesa derives
     * it itself and ignores this. Defaulted so existing callers and providers are untouched.
     */
    val providerChannel: String? = null,
)

@NamedInterface("api")
data class ProviderCollectionResult(
    val providerReference: String,
    val status: String,
    val providerStatus: String = status,
    val providerTimestamp: Instant? = null,
    /**
     * Where to send the payer, for a provider that hosts its own checkout page rather than
     * pushing a PIN prompt to their handset. Null for a pure USSD push.
     */
    val redirectUrl: String? = null,
)

@NamedInterface("api")
data class ProviderStatusQuery(
    val internalReference: String,
    val endpointUrl: String?,
    val clientId: String,
    val apiKey: String,
    val checksumKey: String,
)

@NamedInterface("api")
data class ProviderStatusResult(
    val internalReference: String,
    val providerReference: String?,
    val status: String,
    val providerStatus: String,
    val amount: BigDecimal?,
    val currency: String?,
    val clientId: String?,
    val providerTimestamp: Instant?,
)

@NamedInterface("api")
data class ProviderStatementQuery(
    val endpointUrl: String?,
    val clientId: String,
    val apiKey: String,
    val checksumKey: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val currency: String,
)

@NamedInterface("api")
data class ProviderStatementResult(
    val openingBalance: BigDecimal,
    val closingBalance: BigDecimal,
    val items: List<ProviderStatementItem>,
)

@NamedInterface("api")
data class ProviderStatementItem(
    val providerReference: String,
    val orderReference: String?,
    val occurredAt: Instant,
    val amount: BigDecimal,
)

@NamedInterface("api")
data class ProviderWebhookNotification(
    val eventKey: String,
    val eventType: String,
    val internalReference: String,
    val providerReference: String,
    val status: String,
    val amount: BigDecimal,
    val feeAmount: BigDecimal = BigDecimal.ZERO,
    val currency: String,
    val clientId: String?,
    val providerTimestamp: Instant?,
    val checksumMethod: String?,
    val metadata: Map<String, Any?> = emptyMap(),
)
