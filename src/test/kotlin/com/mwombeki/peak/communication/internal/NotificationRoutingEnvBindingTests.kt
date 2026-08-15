package com.mwombeki.peak.communication.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * The compose file and the properties class have to agree, and nothing else checks that.
 *
 * Routing lives in `ops/production/compose.yaml` as `PEAK_COMMUNICATION_ROUTING_CHANNELS_EMAIL`
 * and reaches Kotlin as `peak.communication.routing.channels.email`. That translation is
 * Spring's relaxed binding, and it is the kind of thing that either works or silently produces
 * an empty map — in which case the worker starts cleanly, reports healthy, and every message
 * fails in the outbox because no channel is routed anywhere.
 *
 * Deployment configuration is not usually testable without deploying, but this part is, and
 * the alternative is finding out from a twelve-minute acceptance drill. It is written with a
 * real [SystemEnvironmentPropertySource] rather than plain properties precisely because the
 * underscore-to-dot mapping is the thing in doubt; asserting against
 * `peak.communication.routing.channels.email` directly would test Spring's map binding, which
 * was never the risk.
 */
class NotificationRoutingEnvBindingTests {

    @Test
    fun theEnvironmentVariablesInComposeReachThePropertiesClass() {
        val bound = bindFromEnvironment(
            "PEAK_COMMUNICATION_ROUTING_EMAIL" to "resend",
            "PEAK_COMMUNICATION_ROUTING_SMS" to "beem",
        )

        assertEquals(
            mapOf("email" to "resend", "sms" to "beem"),
            bound.channelRoutes(),
            "the names in compose.yaml must reach the properties class, or routing is empty " +
                "and every notification fails while the service looks healthy",
        )
    }

    @Test
    fun whatsappRoutingBindsFromTheComposeEnvironmentVariable() {
        val bound = bindFromEnvironment(
            "PEAK_COMMUNICATION_ROUTING_WHATSAPP" to "beem",
        )

        assertEquals(
            mapOf("whatsapp" to "beem"),
            bound.channelRoutes(),
            "PEAK_COMMUNICATION_ROUTING_WHATSAPP must reach peak.communication.routing.whatsapp",
        )
    }

    /**
     * The shape that does not work, kept so nobody reintroduces it.
     *
     * A `Map<String, String>` keyed by channel is the obvious model and it silently binds to
     * nothing from an environment variable: uppercased, there is no way to tell where
     * `channels` ends and the key begins. It produces no warning — just an empty map, a
     * healthy-looking worker, and every message failing in the outbox.
     */
    @Test
    fun aMapKeyedRoutingBlockWouldHaveBoundToNothing() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            SystemEnvironmentPropertySource(
                "peak-test-environment",
                mapOf<String, Any>("PEAK_COMMUNICATION_ROUTING_CHANNELS_EMAIL" to "resend"),
            ),
        )

        val asMap = Binder.get(environment)
            .bind("peak.communication.routing.channels", Map::class.java)
            .orElseGet { emptyMap<String, String>() }

        assertEquals(
            emptyMap<String, String>(),
            asMap,
            "if this ever starts binding, the named-field workaround can be revisited — but " +
                "until then a map is a silent misconfiguration waiting to happen",
        )
    }

    /**
     * A channel with an underscore in its name is where this binding usually breaks, since the
     * separator and the word boundary are the same character. `voice_phone` is already one of
     * the channels the HTTP gateway claims, so this is not hypothetical.
     */
    @Test
    fun aChannelWhoseNameContainsAnUnderscoreStillBinds() {
        val bound = bindFromEnvironment(
            "PEAK_COMMUNICATION_ROUTING_VOICE_PHONE" to "http-gateway",
        )

        assertEquals(
            mapOf("voice_phone" to "http-gateway"),
            bound.channelRoutes(),
            "voice_phone must survive the round trip through VOICE_PHONE rather than being " +
                "split or dropped",
        )
    }

    /** No routing configured is a valid deployment — it means no channel is served. */
    @Test
    fun anAbsentRoutingBlockLeavesEveryChannelUnconfigured() {
        assertEquals(emptyMap(), bindFromEnvironment().channelRoutes())
    }

    private fun bindFromEnvironment(vararg entries: Pair<String, String>):
        NotificationRoutingProperties {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            SystemEnvironmentPropertySource(
                "peak-test-environment",
                entries.toMap<String, Any>(),
            ),
        )
        return Binder.get(environment)
            .bind("peak.communication.routing", NotificationRoutingProperties::class.java)
            .orElseGet { NotificationRoutingProperties() }
    }
}
