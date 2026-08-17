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
import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Collects money into Peak's own AzamPay merchant account.
 *
 * ## Credential mapping
 *
 * The SPI carries three credential slots, which AzamPay's four inputs map onto as:
 *
 * | SPI field     | AzamPay                                          |
 * |---------------|--------------------------------------------------|
 * | `clientId`    | clientId                                         |
 * | `apiKey`      | the `X-API-Key` header on payments calls         |
 * | `checksumKey` | clientSecret, used only to mint a bearer token   |
 *
 * `appName` is the fourth and comes from [AzamPayProperties] rather than the command,
 * because this adapter serves exactly one merchant. See the note there.
 *
 * ## What this does not do
 *
 * `mno/checkout` has no destination field — `accountNumber` is the payer, and funds land in
 * the account the credentials belong to. There is therefore no way to route a guest's
 * payment to a property from here. That is why guest payments stay on the property's own
 * rails and this adapter is used only for Peak's subscription revenue.
 */
@Component
class AzamPayPaymentProvider(
    private val transport: AzamPayHttpTransport,
    private val tokenProvider: AzamPayTokenProvider,
    private val publicKeyProvider: AzamPayPublicKeyProvider,
    private val signature: AzamPaySignature,
    private val objectMapper: ObjectMapper,
    private val properties: AzamPayProperties,
) : StatusQueryablePaymentProvider {

    override val providerCode = "azampay"

    /** mno/checkout has a required `provider` field and will not infer it from the MSISDN. */
    override val requiresMobileNetwork = true

    override fun initiate(command: ProviderCollectionCommand): ProviderCollectionResult {
        val channel = requireSupportedChannel(command.providerChannel)
        val endpoint = azamPayEndpoint(command.endpointUrl, CHECKOUT_PATH)
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "accountNumber" to command.payerIdentifier,
                "amount" to command.amount.toPlainString(),
                "currency" to command.currency.uppercase(),
                "externalId" to command.internalReference,
                "provider" to channel,
            ),
        )

        val response = transport.exchange(
            method = "POST",
            endpoint = endpoint,
            headers = paymentHeaders(command),
            payload = payload,
        )
        val node = objectMapper.readTree(response)

        // AzamPay reports refusal in the body with a 200, so an unchecked `success` would
        // leave a purchase waiting on a push that was never sent.
        val success = node.path("success").asBoolean(false)
        require(success) {
            "AzamPay refused the collection: " +
                node.path("message").asString("no reason given")
        }

        return ProviderCollectionResult(
            providerReference = node.path("transactionId").asString("").trim()
                .takeIf { it.isNotEmpty() }
                ?: command.internalReference,
            status = ProviderPaymentStatus.PENDING,
            providerStatus = node.path("message").asString("pending"),
            // A USSD push, so there is nowhere to send the payer; they answer on the handset.
            redirectUrl = null,
        )
    }


    /**
     * Asks AzamPay what happened, so a lost callback is recoverable.
     *
     * This is a release gate, not a convenience. Mobile money callbacks go missing routinely,
     * and without a way to ask, a customer whose account was debited is told the payment did
     * not happen. `peak_payment_method_capabilities` had claimed AzamPay supported this and
     * the rail was enabled on that claim while this method did not exist; the default
     * implementation threw, the reconciler recorded "unknown", and the payment sat waiting for
     * a human who was never told to look.
     *
     * Keyed on `transactionId` from the initiation response, which is why `initiate` keeps it
     * as the provider reference. Peak's own `externalId` is not accepted here, so a status
     * query for an attempt that has no provider reference cannot be answered — that is a
     * payment we never confirmed was accepted, and it is reported as unknown rather than
     * guessed at.
     */
    override fun queryStatus(command: ProviderStatusQuery): ProviderStatusResult {
        val providerReference = command.providerReference?.trim().orEmpty()
        require(providerReference.isNotEmpty()) {
            "AzamPay keys its status endpoint on the transactionId returned at initiation, " +
                "so an attempt without one cannot be asked about"
        }

        val endpoint = azamPayEndpoint(
            command.endpointUrl,
            "$STATUS_PATH?transactionId=" +
                URLEncoder.encode(providerReference, StandardCharsets.UTF_8) +
                "&provider=" +
                URLEncoder.encode(command.collectionFlow ?: DEFAULT_STATUS_CHANNEL,
                    StandardCharsets.UTF_8),
        )

        val node = objectMapper.readTree(
            transport.exchange(
                method = "GET",
                endpoint = endpoint,
                headers = statusHeaders(command),
                payload = null,
            ),
        )

        val providerStatus = node.path("transactionstatus").asString("")
            .ifEmpty { node.path("data").path("transactionstatus").asString("") }

        return ProviderStatusResult(
            internalReference = node.path("externalreference").asString(
                command.internalReference,
            ),
            providerReference = node.path("transactionid").asString(providerReference),
            status = providerStatus.normalizedStatus(),
            providerStatus = providerStatus,
            amount = node.path("amount").asString(null)?.toBigDecimalOrZero(),
            currency = node.path("currency").asString(null)?.uppercase(),
            clientId = command.clientId,
            providerTimestamp = node.path("time").asString(null)?.let(::parseInstantOrNull),
        )
    }

    private fun statusHeaders(command: ProviderStatusQuery): Map<String, String> {
        val token = tokenProvider.token(
            clientId = command.clientId,
            clientSecret = command.checksumKey,
            // The property's own registration where it has one. Null falls back to Peak's,
            // which is right for subscription collection and wrong for a hotel's own account —
            // hence the column rather than a shared default.
            appName = command.providerAppName,
        )
        return mapOf(
            "Authorization" to "Bearer $token",
            "X-API-Key" to command.apiKey,
        )
    }

    /**
     * Parses without verifying, for the rare caller that only needs the shape. Settlement
     * must use [verifyAndParseWebhook]; an unverified AzamPay callback is an assertion by
     * an anonymous HTTP client that it has paid us.
     */
    override fun parseWebhook(payload: String): ProviderWebhookNotification {
        return toNotification(objectMapper.readTree(payload), verified = false)
    }

    override fun verifyAndParseWebhook(
        payload: String,
        checksumKey: String,
        checksumRequired: Boolean,
    ): ProviderWebhookNotification {
        val node = objectMapper.readTree(payload)
        val provided = node.path("signature").asString("").trim()

        if (provided.isEmpty()) {
            require(!checksumRequired) { "AzamPay callback carried no signature" }
            return toNotification(node, verified = false)
        }

        // checksumKey is unused: AzamPay signs with its own private key, so the material
        // that proves a callback is a public key fetched from them, not a shared secret.
        // The host comes from configuration and never from the payload — a callback is
        // unauthenticated until it verifies, so letting it name the key server would let
        // an attacker present their own key and sign whatever they liked.
        //
        // The authenticator host, not the payments host: /api/Token/PublicKey lives there,
        // and every public-key path on the payments host answers 404.
        val baseUrl = properties.authenticatorUrl
        val message = signature.signedMessage(
            utilityReference = node.path("utilityref").asString(""),
            externalReference = node.path("externalreference").asString(""),
            transactionStatus = node.path("transactionstatus").asString(""),
            operator = node.path("operator").asString(""),
        )

        val verified = signature.verify(publicKeyProvider.publicKey(baseUrl), message, provided) ||
            // One retry against a freshly fetched key: a rotated key is likelier than an
            // attack, and a rotation must not strand every callback until a redeploy.
            signature.verify(publicKeyProvider.refresh(baseUrl), message, provided)

        require(verified || !checksumRequired) {
            "AzamPay callback signature did not verify"
        }
        return toNotification(node, verified = verified)
    }

    private fun toNotification(node: JsonNode, verified: Boolean): ProviderWebhookNotification {
        val externalReference = node.path("externalreference").asString("").trim()
        require(externalReference.isNotEmpty()) {
            "AzamPay callback did not carry an external reference"
        }
        val providerReference = node.path("transactionid").asString("").trim()
            .takeIf { it.isNotEmpty() }
            ?: node.path("utilityref").asString(externalReference)

        return ProviderWebhookNotification(
            // AzamPay does not send a dedicated event id, so the transaction reference is
            // the strongest replay key available. The storage-layer unique index on
            // (provider, provider_event_id) is what actually makes replay a no-op.
            eventKey = providerReference,
            eventType = "collection.updated",
            internalReference = externalReference,
            providerReference = providerReference,
            status = node.path("transactionstatus").asString("").normalizedStatus(),
            providerStatus = node.path("transactionstatus").asString("collection.updated"),
            amount = node.path("amount").asString("0").toBigDecimalOrZero(),
            currency = node.path("currency").asString("TZS").uppercase(),
            // As with Snippe, msisdn is the payer's handset and says nothing about which
            // merchant the money is for.
            merchantIdentity = null,
            payerIdentity = node.path("msisdn").asString(null),
            providerTimestamp = node.path("time").asString(null)?.let(::parseInstantOrNull),
            checksumMethod = if (verified) "SHA256withRSA" else null,
            metadata = mapOf(
                "operator" to node.path("operator").asString(null),
                "signatureVerified" to verified,
            ),
        )
    }

    private fun paymentHeaders(command: ProviderCollectionCommand): Map<String, String> {
        val token = tokenProvider.token(
            clientId = command.clientId,
            clientSecret = command.checksumKey,
            // The property's own registration where it has one. Null falls back to Peak's,
            // which is right for subscription collection and wrong for a hotel's own account —
            // hence the column rather than a shared default.
            appName = command.providerAppName,
        )
        return mapOf(
            "Authorization" to "Bearer $token",
            "X-API-Key" to command.apiKey,
        )
    }

    private fun requireSupportedChannel(channel: String?): String {
        val requested = channel?.trim().orEmpty()
        require(requested.isNotEmpty()) {
            "AzamPay requires a mobile network; one of ${SUPPORTED_CHANNELS.joinToString(", ")}"
        }
        return SUPPORTED_CHANNELS.firstOrNull { it.equals(requested, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "AzamPay does not support the $requested network; " +
                    "expected one of ${SUPPORTED_CHANNELS.joinToString(", ")}",
            )
    }

    private fun String.normalizedStatus(): ProviderPaymentStatus {
        return when (trim().lowercase()) {
            "success", "successful", "completed" -> ProviderPaymentStatus.SUCCEEDED
            "failure", "failed", "rejected" -> ProviderPaymentStatus.FAILED
            "cancelled", "canceled" -> ProviderPaymentStatus.CANCELLED
            "pending", "processing" -> ProviderPaymentStatus.PENDING
            // An absent status is not a pending payment. AzamPay omitting the field means
            // Peak has been told nothing, and the status query must still run.
            else -> ProviderPaymentStatus.UNKNOWN
        }
    }

    private fun String.toBigDecimalOrZero(): BigDecimal =
        runCatching { BigDecimal(trim()) }.getOrDefault(BigDecimal.ZERO)

    private fun parseInstantOrNull(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()

    internal companion object {
        const val CHECKOUT_PATH = "/azampay/mno/checkout"
        const val STATUS_PATH = "/api/v1/partner/gettransactionstatus"

        /**
         * AzamPay's status endpoint takes the network as `provider`. The collection flow
         * carries it where the caller knows it; where it does not, Mpesa is the largest
         * network in Tanzania and the wrong guess costs a re-query rather than a wrong answer.
         */
        const val DEFAULT_STATUS_CHANNEL = "Mpesa"

        /**
         * AzamPay's accepted `provider` values. Mpesa is present: an earlier read of their
         * marketing page suggested otherwise, but the Go, Dart, Laravel and Python SDKs and
         * the published OpenAPI schema all accept it.
         */
        val SUPPORTED_CHANNELS = listOf("Airtel", "Tigo", "Halopesa", "Azampesa", "Mpesa")
    }
}
