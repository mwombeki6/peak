package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.payment.api.ProviderStatusQuery
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

/**
 * One hotel's AzamPay authority must never be reachable from another's.
 *
 * ```
 * Tenant: hotel group
 * ├── Property A ── AzamPay merchant A ── token A
 * └── Property B ── AzamPay merchant B ── token B
 * ```
 *
 * The token cache held a single entry. It compared the client id before serving, so it could
 * not hand A's request B's token — but that made isolation a property of one comparison rather
 * than of the design, and it had two consequences worth removing before any hotel collects on
 * this rail. Alternating between two properties re-minted on every call, against an
 * authenticator AzamPay rate-limits. And invalidating one merchant's token discarded every
 * other merchant's live token with it.
 *
 * The cache is now keyed by the identity AzamPay itself authenticates — authenticator, app
 * registration, client — so a token minted for one merchant is unreachable from another rather
 * than merely unserved.
 *
 * These tests drive the token provider directly instead of through the adapter, because the
 * question is about the cache and an adapter would only obscure which mint happened for whom.
 */
class AzamPayMerchantIsolationTests {

    private val objectMapper = JsonMapper.builder().build()

    /** The sequence that used to re-mint every time. */
    @Test
    fun twoMerchantsAlternatingDoNotEvictEachOther() {
        val transport = MintCountingTransport()
        val tokens = tokenProvider(transport)

        val firstA = tokens.token(clientId = "merchant-a", clientSecret = "secret-a")
        val firstB = tokens.token(clientId = "merchant-b", clientSecret = "secret-b")
        val secondA = tokens.token(clientId = "merchant-a", clientSecret = "secret-a")
        val secondB = tokens.token(clientId = "merchant-b", clientSecret = "secret-b")

        assertEquals(firstA, secondA, "A's second call must reuse A's token")
        assertEquals(firstB, secondB, "B's second call must reuse B's token")
        assertTrue(firstA != firstB, "two merchants must not share a token")

        assertEquals(
            2,
            transport.mints.get(),
            "one mint per merchant. A single-entry cache mints four times here, hammering an " +
                "authenticator AzamPay rate-limits, and does so more the busier the group is",
        )
    }

    /** The property that actually matters: A can never observe B's authority. */
    @Test
    fun aMerchantNeverReceivesAnotherMerchantsToken() {
        val transport = MintCountingTransport()
        val tokens = tokenProvider(transport)

        val forA = tokens.token(clientId = "merchant-a", clientSecret = "secret-a")
        val forB = tokens.token(clientId = "merchant-b", clientSecret = "secret-b")

        assertEquals("token-for-merchant-a", forA)
        assertEquals("token-for-merchant-b", forB)
        assertEquals(
            "secret-a",
            transport.secretPresentedFor("merchant-a"),
            "each merchant's token must be minted with that merchant's own secret",
        )
        assertEquals("secret-b", transport.secretPresentedFor("merchant-b"))
    }

    /**
     * Same client id, different app registration, is a different merchant.
     *
     * A property collecting into its own AzamPay account registers its own application. A
     * token minted under Peak's registration carries Peak's authority, so the registration has
     * to be part of the cache identity or the first caller's authority would be served to the
     * second.
     */
    @Test
    fun theAppRegistrationIsPartOfTheIdentity() {
        val transport = MintCountingTransport()
        val tokens = tokenProvider(transport)

        val underPeak = tokens.token("shared-client", "secret", appName = "Peak")
        val underHotel = tokens.token("shared-client", "secret", appName = "HotelOwnApp")

        assertTrue(
            underPeak != underHotel,
            "two application registrations are two authorities, however similar the client id",
        )
        assertEquals(2, transport.mints.get())
    }

    /** Rotating one merchant's credentials must not cost every other merchant its token. */
    @Test
    fun invalidatingOneMerchantLeavesTheOthersAlone() {
        val transport = MintCountingTransport()
        val tokens = tokenProvider(transport)

        tokens.token(clientId = "merchant-a", clientSecret = "secret-a")
        val firstB = tokens.token(clientId = "merchant-b", clientSecret = "secret-b")

        tokens.invalidate(clientId = "merchant-a")

        val secondB = tokens.token(clientId = "merchant-b", clientSecret = "secret-b")
        assertEquals(firstB, secondB, "B's token must survive A's rotation")
        assertEquals(2, transport.mints.get(), "only A may be re-minted, and only when asked")

        tokens.token(clientId = "merchant-a", clientSecret = "secret-a")
        assertEquals(3, transport.mints.get(), "A re-mints, because A is what was invalidated")
    }

    /**
     * Two properties transacting at once is the ordinary case for a group, not an edge one.
     * A cache that is only correct when serialised is not correct.
     */
    @Test
    fun concurrentMerchantsEachGetTheirOwnToken() {
        val transport = MintCountingTransport()
        val tokens = tokenProvider(transport)
        val merchants = (1..8).map { "merchant-$it" }
        val observed = ConcurrentHashMap<String, MutableSet<String>>()

        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        try {
            val running = merchants.flatMap { merchant ->
                (1..4).map {
                    pool.submit {
                        start.await()
                        val token = tokens.token(merchant, "secret-${merchant.substringAfter('-')}")
                        observed.computeIfAbsent(merchant) {
                            ConcurrentHashMap.newKeySet()
                        }.add(token)
                    }
                }
            }
            start.countDown()
            running.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        merchants.forEach { merchant ->
            assertEquals(
                setOf("token-for-$merchant"),
                observed[merchant]?.toSet(),
                "$merchant saw a token that was not its own",
            )
        }
    }

    /**
     * The registration has to survive the whole way from the account row to the mint, or the
     * cache is keyed on something the caller never actually supplies.
     *
     * `PaymentOutboxHandler` reads `payment_provider_accounts.provider_app_name` (V107) into
     * the collection command, and the adapter hands it to the token provider. This pins the
     * adapter's half — that the value on the command is what authenticates, rather than the
     * configured default silently winning.
     */
    @Test
    fun theAdapterAuthenticatesUnderTheAccountsOwnRegistration() {
        val transport = MintCountingTransport()
        val tokens = tokenProvider(transport)
        val provider = AzamPayPaymentProvider(
            transport = transport,
            tokenProvider = tokens,
            publicKeyProvider = AzamPayPublicKeyProvider(transport),
            signature = AzamPaySignature(),
            objectMapper = objectMapper,
            properties = AzamPayProperties(
                authenticatorUrl = "https://authenticator-sandbox.azampay.co.tz",
                paymentsUrl = "https://sandbox.azampay.co.tz",
                appName = "Peak",
            ),
        )

        provider.queryStatus(
            ProviderStatusQuery(
                internalReference = "PEAK-REF-1",
                providerReference = "AZ-123",
                endpointUrl = "https://sandbox.azampay.co.tz",
                clientId = "hotel-client",
                apiKey = "api-key",
                checksumKey = "hotel-secret",
                providerAppName = "HotelOwnApp",
            ),
        )

        assertEquals(
            "HotelOwnApp",
            transport.registrationPresentedFor("hotel-client"),
            "the hotel's own registration must reach AzamPay. Falling back to Peak's would " +
                "mint a token carrying Peak's authority for the hotel's money",
        )
    }

    private fun tokenProvider(transport: MintCountingTransport) = AzamPayTokenProvider(
        transport = transport,
        objectMapper = objectMapper,
        properties = AzamPayProperties(
            authenticatorUrl = "https://authenticator-sandbox.azampay.co.tz",
            paymentsUrl = "https://sandbox.azampay.co.tz",
            appName = "Peak",
        ),
        clock = Clock.fixed(Instant.parse("2026-08-14T09:00:00Z"), ZoneOffset.UTC),
    )

    /** Answers with a token naming whoever asked, so a mix-up is visible rather than inferred. */
    private class MintCountingTransport : AzamPayHttpTransport {
        val mints = AtomicInteger()
        private val secrets = ConcurrentHashMap<String, String>()
        private val registrations = ConcurrentHashMap<String, String>()

        fun secretPresentedFor(clientId: String): String? = secrets[clientId]

        fun registrationPresentedFor(clientId: String): String? = registrations[clientId]

        override fun exchange(
            method: String,
            endpoint: URI,
            headers: Map<String, String>,
            payload: String?,
        ): String {
            // The status call is answered so the adapter can be driven end to end; every
            // other path would only obscure which mint happened for whom.
            if (endpoint.path.contains("gettransactionstatus")) {
                return """{"transactionid":"AZ-123","externalreference":"PEAK-REF-1",
                           "transactionstatus":"success","amount":"30000","currency":"TZS",
                           "time":"2026-08-14T09:05:00Z"}"""
            }
            require(endpoint.path.contains("GenerateToken")) {
                "these tests exercise the token cache only, not $endpoint"
            }
            mints.incrementAndGet()
            val body = payload.orEmpty()
            val clientId = body.substringAfter("\"clientId\":\"").substringBefore("\"")
            val appName = body.substringAfter("\"appName\":\"").substringBefore("\"")
            secrets[clientId] = body.substringAfter("\"clientSecret\":\"").substringBefore("\"")
            registrations[clientId] = appName

            // The app name is folded in so a token minted under a different registration is
            // distinguishable, which is what the identity test needs to be able to see.
            val suffix = if (appName == "Peak") "" else "-via-$appName"
            return """{"success":true,"data":{"accessToken":"token-for-$clientId$suffix",
                       "expire":"2026-08-14T18:00:00Z"}}"""
        }
    }
}
