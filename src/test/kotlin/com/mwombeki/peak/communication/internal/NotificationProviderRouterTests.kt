package com.mwombeki.peak.communication.internal

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Delivery routing is a decision, not a search.
 *
 * The handler used to pick with `providers.firstOrNull { it.supports(channel) }`, which asks
 * every adapter whether it *can* deliver and hands the message to whichever answered first.
 * That is capability discovery standing in for routing, and the tie-break is Spring's bean
 * ordering. It only stayed correct because exactly one adapter was ever enabled at a time.
 *
 * Adding Resend and Beem would have been the event that exposed it — and the failure would
 * have been silent, because the generic HTTP gateway already claims `email`, `sms`, `whatsapp`
 * and `voice_phone`. Email could have kept going to the gateway with nothing logged, which is
 * the same class of defect as the payment-provider vocabulary bug: two halves each internally
 * consistent, never introduced to each other.
 *
 * These are the six cases that make ambiguity impossible rather than merely detectable.
 */
class NotificationProviderRouterTests {

    /**
     * The case the old code could not express. Two adapters may both be capable of email; only
     * one is routed it. An adapter should not have to understate what it can do to stay out of
     * the way — the HTTP gateway is a legitimate fallback integration and says so honestly.
     */
    @Test
    fun twoAdaptersMayBothSupportEmailWhenOnlyOneIsRoutedIt() {
        val resend = StubProvider("resend", "email")
        val gateway = StubProvider("http-gateway", "email", "sms")

        val router = router(
            providers = listOf(resend, gateway),
            channels = mapOf("email" to "resend"),
        )

        assertEquals(resend, router.routeFor("email"))
        assertNull(
            router.routeFor("sms"),
            "the gateway supports sms but nothing routes it there, so sms is unavailable " +
                "rather than quietly delivered by whoever happened to be capable",
        )
    }

    /** The tie-break that used to decide this must now decide nothing. */
    @Test
    fun beanOrderingChangesNothing() {
        val resend = StubProvider("resend", "email")
        val gateway = StubProvider("http-gateway", "email")
        val channels = mapOf("email" to "resend")

        assertEquals(
            resend,
            router(listOf(resend, gateway), channels).routeFor("email"),
        )
        assertEquals(
            resend,
            router(listOf(gateway, resend), channels).routeFor("email"),
            "reversing registration order must not move a channel to another provider",
        )
    }

    /**
     * Configuration corruption, refused at startup.
     *
     * The alternative is a deployment that comes up green and never sends a password reset.
     * Nobody discovers that from a dashboard; they discover it from a locked-out user days
     * later, by which time the cause is several deploys back.
     */
    @Test
    fun aChannelRoutedToAMissingAdapterRefusesToStart() {
        val refused = assertFailsWith<IllegalStateException> {
            router(
                providers = listOf(StubProvider("http-gateway", "email")),
                channels = mapOf("email" to "resend"),
            )
        }

        assertTrue(refused.message!!.contains("not registered"), refused.message!!)
        assertTrue(
            refused.message!!.contains("http-gateway"),
            "the message must say what is available, or the fix is a guess: ${refused.message}",
        )
    }

    /** Same class of fault, different fix: the channel is wrong rather than the deployment. */
    @Test
    fun aChannelRoutedToAnAdapterThatCannotServeItRefusesToStart() {
        val refused = assertFailsWith<IllegalStateException> {
            router(
                providers = listOf(StubProvider("beem", "sms")),
                channels = mapOf("email" to "beem"),
            )
        }

        assertTrue(refused.message!!.contains("does not support"), refused.message!!)
    }

    /**
     * Two adapters answering to one code makes routing ambiguous however it is configured,
     * so no configuration can rescue it and it is refused outright.
     */
    @Test
    fun twoAdaptersSharingAProviderCodeRefuseToStart() {
        val refused = assertFailsWith<IllegalStateException> {
            router(
                providers = listOf(StubProvider("beem", "sms"), StubProvider("beem", "whatsapp")),
                channels = emptyMap(),
            )
        }

        assertTrue(refused.message!!.contains("ambiguous"), refused.message!!)
    }

    /**
     * A channel nobody bought is unavailable, and that is not a fault. It must not take the
     * configured channels down with it — which is the practical difference between "we do not
     * offer WhatsApp" and "notifications are broken".
     */
    @Test
    fun anUnconfiguredChannelIsUnavailableWithoutAffectingTheOthers() {
        val router = router(
            providers = listOf(StubProvider("resend", "email"), StubProvider("beem", "sms")),
            channels = mapOf("email" to "resend", "sms" to "beem"),
        )

        assertNull(router.routeFor("whatsapp"))
        assertEquals("resend", router.routeFor("email")?.providerCode)
        assertEquals("beem", router.routeFor("sms")?.providerCode)
        assertEquals(setOf("email", "sms"), router.configuredChannels())
    }

    /** Config is hand-written, so casing and stray whitespace must not change behaviour. */
    @Test
    fun routingIsNotDefeatedByCasingOrWhitespace() {
        val router = NotificationProviderRouter(
            listOf(StubProvider("Resend", "email")),
            NotificationRoutingProperties(email = " resend "),
        )

        assertEquals("Resend", router.routeFor("Email")?.providerCode)
    }

    private fun router(
        providers: List<NotificationDeliveryProvider>,
        channels: Map<String, String>,
    ) = NotificationProviderRouter(
        providers,
        NotificationRoutingProperties(
            email = channels["email"].orEmpty(),
            sms = channels["sms"].orEmpty(),
            whatsapp = channels["whatsapp"].orEmpty(),
            voicePhone = channels["voice_phone"].orEmpty(),
        ),
    )

    private class StubProvider(
        override val providerCode: String,
        private vararg val channels: String,
    ) : NotificationDeliveryProvider {
        override fun supports(channel: String) = channel in channels

        override fun send(command: NotificationDeliveryCommand) =
            NotificationDeliveryResult(providerMessageId = "stub-${UUID.randomUUID()}")
    }
}
