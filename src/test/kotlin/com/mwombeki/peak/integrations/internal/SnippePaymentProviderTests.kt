package com.mwombeki.peak.integrations.internal

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
    fun initiateOpensAHostedCheckoutAndReturnsSomewhereToSendThePayer() {
        val transport = StubTransport()
        val result = provider(transport).initiate(command())

        assertEquals("sess_abc123def456", result.providerReference)
        assertEquals(
            "https://pay.snippe.sh/c/tok_xyz",
            result.redirectUrl,
            "a hosted checkout is useless without the URL to send the customer to",
        )
        assertEquals("pending", result.status)

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
        assertEquals("PEAK-REF-1", body.path("external_reference").asString(""))
        assertEquals("TZS", body.path("currency").asString(""))
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
        assertEquals("succeeded", result.status)
        assertEquals(0, BigDecimal("30000").compareTo(result.amount))
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
        assertEquals("succeeded", notification.status)
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
            "payment.completed" to "succeeded",
            "payment.failed" to "failed",
            "payment.voided" to "failed",
            "payment.expired" to "failed",
            "payment.something_new" to "pending",
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

    private fun command() = ProviderCollectionCommand(
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
           "created_at":"2026-08-13T09:00:00Z","data":{"reference":"sess_abc123def456",
           "external_reference":"PEAK-REF-1","status":"completed",
           "amount":{"value":30000,"currency":"TZS"},
           "settlement":{"fees":{"value":1000,"currency":"TZS"}},
           "channel":{"type":"mobile_money","provider":"mpesa"},
           "customer":{"phone":"+255700000001"},"completed_at":"2026-08-13T09:00:00Z"}}"""

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
    ) : SnippeHttpTransport {
        val calls = mutableListOf<RecordedCall>()

        override fun exchange(
            method: String,
            endpoint: URI,
            headers: Map<String, String>,
            payload: String?,
        ): String {
            calls += RecordedCall(method, endpoint, headers, payload)
            return if (method == "GET") {
                """{"reference":"sess_abc123def456","external_reference":"PEAK-REF-1",
                   "status":"completed","amount":{"value":30000,"currency":"TZS"},
                   "completed_at":"2026-08-13T09:00:00Z"}"""
            } else {
                sessionResponse
            }
        }
    }
}
