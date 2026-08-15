package com.mwombeki.peak.integrations.internal

// Provider-owned HTTP gateway implementation lives outside the payment domain.
import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.ProviderCollectionCommand
import com.mwombeki.peak.payment.api.ProviderCollectionResult
import com.mwombeki.peak.payment.api.ProviderPaymentStatus
import com.mwombeki.peak.payment.api.ProviderStatusQuery
import com.mwombeki.peak.payment.api.ProviderStatusResult
import com.mwombeki.peak.payment.api.ProviderWebhookNotification
import com.mwombeki.peak.payment.api.StatusQueryablePaymentProvider
import com.mwombeki.peak.shared.outbound.BoundedJsonHttpClient
import com.mwombeki.peak.shared.outbound.OutboundEndpointPolicy
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@ConfigurationProperties(prefix = "peak.payment.providers.snippe")
data class SnippeProperties(
    /** Base URL, e.g. https://api.snippe.sh. */
    val baseUrl: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val requestTimeout: Duration = Duration.ofSeconds(20),
    /**
     * How far out of date a webhook timestamp may be.
     *
     * Snippe recommends five minutes. Without this the signature proves authenticity but not
     * freshness, so a captured callback could be replayed indefinitely — and while the
     * provider-event ledger would refuse to apply it twice, refusing early is cheaper and
     * does not depend on the ledger being reachable.
     */
    val webhookTolerance: Duration = Duration.ofMinutes(5),
) {
    init {
        require(!webhookTolerance.isNegative && !webhookTolerance.isZero) {
            "peak.payment.providers.snippe.webhook-tolerance must be positive"
        }
    }
}

fun interface SnippeHttpTransport {
    fun exchange(
        method: String,
        endpoint: URI,
        headers: Map<String, String>,
        payload: String?,
    ): String
}

@Component
class JdkSnippeHttpTransport(
    private val properties: SnippeProperties,
    outboundEndpointPolicy: OutboundEndpointPolicy,
) : SnippeHttpTransport {
    private val client = BoundedJsonHttpClient(
        endpointPolicy = outboundEndpointPolicy,
        connectTimeout = properties.connectTimeout,
        providerLabel = "Snippe",
    )

    override fun exchange(
        method: String,
        endpoint: URI,
        headers: Map<String, String>,
        payload: String?,
    ): String = client.exchange(
        method = method,
        endpoint = endpoint,
        requestTimeout = properties.requestTimeout,
        headers = headers,
        payload = payload,
    )
}

/**
 * Collects through Snippe's hosted checkout.
 *
 * Chosen over AzamPay's USSD push for the cases where a push cannot go: an amount above the
 * mobile money ceiling, or a payer who would rather use a card or a bank. `initiate` returns a
 * `redirectUrl` rather than pushing a prompt, which is why
 * [ProviderCollectionResult.redirectUrl] exists.
 *
 * ## Why this contract could be implemented and AzamPay's callback could not
 *
 * Snippe documents one header, one algorithm and one signed message, and says explicitly to
 * verify against the raw body. AzamPay's own pages contradict each other about which fields
 * their RSA signature covers, and the contract appears in no machine-readable spec. The
 * difference is not effort; it is whether there is a single answer to implement.
 *
 * ## Amounts
 *
 * Snippe describes amounts as "Integer (smallest unit)". For TZS the smallest circulating unit
 * *is* the shilling, and the magnitudes agree — a documented minimum of 500 is a sensible floor
 * in shillings and an absurd one in hundredths. So this treats the value as whole shillings.
 *
 * If that is wrong, the failure is safe rather than silent: settlement already refuses a
 * callback whose amount disagrees with the attempt, so a unit mismatch rejects every payment
 * loudly instead of settling each at a hundredth of its value.
 */
@Component
class SnippePaymentProvider(
    private val transport: SnippeHttpTransport,
    private val objectMapper: ObjectMapper,
    private val properties: SnippeProperties,
    private val clock: Clock,
) : StatusQueryablePaymentProvider {

    override val providerCode = "snippe"

    override val guestCollectionFlow = DIRECT_PUSH

    override fun initiate(command: ProviderCollectionCommand): ProviderCollectionResult =
        when (command.collectionFlow?.trim()?.lowercase()) {
            DIRECT_PUSH -> pushToHandset(command)
            HOSTED_CHECKOUT, null -> openHostedCheckout(command)
            else -> throw IllegalArgumentException(
                "Snippe does not offer a ${command.collectionFlow} collection flow",
            )
        }

    /**
     * The direct rail: Peak supplies the number and Snippe pushes the prompt to it.
     *
     * This is what Peak's own subscription UX means by "click Pay" — the owner answers on
     * their handset rather than being sent to a page. Different endpoint, different path
     * prefix (`/v1`, not `/api/v1`) and a different request shape from a session.
     */
    private fun pushToHandset(command: ProviderCollectionCommand): ProviderCollectionResult {
        val endpoint = snippeEndpoint(command.endpointUrl.orElse(properties.baseUrl), PAYMENTS_PATH)
        val payer = requirePayerIdentity(command)

        val body = mapOf(
            "payment_type" to "mobile",
            "details" to mapOf(
                "amount" to command.amount.toWholeUnits(),
                "currency" to command.currency.uppercase(),
            ),
            "phone_number" to internationalMsisdn(command.payerIdentifier),
            "customer" to mapOf(
                "firstname" to payer.firstName,
                "lastname" to payer.lastName,
                "email" to payer.email,
            ),
            // snippe-integration skill: create has no external_reference field.
            // Peak's handle rides in metadata, which webhooks and GET echo back.
            // data.external_reference on the webhook is Selcom's, not ours.
            "metadata" to mapOf("external_reference" to command.internalReference),
        )

        val response = transport.exchange(
            method = "POST",
            endpoint = endpoint,
            headers = mapOf(
                "Authorization" to "Bearer ${command.apiKey}",
                "Idempotency-Key" to idempotencyKey(command.internalReference),
            ),
            payload = objectMapper.writeValueAsString(body),
        )

        val node = objectMapper.readTree(response).unwrapData()
        val reference = node.path("reference").asString("").trim()
        require(reference.isNotEmpty()) {
            "Snippe payment response carried no reference, so the payment could never be " +
                "reconciled if its callback were lost"
        }

        return ProviderCollectionResult(
            providerReference = reference,
            status = node.path("status").asString("pending").normalizedStatus(),
            providerStatus = node.path("status").asString("pending"),
            // A push, so there is nowhere to send the payer; they answer on the handset.
            redirectUrl = null,
        )
    }

    private fun openHostedCheckout(command: ProviderCollectionCommand): ProviderCollectionResult {
        val endpoint = snippeEndpoint(command.endpointUrl.orElse(properties.baseUrl), SESSIONS_PATH)
        val body = buildMap<String, Any> {
            put("amount", command.amount.toWholeUnits())
            put("currency", command.currency.uppercase())
            // Sessions 2026-01-25 has no external_reference field. Peak's reference
            // rides in metadata, which the docs say is echoed on the webhook.
            put("metadata", mapOf("external_reference" to command.internalReference))
            put("allowed_methods", listOf("mobile_money"))
            if (command.payerIdentifier.isNotBlank()) {
                put("customer", mapOf("phone" to command.payerIdentifier.trim()))
            }
        }

        val response = transport.exchange(
            method = "POST",
            endpoint = endpoint,
            headers = mapOf(
                "Authorization" to "Bearer ${command.apiKey}",
                // Snippe honours this, so a retried initiation cannot create a second session
                // for one purchase. Keys longer than 30 characters return PAY_001.
                "Idempotency-Key" to idempotencyKey(command.internalReference),
            ),
            payload = objectMapper.writeValueAsString(body),
        )

        val node = objectMapper.readTree(response).unwrapData()
        val reference = node.path("reference").asString("").trim()
        require(reference.isNotEmpty()) {
            "Snippe session response carried no reference, so the payment could never be " +
                "reconciled if its callback were lost"
        }

        return ProviderCollectionResult(
            providerReference = reference,
            status = node.path("status").asString("pending").normalizedStatus(),
            providerStatus = node.path("status").asString("pending"),
            redirectUrl = node.firstNonBlank("checkout_url", "url", "checkoutUrl"),
        )
    }

    /**
     * Keyed on the reference returned at initiation, which is what makes a lost callback
     * recoverable on this rail.
     */
    override fun queryStatus(command: ProviderStatusQuery): ProviderStatusResult {
        // Snippe's status endpoints are keyed on the reference it issued, not on ours.
        val reference = command.providerReference?.trim()?.takeIf { it.isNotEmpty() }
            ?: command.internalReference.trim()
        // Each flow has its own status endpoint. Inferring which from the shape of the
        // reference — sessions are prefixed, payments are bare UUIDs — would work today and
        // break silently the day a prefix changes.
        val basePath = when (command.collectionFlow?.trim()?.lowercase()) {
            DIRECT_PUSH -> PAYMENTS_PATH
            else -> SESSIONS_PATH
        }
        val endpoint = snippeEndpoint(
            command.endpointUrl.orElse(properties.baseUrl),
            "$basePath/$reference",
        )
        val response = transport.exchange(
            method = "GET",
            endpoint = endpoint,
            headers = mapOf("Authorization" to "Bearer ${command.apiKey}"),
            payload = null,
        )
        val node = objectMapper.readTree(response).unwrapData()
        val providerStatus = node.path("status").asString("")
        val peakReference = node.path("metadata").path("external_reference").asString("").trim()
            .ifEmpty { command.internalReference.trim() }

        return ProviderStatusResult(
            internalReference = peakReference,
            providerReference = node.path("reference").asString(reference),
            status = providerStatus.normalizedStatus(),
            providerStatus = providerStatus,
            amount = node.path("amount").path("value").asString(null)?.toWholeAmount()
                ?: node.path("amount").asString(null)?.toWholeAmount(),
            currency = node.path("amount").path("currency").asString("TZS")
                .ifBlank { node.path("currency").asString("TZS") }
                .uppercase(),
            clientId = null,
            providerTimestamp = node.path("completed_at").asString(null)?.let(::parseInstantOrNull),
        )
    }

    override fun parseWebhook(payload: String): ProviderWebhookNotification =
        toNotification(objectMapper.readTree(payload), verified = false)

    override fun verifyAndParseWebhook(
        payload: String,
        checksumKey: String,
        checksumRequired: Boolean,
        headers: Map<String, String>,
    ): ProviderWebhookNotification {
        val lookup = headers.mapKeys { it.key.lowercase() }
        val signature = lookup[SIGNATURE_HEADER]?.trim().orEmpty()
        val timestamp = lookup[TIMESTAMP_HEADER]?.trim().orEmpty()

        if (signature.isEmpty()) {
            require(!checksumRequired) { "Snippe callback carried no $SIGNATURE_HEADER header" }
            return toNotification(objectMapper.readTree(payload), verified = false)
        }
        require(timestamp.isNotEmpty()) {
            "Snippe callback carried a signature but no $TIMESTAMP_HEADER, so the signed " +
                "message cannot be reconstructed"
        }

        // Freshness before authenticity: a valid signature on a captured callback is still a
        // replay, and rejecting it here does not depend on the event ledger being reachable.
        require(withinTolerance(timestamp)) {
            "Snippe callback timestamp is outside the " +
                "${properties.webhookTolerance.toMinutes()} minute replay window"
        }

        // The raw body exactly as received. Parsing and re-serialising would change whitespace
        // or key order and break every verification — Snippe's documentation calls this out,
        // and it is the usual way HMAC webhook verification is got wrong.
        val expected = hmacSha256Hex(checksumKey, "$timestamp.$payload")
        val verified = constantTimeEquals(expected, signature)

        require(verified || !checksumRequired) { "Snippe callback signature did not verify" }
        return toNotification(objectMapper.readTree(payload), verified = verified)
    }

    private fun toNotification(root: JsonNode, verified: Boolean): ProviderWebhookNotification {
        val data = root.path("data")
        // snippe-integration skill: data.external_reference is the upstream processor
        // (Selcom) reference. Peak's own handle is whatever we put in metadata on create.
        val peakReference = data.path("metadata").path("external_reference").asString("").trim()
        require(peakReference.isNotEmpty()) {
            "Snippe callback carried no Peak reference in metadata, so it cannot be " +
                "matched to a payment Peak started"
        }

        val amount = data.path("amount")
        val settlement = data.path("settlement")

        return ProviderWebhookNotification(
            // A real event id, unlike AzamPay. The unique index on
            // (provider, provider_event_id) turns a redelivery into a no-op.
            eventKey = root.path("id").asString(peakReference),
            eventType = root.path("type").asString("payment.updated"),
            internalReference = peakReference,
            providerReference = data.path("reference").asString(peakReference),
            status = root.path("type").asString("").eventStatus(),
            amount = amount.path("value").asString("0").toWholeAmount(),
            feeAmount = settlement.path("fees").path("value").asString("0").toWholeAmount(),
            currency = amount.path("currency").asString("TZS").uppercase(),
            merchantIdentity = null,
            payerIdentity = data.path("customer").path("phone").asString(null),
            providerTimestamp = data.path("completed_at").asString(null)
                ?.let(::parseInstantOrNull)
                ?: root.path("created_at").asString(null)?.let(::parseInstantOrNull),
            checksumMethod = if (verified) "HMAC-SHA256" else null,
            metadata = mapOf(
                "channel" to data.path("channel").path("provider").asString(null),
                "signatureVerified" to verified,
            ),
        )
    }

    private fun withinTolerance(timestamp: String): Boolean {
        val seconds = timestamp.toLongOrNull() ?: return false
        val age = Duration.between(Instant.ofEpochSecond(seconds), clock.instant()).abs()
        return age <= properties.webhookTolerance
    }

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Length-independent and content-independent in time, so a signature cannot be guessed. */
    private fun constantTimeEquals(expected: String, provided: String): Boolean {
        val a = expected.lowercase().toByteArray(Charsets.UTF_8)
        val b = provided.lowercase().toByteArray(Charsets.UTF_8)
        var difference = a.size xor b.size
        for (i in a.indices) {
            difference = difference or (a[i].toInt() xor b.getOrElse(i) { 0 }.toInt())
        }
        return difference == 0
    }

    /**
     * `payment.completed` is the only event that means paid. `voided` and `expired` are
     * failures the customer may retry; anything unrecognised is left for the reconciler to
     * treat as unknown rather than guessed at here.
     */
    /**
     * Snippe reports the outcome in the event name rather than in a status field, so the
     * event type is what carries meaning here — but only inside this adapter. The domain used
     * to read event names directly, which is exactly why every Snippe callback was rejected.
     */
    private fun String.eventStatus(): ProviderPaymentStatus = when (trim().lowercase()) {
        "payment.completed" -> ProviderPaymentStatus.SUCCEEDED
        "payment.failed", "payment.expired" -> ProviderPaymentStatus.FAILED
        "payment.voided" -> ProviderPaymentStatus.CANCELLED
        "payment.pending", "payment.processing", "payment.created" ->
            ProviderPaymentStatus.PENDING
        // An event Peak has no mapping for is not a failed payment. Snippe adding an event
        // type must not make Peak declare a guest's payment dead.
        else -> ProviderPaymentStatus.UNKNOWN
    }

    private fun String.normalizedStatus(): ProviderPaymentStatus = when (trim().lowercase()) {
        "completed", "succeeded", "success" -> ProviderPaymentStatus.SUCCEEDED
        "failed", "expired" -> ProviderPaymentStatus.FAILED
        "cancelled", "canceled" -> ProviderPaymentStatus.CANCELLED
        "pending", "active", "processing" -> ProviderPaymentStatus.PENDING
        else -> ProviderPaymentStatus.UNKNOWN
    }

    /** TZS carries no circulating subunit, so the integer is already whole shillings. */
    private fun BigDecimal.toWholeUnits(): Long = toBigInteger().toLong()

    private fun String.toWholeAmount(): BigDecimal =
        runCatching { BigDecimal(trim()) }.getOrDefault(BigDecimal.ZERO)

    private fun String?.orElse(fallback: String): String =
        this?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    /**
     * Mobile-money create wants `255XXXXXXXXX` with no leading `+`.
     * `docs.snippe.sh/docs/2026-01-25/payments/mobile-money`.
     */
    private fun internationalMsisdn(value: String): String =
        value.trim().removePrefix("+").filter(Char::isDigit)

    /**
     * `POST /v1/payments` rejects Idempotency-Key values longer than 30 characters
     * with `500 PAY_001`. Peak's own reference is already shorter; this keeps a
     * longer caller from blowing up every retry.
     */
    private fun idempotencyKey(value: String): String = value.trim().take(MAX_IDEMPOTENCY_KEY_LENGTH)

    /** Snippe wraps some responses in `data` and returns others bare. Both are accepted. */
    private fun JsonNode.unwrapData(): JsonNode =
        if (path("data").isObject) path("data") else this

    private fun JsonNode.firstNonBlank(vararg fields: String): String? =
        fields.firstNotNullOfOrNull { path(it).asString(null)?.takeIf { v -> v.isNotBlank() } }

    private fun parseInstantOrNull(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()

    /**
     * Snippe requires a name and email on a direct payment. Peak knows the tenant user who
     * pressed Pay, so this is a wiring requirement rather than something to ask the customer
     * for — but failing here beats sending placeholders into a payment record.
     */
    private fun requirePayerIdentity(command: ProviderCollectionCommand): PayerIdentity {
        val email = command.payerEmail?.trim()?.takeIf { it.isNotEmpty() }
        requireNotNull(email) {
            "Snippe requires the payer's email on a direct mobile money payment"
        }
        val name = command.payerName?.trim()?.takeIf { it.isNotEmpty() }
        requireNotNull(name) {
            "Snippe requires the payer's name on a direct mobile money payment"
        }
        val parts = name.split(Regex("\\s+"), limit = 2)
        return PayerIdentity(
            firstName = parts.first(),
            // Snippe wants both halves. A single-word name is normal here, so it stands in
            // for the surname rather than being rejected or padded with something invented.
            lastName = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: parts.first(),
            email = email,
        )
    }

    private data class PayerIdentity(
        val firstName: String,
        val lastName: String,
        val email: String,
    )

    internal companion object {
        const val SESSIONS_PATH = "/api/v1/sessions"
        /** Note the prefix: /v1, not /api/v1. Snippe's two collection APIs differ in it. */
        const val PAYMENTS_PATH = "/v1/payments"
        const val DIRECT_PUSH = "direct_push"
        const val HOSTED_CHECKOUT = "hosted_checkout"
        const val SIGNATURE_HEADER = "x-webhook-signature"
        const val TIMESTAMP_HEADER = "x-webhook-timestamp"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 30
    }
}

internal fun snippeEndpoint(baseUrl: String?, path: String): URI {
    val trimmed = baseUrl?.trim().orEmpty().trimEnd('/')
    require(trimmed.isNotEmpty()) { "Snippe base URL is required" }
    val uri = URI.create(trimmed + path)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
        "Snippe endpoint must be an absolute HTTPS URL"
    }
    return uri
}
