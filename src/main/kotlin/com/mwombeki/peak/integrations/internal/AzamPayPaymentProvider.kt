package com.mwombeki.peak.integrations.internal

// Provider-owned HTTP gateway implementation lives outside the payment domain.
import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.ProviderCollectionCommand
import com.mwombeki.peak.payment.api.ProviderCollectionResult
import com.mwombeki.peak.payment.api.ProviderWebhookNotification
import java.math.BigDecimal
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
) : PaymentProvider {

    override val providerCode = "azampay"

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
            status = "pending",
            providerStatus = node.path("message").asString("pending"),
            // A USSD push, so there is nowhere to send the payer; they answer on the handset.
            redirectUrl = null,
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
        val baseUrl = properties.paymentsUrl
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
            amount = node.path("amount").asString("0").toBigDecimalOrZero(),
            currency = node.path("currency").asString("TZS").uppercase(),
            clientId = node.path("msisdn").asString(null),
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

    private fun String.normalizedStatus(): String {
        return when (trim().lowercase()) {
            "success", "successful", "completed" -> "succeeded"
            "failure", "failed", "rejected" -> "failed"
            "" -> "pending"
            else -> trim().lowercase()
        }
    }

    private fun String.toBigDecimalOrZero(): BigDecimal =
        runCatching { BigDecimal(trim()) }.getOrDefault(BigDecimal.ZERO)

    private fun parseInstantOrNull(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()

    internal companion object {
        const val CHECKOUT_PATH = "/azampay/mno/checkout"

        /**
         * AzamPay's accepted `provider` values. Mpesa is present: an earlier read of their
         * marketing page suggested otherwise, but the Go, Dart, Laravel and Python SDKs and
         * the published OpenAPI schema all accept it.
         */
        val SUPPORTED_CHANNELS = listOf("Airtel", "Tigo", "Halopesa", "Azampesa", "Mpesa")
    }
}
