package com.mwombeki.peak.payment.api

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface PaymentProvider {
    val providerCode: String

    /**
     * Whether this provider must be told which mobile network to push to.
     *
     * A provider's own business, declared here rather than listed in the payment module.
     * A caller holding that list would have to be edited every time a provider is added,
     * and a provider missed off it fails in the worker — after the work is queued, which is
     * the failure this exists to prevent.
     *
     * False by default: most providers work the network out from the MSISDN, and asking a
     * front desk a question the provider does not need answered is its own kind of defect.
     */
    val requiresMobileNetwork: Boolean get() = false

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

    /**
     * For a provider that signs in HTTP headers rather than in the body.
     *
     * A separate method rather than a defaulted parameter, because adding a parameter to the
     * method above would break every existing override. This one delegates by default, so a
     * provider that signs in the body — ClickPesa, AzamPay — needs no change at all.
     *
     * Callers should prefer this: it is a superset, and a provider that ignores headers is
     * unaffected by being handed them.
     */
    fun verifyAndParseWebhook(
        payload: String,
        checksumKey: String,
        checksumRequired: Boolean,
        headers: Map<String, String>,
    ): ProviderWebhookNotification {
        return verifyAndParseWebhook(payload, checksumKey, checksumRequired)
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
    /**
     * Which collection experience to open, where a provider offers more than one.
     *
     * Snippe collects mobile money two ways — a USSD push to a handset we supply, and a
     * hosted checkout page — against different endpoints with different request shapes.
     * Naming the rail does not choose between them.
     */
    val collectionFlow: String? = null,
    /**
     * The payer's name, where the provider requires one. Snippe's direct mobile money
     * endpoint does; a USSD push does not otherwise need it.
     */
    val payerName: String? = null,
    /** The payer's email, likewise required by some providers and by no rail's mechanics. */
    val payerEmail: String? = null,
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
    /**
     * The reference the provider issued at initiation, where its status endpoint is keyed on
     * that rather than on ours. AzamPay's transactionId and Snippe's payment reference both
     * are; passing Peak's own reference would simply not be found.
     */
    val providerReference: String? = null,
    /**
     * Which flow created the payment being asked about, since a provider offering several
     * exposes a different status endpoint for each. Inferring it from the shape of a
     * reference would work today and break the day a prefix changes.
     */
    val collectionFlow: String? = null,
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
