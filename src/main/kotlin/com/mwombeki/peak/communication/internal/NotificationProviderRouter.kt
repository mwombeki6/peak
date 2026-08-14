package com.mwombeki.peak.communication.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Which channel is delivered by which adapter, decided by configuration.
 *
 * A blank entry means the channel is deliberately unavailable — WhatsApp before anyone has
 * bought it, voice before it exists. That is a different thing from a channel that is
 * configured and broken, and the two must not produce the same outcome.
 *
 * Named fields rather than a `Map<String, String>`, and not by preference. Routing is set
 * through environment variables in `compose.yaml`, and Spring cannot bind a map from
 * `PEAK_COMMUNICATION_ROUTING_CHANNELS_EMAIL`: with everything uppercased there is nothing to
 * tell it where the prefix stops and the key begins, so it binds an **empty map** and reports
 * no error. The worker would have started cleanly, looked healthy, and failed every message
 * in the outbox. `NotificationRoutingEnvBindingTests` caught that before CI did.
 *
 * A closed set is also the truth: channels are defined by what adapters can carry, not by
 * whatever a deployment invents.
 */
@ConfigurationProperties(prefix = "peak.communication.routing")
data class NotificationRoutingProperties(
    val email: String = "",
    val sms: String = "",
    val whatsapp: String = "",
    /** Binds from `PEAK_COMMUNICATION_ROUTING_VOICE_PHONE`. */
    val voicePhone: String = "",
) {
    /** Channel to provider code, with unconfigured channels left out entirely. */
    fun channelRoutes(): Map<String, String> = mapOf(
        "email" to email,
        "sms" to sms,
        "whatsapp" to whatsapp,
        "voice_phone" to voicePhone,
    ).filterValues { it.isNotBlank() }
}

/**
 * Routes a notification to exactly one adapter, or says the channel is not configured.
 *
 * This replaces `providers.firstOrNull { it.supports(channel) }`, which used capability
 * discovery as routing. `supports()` answers *can this adapter deliver this kind of thing* —
 * a property of the adapter. It cannot answer *should this adapter receive this message*,
 * which is a decision about the deployment. Conflating them meant the answer came from Spring
 * bean ordering, silently, and only stayed right because exactly one provider was ever enabled.
 *
 * It also meant an adapter had to lie about its capabilities to stay out of the way. The
 * generic HTTP gateway legitimately supports email and SMS as a fallback integration; under
 * the old rule, saying so would have been enough to hijack both channels from Resend and Beem.
 * Routing being explicit is what lets an adapter tell the truth about what it can do.
 *
 * Every misconfiguration is refused at startup rather than at 3am when a password reset does
 * not arrive. Deploying successfully and discovering later that invitation emails never left
 * the system is the failure this exists to prevent.
 *
 * No failover. `Resend fails → quietly send via the HTTP gateway` is a policy with its own
 * consequences for sender identity, templates, duplicate delivery, cost and delivery-status
 * semantics, and it is not something to acquire by accident. Failures go to the existing
 * retry and delivery-attempt machinery.
 */
@Component
class NotificationProviderRouter(
    providers: List<NotificationDeliveryProvider>,
    properties: NotificationRoutingProperties,
) {
    private val routes: Map<String, NotificationDeliveryProvider>

    init {
        val duplicates = providers
            .groupBy { it.providerCode.trim().lowercase() }
            .filterValues { it.size > 1 }
        check(duplicates.isEmpty()) {
            "Two notification adapters share a provider code, so routing to it is ambiguous " +
                "however it is configured: ${duplicates.keys}"
        }

        val byCode = providers.associateBy { it.providerCode.trim().lowercase() }

        routes = properties.channelRoutes().entries.associate { (rawChannel, rawCode) ->
            val channel = rawChannel.trim().lowercase()
            val code = rawCode.trim().lowercase()

            // Configured and absent is corruption, not an unavailable channel. Starting up
            // would mean every message on this channel silently failing forever.
            val provider = byCode[code]
                ?: error(
                    "Channel '$channel' is routed to notification provider '$code', which is " +
                        "not registered. Available: ${byCode.keys.sorted()}",
                )

            // A route to an adapter that cannot serve the channel is the same class of fault,
            // and is worth separating because the fix is different: this one is a typo in the
            // channel, not a missing deployment.
            check(provider.supports(channel)) {
                "Channel '$channel' is routed to '$code', which does not support it"
            }

            channel to provider
        }
    }

    /**
     * @return the single adapter configured for this channel, or null when the channel is
     *   deliberately not configured. Never a guess, and never dependent on bean ordering.
     */
    fun routeFor(channel: String): NotificationDeliveryProvider? =
        routes[channel.trim().lowercase()]

    /** Channels this deployment can actually deliver, for readiness reporting. */
    fun configuredChannels(): Set<String> = routes.keys
}
