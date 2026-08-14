package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.payment.api.ProviderPaymentStatus
import com.mwombeki.peak.payment.api.ProviderCollectionCommand
import java.math.BigDecimal
import java.net.URI
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

/**
 * Drives the adapter through a stub transport, so the outbound contract is pinned without
 * a network. The signature tests are the reason this file exists: they encode a decision
 * about what we refuse, which is not recoverable from reading the adapter alone.
 */
class AzamPayPaymentProviderTests {

    private val objectMapper = JsonMapper.builder().build()
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }
        .generateKeyPair()

    @Test
    fun initiateSendsTheCheckoutAzamPayExpectsAndReportsAPendingPush() {
        val transport = StubTransport(publicKeyPem = pem(keyPair))
        val provider = provider(transport)

        val result = provider.initiate(command(channel = "Mpesa"))

        assertEquals(ProviderPaymentStatus.PENDING, result.status)
        assertEquals("AZ-123", result.providerReference)
        assertEquals(null, result.redirectUrl, "a USSD push has nowhere to redirect to")

        val token = transport.calls.single { it.endpoint.path.contains("GenerateToken") }
        assertEquals("POST", token.method)

        val checkout = transport.calls.single { it.endpoint.path.contains("mno/checkout") }
        assertEquals("https", checkout.endpoint.scheme)
        assertEquals("Bearer token-abc", checkout.headers["Authorization"])
        assertEquals("api-key-value", checkout.headers["X-API-Key"])

        val body = objectMapper.readTree(requireNotNull(checkout.payload))
        assertEquals("255700000001", body.path("accountNumber").asString(""))
        assertEquals("Mpesa", body.path("provider").asString(""))
        assertEquals("TZS", body.path("currency").asString(""))
        assertEquals("PEAK-REF-1", body.path("externalId").asString(""))
    }

    @Test
    fun anUnsupportedNetworkIsRejectedBeforeAnyOutboundCall() {
        val transport = StubTransport(publicKeyPem = pem(keyPair))
        val provider = provider(transport)

        val failure = assertFailsWith<IllegalArgumentException> {
            provider.initiate(command(channel = "Vodacom"))
        }

        assertTrue(failure.message.orEmpty().contains("Vodacom"), failure.message.orEmpty())
        assertTrue(transport.calls.isEmpty(), "a bad channel must not reach the network")
    }

    @Test
    fun aMissingNetworkIsRejectedRatherThanGuessed() {
        val transport = StubTransport(publicKeyPem = pem(keyPair))
        val provider = provider(transport)

        assertFailsWith<IllegalArgumentException> { provider.initiate(command(channel = null)) }
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun aRefusalReportedInsideATwoHundredIsTreatedAsAFailure() {
        val transport = StubTransport(
            publicKeyPem = pem(keyPair),
            checkoutResponse = """{"success":false,"message":"insufficient balance"}""",
        )
        val provider = provider(transport)

        val failure = assertFailsWith<IllegalArgumentException> {
            provider.initiate(command(channel = "Airtel"))
        }
        assertTrue(
            failure.message.orEmpty().contains("insufficient balance"),
            failure.message.orEmpty(),
        )
    }

    @Test
    fun theBearerTokenIsMintedOnceAndReusedAcrossCollections() {
        val transport = StubTransport(publicKeyPem = pem(keyPair))
        val provider = provider(transport)

        provider.initiate(command(channel = "Tigo"))
        provider.initiate(command(channel = "Tigo"))

        assertEquals(
            1,
            transport.calls.count { it.endpoint.path.contains("GenerateToken") },
            "a token valid for hours must not be re-minted per collection",
        )
        assertEquals(2, transport.calls.count { it.endpoint.path.contains("mno/checkout") })
    }

    @Test
    fun aCallbackSignedOverAllFourFieldsVerifies() {
        val transport = StubTransport(publicKeyPem = pem(keyPair))
        val provider = provider(transport)
        val payload = callbackJson(
            signature = sign("UTIL-1" + "PEAK-REF-1" + "success" + "Mpesa"),
        )

        val notification = provider.verifyAndParseWebhook(payload, "unused", checksumRequired = true)

        assertEquals("PEAK-REF-1", notification.internalReference)
        assertEquals(ProviderPaymentStatus.SUCCEEDED, notification.status)
        assertEquals(BigDecimal("30000"), notification.amount)
        assertEquals("SHA256withRSA", notification.checksumMethod)
        assertEquals(true, notification.metadata["signatureVerified"])
    }

    /**
     * The load-bearing test. AzamPay's docs disagree on whether the signature covers two
     * fields or four; if we accepted the two-field form, anyone able to modify a callback
     * in flight could flip `transactionstatus` to success and keep a valid signature.
     */
    @Test
    fun aCallbackSignedOverOnlyTheReferencesIsRejected() {
        val transport = StubTransport(publicKeyPem = pem(keyPair))
        val provider = provider(transport)
        val payload = callbackJson(signature = sign("UTIL-1" + "PEAK-REF-1"))

        assertFailsWith<IllegalArgumentException> {
            provider.verifyAndParseWebhook(payload, "unused", checksumRequired = true)
        }
    }

    @Test
    fun aCallbackWhoseStatusWasAlteredAfterSigningIsRejected() {
        val transport = StubTransport(publicKeyPem = pem(keyPair))
        val provider = provider(transport)
        // Signed as a failure, delivered as a success.
        val payload = callbackJson(
            status = "success",
            signature = sign("UTIL-1" + "PEAK-REF-1" + "failure" + "Mpesa"),
        )

        assertFailsWith<IllegalArgumentException> {
            provider.verifyAndParseWebhook(payload, "unused", checksumRequired = true)
        }
    }

    @Test
    fun aCallbackSignedByAnotherKeyIsRejected() {
        val impostor = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val transport = StubTransport(publicKeyPem = pem(keyPair))
        val provider = provider(transport)
        val payload = callbackJson(
            signature = sign("UTIL-1PEAK-REF-1successMpesa", keyPair = impostor),
        )

        assertFailsWith<IllegalArgumentException> {
            provider.verifyAndParseWebhook(payload, "unused", checksumRequired = true)
        }
    }

    @Test
    fun aRotatedKeyIsRefetchedOnceRatherThanStrandingEveryCallback() {
        val rotated = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        // The cache holds the old key; the provider has already rotated to the new one.
        val transport = StubTransport(
            publicKeyPem = pem(keyPair),
            rotatedPublicKeyPem = pem(rotated),
        )
        val provider = provider(transport)
        val payload = callbackJson(
            signature = sign("UTIL-1PEAK-REF-1successMpesa", keyPair = rotated),
        )

        val notification = provider.verifyAndParseWebhook(payload, "unused", checksumRequired = true)

        assertEquals(ProviderPaymentStatus.SUCCEEDED, notification.status)
        assertEquals(
            2,
            transport.calls.count { it.endpoint.path.contains("PublicKey") },
            "exactly one refetch: the cached key, then the rotated one",
        )
    }

    /**
     * A callback is unauthenticated until its signature verifies, so it must not get to
     * say where the verifying key comes from. If it could, an attacker would point us at
     * their own key server and every forged callback would verify.
     */
    @Test
    fun theVerifyingKeyIsFetchedFromConfigurationNotFromAHostTheCallbackNames() {
        val impostor = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val transport = StubTransport(publicKeyPem = pem(keyPair))
        val provider = provider(transport)
        val payload = callbackJson(
            signature = sign("UTIL-1PEAK-REF-1successMpesa", keyPair = impostor),
            extra = ""","endpointUrl":"https://attacker.example.com"""",
        )

        assertFailsWith<IllegalArgumentException> {
            provider.verifyAndParseWebhook(payload, "unused", checksumRequired = true)
        }

        val keyHosts = transport.calls
            .filter { it.endpoint.path.contains("PublicKey") }
            .map { it.endpoint.host }
        assertTrue(keyHosts.isNotEmpty(), "the key should have been fetched at all")
        assertTrue(
            keyHosts.all { it == "authenticator-sandbox.azampay.co.tz" },
            "the verifying key must come only from the configured host, but went to $keyHosts",
        )
    }

    @Test
    fun anUnsignedCallbackIsRejectedWhenAChecksumIsRequired() {
        val transport = StubTransport(publicKeyPem = pem(keyPair))
        val provider = provider(transport)

        assertFailsWith<IllegalArgumentException> {
            provider.verifyAndParseWebhook(
                callbackJson(signature = null),
                "unused",
                checksumRequired = true,
            )
        }
    }

    @Test
    fun parsingWithoutVerifyingNeverClaimsTheCallbackWasVerified() {
        val transport = StubTransport(publicKeyPem = pem(keyPair))
        val provider = provider(transport)

        val notification = provider.parseWebhook(callbackJson(signature = sign("anything")))

        assertEquals(null, notification.checksumMethod)
        assertFalse(notification.metadata["signatureVerified"] as Boolean)
    }

    private fun provider(transport: StubTransport): AzamPayPaymentProvider {
        val properties = AzamPayProperties(
            authenticatorUrl = "https://authenticator-sandbox.azampay.co.tz",
            paymentsUrl = "https://sandbox.azampay.co.tz",
            appName = "Peak",
        )
        val clock = Clock.fixed(Instant.parse("2026-08-13T09:00:00Z"), ZoneOffset.UTC)
        return AzamPayPaymentProvider(
            transport = transport,
            tokenProvider = AzamPayTokenProvider(transport, objectMapper, properties, clock),
            publicKeyProvider = AzamPayPublicKeyProvider(transport),
            signature = AzamPaySignature(),
            objectMapper = objectMapper,
            properties = properties,
        )
    }

    private fun command(channel: String?) = ProviderCollectionCommand(
        transactionId = UUID.randomUUID(),
        internalReference = "PEAK-REF-1",
        endpointUrl = "https://sandbox.azampay.co.tz",
        clientId = "client-id-value",
        payerIdentifier = "255700000001",
        amount = BigDecimal("30000.00"),
        currency = "TZS",
        apiKey = "api-key-value",
        checksumKey = "client-secret-value",
        providerChannel = channel,
    )

    private fun callbackJson(
        status: String = "success",
        signature: String? = null,
        extra: String = "",
    ): String {
        val signatureField = signature?.let { ""","signature":"$it"""" } ?: ""
        return """
            {"utilityref":"UTIL-1","externalreference":"PEAK-REF-1",
             "transactionstatus":"$status","operator":"Mpesa",
             "transactionid":"AZ-123","amount":"30000","currency":"TZS",
             "msisdn":"255700000001","time":"2026-08-13T09:05:00Z"$signatureField$extra}
        """.trimIndent()
    }

    private fun sign(message: String, keyPair: KeyPair = this.keyPair): String {
        val bytes = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(message.toByteArray(Charsets.UTF_8))
        }.sign()
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun pem(keyPair: KeyPair): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(keyPair.public.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----"
    }

    private data class RecordedCall(
        val method: String,
        val endpoint: URI,
        val headers: Map<String, String>,
        val payload: String?,
    )

    private class StubTransport(
        private val publicKeyPem: String,
        private val rotatedPublicKeyPem: String? = null,
        private val checkoutResponse: String =
            """{"success":true,"transactionId":"AZ-123","message":"pending"}""",
    ) : AzamPayHttpTransport {
        val calls = mutableListOf<RecordedCall>()
        private var publicKeyFetches = 0

        override fun exchange(
            method: String,
            endpoint: URI,
            headers: Map<String, String>,
            payload: String?,
        ): String {
            calls += RecordedCall(method, endpoint, headers, payload)
            return when {
                endpoint.path.contains("GenerateToken") ->
                    """{"success":true,"data":{"accessToken":"token-abc",
                       "expire":"2026-08-13T18:00:00Z"}}"""

                endpoint.path.contains("PublicKey") -> {
                    publicKeyFetches += 1
                    // The first fetch serves the cached-and-now-stale key; a refetch serves
                    // the rotated one, when the test is exercising rotation.
                    if (publicKeyFetches > 1 && rotatedPublicKeyPem != null) {
                        rotatedPublicKeyPem
                    } else {
                        publicKeyPem
                    }
                }

                endpoint.path.contains("mno/checkout") -> checkoutResponse

                else -> error("Unexpected AzamPay call to $endpoint")
            }
        }
    }
}
