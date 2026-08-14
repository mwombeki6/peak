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

/**
 * What a provider said about a payment, reduced to the outcomes Peak acts on.
 *
 * This exists because it did not, and its absence was silently fatal. Every adapter mapped its
 * provider's vocabulary onto a bare `String` and each picked a different word: ClickPesa said
 * `posted`, Snippe and AzamPay said `succeeded`. The payment domain compared against
 * `posted`, so ClickPesa worked and the other two had every callback rejected — after a
 * correct signature check, by a validator one layer further in. Nothing failed loudly; a
 * hotel on either rail would simply watch collections sit pending until the sweep gave up.
 *
 * `posted` is the clue to how it happened: that is not an outcome a provider can report, it is
 * a state in Peak's own `payment_transactions`. The first adapter mapped straight onto the
 * database and the boundary was never named, so the second and third adapter authors had
 * nothing to be wrong about until production.
 *
 * Mapping a provider's words onto these five is the adapter's job and only the adapter's.
 * Past this boundary nothing may ask which PSP an answer came from.
 */
@NamedInterface("api")
enum class ProviderPaymentStatus {
    /** Still in flight. The payer has not yet acted, or the provider has not yet said. */
    PENDING,

    SUCCEEDED,

    /** The provider says it will not happen. Distinct from Peak being unable to find out. */
    FAILED,

    /** Abandoned by the payer or withdrawn by the provider. Terminal, and not a fault. */
    CANCELLED,

    /**
     * Peak could not determine the outcome: a timeout, an unreadable response, a status word
     * no adapter recognises. Never treated as failure — not knowing is not knowing, and
     * collapsing the two is how a guest is asked to pay a second time for the same night.
     */
    UNKNOWN,
}

@NamedInterface("api")
data class ProviderCollectionResult(
    val providerReference: String,
    val status: ProviderPaymentStatus,
    /** The provider's own word, kept as evidence rather than for decisions. */
    val providerStatus: String,
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
    val status: ProviderPaymentStatus,
    /** The provider's own word, kept as evidence rather than for decisions. */
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
    /**
     * The provider's own name for the event. Evidence only: it is one vocabulary per provider
     * and the domain must never branch on it, which it used to, by enumerating ClickPesa's
     * two event names and so rejecting every other provider's callbacks.
     */
    val eventType: String,
    val internalReference: String,
    val providerReference: String,
    val status: ProviderPaymentStatus,
    /** The provider's own word, kept as evidence rather than for decisions. */
    val providerStatus: String = eventType,
    val amount: BigDecimal,
    val feeAmount: BigDecimal = BigDecimal.ZERO,
    val currency: String,
    /**
     * The merchant this callback says it is for, where the provider states one.
     *
     * Split from the payer because the two were one field called `clientId` and adapters
     * disagreed about which it meant: ClickPesa put the merchant's id in it, Snippe and
     * AzamPay put the guest's phone number. The domain checked it against the account's
     * merchant id, so a Snippe callback was rejected for the crime of naming its own payer.
     *
     * Null where the provider states no merchant. The check is skipped rather than failed —
     * the account was already resolved from the route, and this is corroboration, not the
     * primary control.
     */
    val merchantIdentity: String?,
    /** Who paid, as the provider reports them. Evidence; never an authorisation input. */
    val payerIdentity: String?,
    val providerTimestamp: Instant?,
    val checksumMethod: String?,
    val metadata: Map<String, Any?> = emptyMap(),
)
