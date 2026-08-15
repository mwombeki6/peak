package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.payment.api.ProviderPaymentStatus
import com.mwombeki.peak.payment.api.ProviderCollectionCommand
import com.mwombeki.peak.payment.api.ProviderStatusQuery
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

/**
 * Drives the Snippe adapter through a stub transport.
 *
 * The signature tests carry the weight. Snippe's scheme is HMAC over
 * `{timestamp}.{raw_body}`, and the usual way that is got wrong is to parse the JSON and
 * re-serialise it before verifying — which changes whitespace or key order and breaks every
 * callback. Their documentation says so explicitly, and one of these tests reproduces exactly
 * that mistake to prove the adapter does not make it.
 */
class SnippePaymentProviderTests {

    private val objectMapper = JsonMapper.builder().build()
    private val now: Instant = Instant.parse("2026-08-13T09:00:00Z")
    private val secret = "whsec_0123456789abcdef"

    @Test
    fun guestCollectionUsesThePaymentsApiNotAHostedSession() {
        assertEquals(
            "direct_push",
            provider(StubTransport()).guestCollectionFlow,
            "headless POS and folio collection is POST /v1/payments, not a checkout session",
        )
    }

    @Test
    fun initiateOpensAHostedCheckoutAndReturnsSomewhereToSendThePayer() {
        val transport = StubTransport()
        val result = provider(transport).initiate(command())

        assertEquals("sess_abc123def456", result.providerReference)
        assertEquals(
            "https://pay.snippe.sh/c/tok_xyz",
            result.redirectUrl,
            "a hosted checkout is useless without the URL to send the customer to",
        )
        assertEquals(ProviderPaymentStatus.PENDING, result.status)

        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertEquals("/api/v1/sessions", call.endpoint.path)
        assertEquals("Bearer api-key-value", call.headers["Authorization"])
        assertEquals(
            "PEAK-REF-1",
            call.headers["Idempotency-Key"],
            "a retried initiation must not open a second checkout for one purchase",
        )

        val body = objectMapper.readTree(requireNotNull(call.payload))
        assertTrue(
            body.path("external_reference").isMissingNode,
            "sessions 2026-01-25 has no external_reference field",
        )
        assertEquals(
            "PEAK-REF-1",
            body.path("metadata").path("external_reference").asString(""),
        )
        assertEquals("TZS", body.path("currency").asString(""))
        assertEquals(
            "mobile_money",
            body.path("allowed_methods").path(0).asString(""),
        )
        assertEquals(
            30000L,
            body.path("amount").asLong(0),
            "TZS carries no circulating subunit, so 30,000 shillings is sent as 30000",
        )
    }

    /**
     * The reference is what makes a lost callback recoverable. A session response without one
     * is unusable, and failing here is far better than discovering it when a callback goes
     * missing weeks later.
     */
    @Test
    fun aSessionWithNoReferenceIsRefusedRatherThanAccepted() {
        val transport = StubTransport(sessionResponse = """{"checkout_url":"https://x/y"}""")

        val failure = assertFailsWith<IllegalArgumentException> {
            provider(transport).initiate(command())
        }
        assertTrue(failure.message.orEmpty().contains("reconciled"), failure.message.orEmpty())
    }

    @Test
    fun statusIsQueriedByTheReferenceInitiationReturned() {
        val transport = StubTransport()
        val result = provider(transport).queryStatus(
            ProviderStatusQuery(
                internalReference = "sess_abc123def456",
                endpointUrl = "https://api.snippe.test",
                clientId = "client",
                apiKey = "api-key-value",
                checksumKey = secret,
            ),
        )

        assertEquals("/api/v1/sessions/sess_abc123def456", transport.calls.single().endpoint.path)
        assertEquals(ProviderPaymentStatus.SUCCEEDED, result.status)
        assertEquals(0, BigDecimal("30000").compareTo(result.amount))
    }

    // ---- direct push: what Peak's own subscription UX actually needs ----

    @Test
    fun aDirectPushGoesToThePaymentsEndpointAndSendsNoOneAnywhere() {
        val transport = StubTransport()
        val result = provider(transport).initiate(
            command(flow = "direct_push", name = "Asha Mwinyi", email = "asha@hotel.example"),
        )

        val call = transport.calls.single()
        assertEquals(
            "/v1/payments",
            call.endpoint.path,
            "the direct rail lives under /v1, not /api/v1 like sessions",
        )
        assertEquals(
            null,
            result.redirectUrl,
            "a push has nowhere to send the payer; they answer on the handset",
        )
        assertEquals("9015c155-9e29-4e8e-8fe6-d5d81553c8e6", result.providerReference)

        val body = objectMapper.readTree(requireNotNull(call.payload))
        assertEquals("mobile", body.path("payment_type").asString(""))
        assertEquals("255700000001", body.path("phone_number").asString(""))
        assertEquals(30000L, body.path("details").path("amount").asLong(0))
        assertEquals("TZS", body.path("details").path("currency").asString(""))
        assertEquals("Asha", body.path("customer").path("firstname").asString(""))
        assertEquals("Mwinyi", body.path("customer").path("lastname").asString(""))
        assertEquals("asha@hotel.example", body.path("customer").path("email").asString(""))
        assertEquals(
            "PEAK-REF-1",
            body.path("metadata").path("external_reference").asString(""),
            "the create-payment body has no external_reference field, so ours rides in " +
                "metadata or the callback cannot be matched to a payment Peak started",
        )
    }

    @Test
    fun aDirectPushStripsThePlusFromTheMsisdn() {
        val transport = StubTransport()
        provider(transport).initiate(
            command(
                flow = "direct_push",
                name = "Asha Mwinyi",
                email = "asha@hotel.example",
            ).copy(payerIdentifier = "+255781000000"),
        )
        val body = objectMapper.readTree(requireNotNull(transport.calls.single().payload))
        assertEquals(
            "255781000000",
            body.path("phone_number").asString(""),
            "Snippe mobile-money create documents 255XXXXXXXXX with no leading +",
        )
    }

    @Test
    fun aDirectPushWithoutThePayersNameOrEmailIsRefused() {
        listOf(
            command(flow = "direct_push", name = null, email = "a@b.example"),
            command(flow = "direct_push", name = "Asha Mwinyi", email = null),
        ).forEach { incomplete ->
            val transport = StubTransport()
            assertFailsWith<IllegalArgumentException> { provider(transport).initiate(incomplete) }
            assertTrue(
                transport.calls.isEmpty(),
                "placeholders must never reach a payment record, so this fails before the call",
            )
        }
    }

    @Test
    fun aSingleWordNameStandsInForBothHalvesRatherThanBeingInvented() {
        val transport = StubTransport()
        provider(transport).initiate(
            command(flow = "direct_push", name = "Asha", email = "asha@hotel.example"),
        )

        val customer = objectMapper.readTree(requireNotNull(transport.calls.single().payload))
            .path("customer")
        assertEquals("Asha", customer.path("firstname").asString(""))
        assertEquals(
            "Asha",
            customer.path("lastname").asString(""),
            "one-word names are ordinary here; padding with something made up would be worse",
        )
    }

    @Test
    fun theTwoFlowsUseTheirOwnStatusEndpoints() {
        val direct = StubTransport()
        provider(direct).queryStatus(statusQuery(flow = "direct_push", providerRef = "pay-uuid"))
        assertEquals("/v1/payments/pay-uuid", direct.calls.single().endpoint.path)

        val hosted = StubTransport()
        provider(hosted).queryStatus(statusQuery(flow = "hosted_checkout", providerRef = "sess_1"))
        assertEquals("/api/v1/sessions/sess_1", hosted.calls.single().endpoint.path)
    }

    @Test
    fun aSessionStatusAmountMayBeAScalar() {
        val transport = StubTransport(
            getResponse = """{"reference":"sess_abc123def456","status":"completed",
               "amount":30000,"currency":"TZS",
               "metadata":{"external_reference":"PEAK-REF-1"}}""",
        )
        val result = provider(transport).queryStatus(
            statusQuery(flow = "hosted_checkout", providerRef = "sess_abc123def456"),
        )
        assertEquals(
            0,
            BigDecimal("30000").compareTo(result.amount),
            "sessions 2026-01-25 return amount as a scalar, not {value, currency}",
        )
        assertEquals("TZS", result.currency)
        assertEquals("PEAK-REF-1", result.internalReference)
    }

    @Test
    fun statusIsAskedByTheReferenceSnippeIssuedNotPeaksOwn() {
        val transport = StubTransport()
        provider(transport).queryStatus(
            statusQuery(flow = "direct_push", providerRef = "9015c155-uuid"),
        )
        assertEquals(
            "/v1/payments/9015c155-uuid",
            transport.calls.single().endpoint.path,
            "Snippe keys its status endpoints on what it issued; Peak's reference is not " +
                "something it has ever seen",
        )
    }

    @Test
    fun anUnknownCollectionFlowIsRefusedRatherThanDefaulted() {
        assertFailsWith<IllegalArgumentException> {
            provider(StubTransport()).initiate(command(flow = "carrier_pigeon"))
        }
    }

    @Test
    fun aDirectPaymentCallbackIsMatchedThroughMetadata() {
        val payload = """{"id":"evt_direct","type":"payment.completed",
           "data":{"reference":"9015c155-uuid","external_reference":"SEL123456789",
           "status":"completed",
           "amount":{"value":30000,"currency":"TZS"},
           "metadata":{"external_reference":"PEAK-REF-1"}}}"""

        val notification = provider(StubTransport()).parseWebhook(payload)

        assertEquals("PEAK-REF-1", notification.internalReference)
        assertEquals("9015c155-uuid", notification.providerReference)
        assertEquals(ProviderPaymentStatus.SUCCEEDED, notification.status)
    }

    @Test
    fun anUpstreamProcessorReferenceIsNotTreatedAsPeaks() {
        val payload = """{"id":"evt_sel","type":"payment.completed",
           "data":{"reference":"pi_abc","external_reference":"SEL123456789",
           "status":"completed","amount":{"value":500,"currency":"TZS"}}}"""

        val failure = assertFailsWith<IllegalArgumentException> {
            provider(StubTransport()).parseWebhook(payload)
        }
        assertTrue(failure.message.orEmpty().contains("metadata"), failure.message.orEmpty())
    }

    @Test
    fun aCallbackCarryingNeitherReferenceIsRefused() {
        val payload = """{"id":"evt_x","type":"payment.completed",
           "data":{"reference":"r","amount":{"value":1,"currency":"TZS"}}}"""

        val failure = assertFailsWith<IllegalArgumentException> {
            provider(StubTransport()).parseWebhook(payload)
        }
        assertTrue(failure.message.orEmpty().contains("metadata"), failure.message.orEmpty())
    }

    // ---- webhook signature ----

    @Test
    fun aCorrectlySignedCallbackVerifies() {
        val payload = callbackJson()
        val timestamp = now.epochSecond.toString()

        val notification = provider(StubTransport()).verifyAndParseWebhook(
            payload = payload,
            checksumKey = secret,
            checksumRequired = true,
            headers = signedHeaders(payload, timestamp),
        )

        assertEquals("PEAK-REF-1", notification.internalReference)
        assertEquals(ProviderPaymentStatus.SUCCEEDED, notification.status)
        assertEquals(0, BigDecimal("30000").compareTo(notification.amount))
        assertEquals(0, BigDecimal("1000").compareTo(notification.feeAmount))
        assertEquals("HMAC-SHA256", notification.checksumMethod)
        assertEquals(
            "evt_abc123",
            notification.eventKey,
            "Snippe supplies a real event id, which is a far better replay key than a " +
                "transaction reference",
        )
    }

    /**
     * The mistake Snippe's documentation warns about, reproduced deliberately.
     *
     * Re-serialising the body changes whitespace and key order, so a verifier that signs its
     * own re-rendered JSON rejects every genuine callback. Signing the reformatted body here
     * must fail, which proves the adapter verifies the bytes it was given.
     */
    @Test
    fun aSignatureOverAReSerialisedBodyIsRejected() {
        val payload = callbackJson()
        val reSerialised = objectMapper.writeValueAsString(objectMapper.readTree(payload))
        assertTrue(reSerialised != payload, "precondition: re-serialising must change the bytes")

        val timestamp = now.epochSecond.toString()
        assertFailsWith<IllegalArgumentException> {
            provider(StubTransport()).verifyAndParseWebhook(
                payload = payload,
                checksumKey = secret,
                checksumRequired = true,
                headers = mapOf(
                    "X-Webhook-Timestamp" to timestamp,
                    "X-Webhook-Signature" to hmac(secret, "$timestamp.$reSerialised"),
                ),
            )
        }
    }

    @Test
    fun aSignatureThatOmitsTheTimestampFromTheMessageIsRejected() {
        val payload = callbackJson()
        val timestamp = now.epochSecond.toString()

        // Signed over the body alone, not "{timestamp}.{body}".
        assertFailsWith<IllegalArgumentException> {
            provider(StubTransport()).verifyAndParseWebhook(
                payload = payload,
                checksumKey = secret,
                checksumRequired = true,
                headers = mapOf(
                    "X-Webhook-Timestamp" to timestamp,
                    "X-Webhook-Signature" to hmac(secret, payload),
                ),
            )
        }
    }

    @Test
    fun aCallbackSignedWithAnotherSecretIsRejected() {
        val payload = callbackJson()
        val timestamp = now.epochSecond.toString()

        assertFailsWith<IllegalArgumentException> {
            provider(StubTransport()).verifyAndParseWebhook(
                payload = payload,
                checksumKey = secret,
                checksumRequired = true,
                headers = mapOf(
                    "X-Webhook-Timestamp" to timestamp,
                    "X-Webhook-Signature" to hmac("whsec_someone_elses", "$timestamp.$payload"),
                ),
            )
        }
    }

    /**
     * A valid signature on a captured callback is still a replay. The event ledger would
     * refuse to apply it twice, but rejecting on age is cheaper and does not depend on the
     * ledger being reachable.
     */
    @Test
    fun aCorrectlySignedButStaleCallbackIsRejected() {
        val payload = callbackJson()
        val stale = now.minus(Duration.ofMinutes(30)).epochSecond.toString()

        val failure = assertFailsWith<IllegalArgumentException> {
            provider(StubTransport()).verifyAndParseWebhook(
                payload = payload,
                checksumKey = secret,
                checksumRequired = true,
                headers = signedHeaders(payload, stale),
            )
        }
        assertTrue(failure.message.orEmpty().contains("replay"), failure.message.orEmpty())
    }

    @Test
    fun aSignatureWithoutATimestampCannotBeCheckedAndIsRejected() {
        val payload = callbackJson()

        assertFailsWith<IllegalArgumentException> {
            provider(StubTransport()).verifyAndParseWebhook(
                payload = payload,
                checksumKey = secret,
                checksumRequired = true,
                headers = mapOf("X-Webhook-Signature" to hmac(secret, "x.$payload")),
            )
        }
    }

    @Test
    fun headerNamesAreMatchedWithoutRegardToCase() {
        val payload = callbackJson()
        val timestamp = now.epochSecond.toString()

        // HTTP header names are case-insensitive and servers normalise them differently.
        val notification = provider(StubTransport()).verifyAndParseWebhook(
            payload = payload,
            checksumKey = secret,
            checksumRequired = true,
            headers = mapOf(
                "x-webhook-timestamp" to timestamp,
                "x-webhook-signature" to hmac(secret, "$timestamp.$payload"),
            ),
        )
        assertEquals("HMAC-SHA256", notification.checksumMethod)
    }

    @Test
    fun anUnsignedCallbackIsRejectedWhenAChecksumIsRequired() {
        assertFailsWith<IllegalArgumentException> {
            provider(StubTransport()).verifyAndParseWebhook(
                payload = callbackJson(),
                checksumKey = secret,
                checksumRequired = true,
                headers = emptyMap(),
            )
        }
    }

    @Test
    fun parsingWithoutVerifyingNeverClaimsTheCallbackWasVerified() {
        val notification = provider(StubTransport()).parseWebhook(callbackJson())

        assertEquals(null, notification.checksumMethod)
        assertFalse(notification.metadata["signatureVerified"] as Boolean)
    }

    @Test
    fun onlyACompletedPaymentCountsAsPaid() {
        listOf(
            "payment.completed" to ProviderPaymentStatus.SUCCEEDED,
            "payment.failed" to ProviderPaymentStatus.FAILED,
            "payment.expired" to ProviderPaymentStatus.FAILED,
            // The payer walked away. Terminal, but not a fault, and worth telling apart from
            // a payment the provider refused.
            "payment.voided" to ProviderPaymentStatus.CANCELLED,
            // Snippe adding an event type must not make Peak declare a guest's payment dead.
            // UNKNOWN keeps the status query running; PENDING would have claimed knowledge.
            "payment.something_new" to ProviderPaymentStatus.UNKNOWN,
        ).forEach { (event, expected) ->
            val notification = provider(StubTransport()).parseWebhook(callbackJson(type = event))
            assertEquals(expected, notification.status, "for $event")
        }
    }

    private fun provider(transport: StubTransport) = SnippePaymentProvider(
        transport = transport,
        objectMapper = objectMapper,
        properties = SnippeProperties(baseUrl = "https://api.snippe.test"),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun statusQuery(flow: String, providerRef: String) = ProviderStatusQuery(
        internalReference = "PEAK-REF-1",
        endpointUrl = "https://api.snippe.test",
        clientId = "client",
        apiKey = "api-key-value",
        checksumKey = secret,
        providerReference = providerRef,
        collectionFlow = flow,
    )

    private fun command(
        flow: String? = null,
        name: String? = null,
        email: String? = null,
    ) = ProviderCollectionCommand(
        transactionId = UUID.randomUUID(),
        internalReference = "PEAK-REF-1",
        endpointUrl = "https://api.snippe.test",
        clientId = "client-id-value",
        payerIdentifier = "255700000001",
        amount = BigDecimal("30000.00"),
        currency = "TZS",
        apiKey = "api-key-value",
        checksumKey = secret,
        providerChannel = "mobile_money",
        collectionFlow = flow,
        payerName = name,
        payerEmail = email,
    )

    private fun signedHeaders(payload: String, timestamp: String) = mapOf(
        "X-Webhook-Timestamp" to timestamp,
        "X-Webhook-Signature" to hmac(secret, "$timestamp.$payload"),
    )

    private fun hmac(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Deliberately irregular whitespace, so re-serialising it demonstrably differs. */
    private fun callbackJson(type: String = "payment.completed"): String =
        """{"id":"evt_abc123",  "type":"$type","api_version":"2026-01-25",
           "created_at":"2026-08-13T09:00:00Z","data":{"reference":"9015c155-9e29-4e8e-8fe6-d5d81553c8e6",
           "external_reference":"SEL123456789","status":"completed",
           "amount":{"value":30000,"currency":"TZS"},
           "settlement":{"fees":{"value":1000,"currency":"TZS"}},
           "channel":{"type":"mobile_money","provider":"mpesa"},
           "customer":{"phone":"+255700000001"},
           "metadata":{"external_reference":"PEAK-REF-1"},
           "completed_at":"2026-08-13T09:00:00Z"}}"""

    private data class RecordedCall(
        val method: String,
        val endpoint: URI,
        val headers: Map<String, String>,
        val payload: String?,
    )

    private class StubTransport(
        private val sessionResponse: String =
            """{"reference":"sess_abc123def456","status":"pending",
               "checkout_url":"https://pay.snippe.sh/c/tok_xyz"}""",
        private val getResponse: String? = null,
    ) : SnippeHttpTransport {
        val calls = mutableListOf<RecordedCall>()

        override fun exchange(
            method: String,
            endpoint: URI,
            headers: Map<String, String>,
            payload: String?,
        ): String {
            calls += RecordedCall(method, endpoint, headers, payload)
            if (endpoint.path.startsWith("/v1/payments") && method == "POST") {
                return """{"status":"success","code":201,"data":{
                   "amount":{"currency":"TZS","value":30000},"api_version":"2026-01-25",
                   "expires_at":"2026-08-13T13:00:00Z","object":"payment",
                   "payment_type":"mobile",
                   "reference":"9015c155-9e29-4e8e-8fe6-d5d81553c8e6","status":"pending"}}"""
            }
            return if (method == "GET") {
                getResponse ?: """{"reference":"sess_abc123def456",
                   "status":"completed","amount":{"value":30000,"currency":"TZS"},
                   "metadata":{"external_reference":"PEAK-REF-1"},
                   "completed_at":"2026-08-13T09:00:00Z"}"""
            } else {
                sessionResponse
            }
        }
    }
}
