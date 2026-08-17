package com.mwombeki.peak.realtime.internal.config

import com.mwombeki.peak.realtime.internal.RealtimeDestinationParser
import com.mwombeki.peak.realtime.internal.RealtimeSecurityAuditService
import com.mwombeki.peak.realtime.internal.RealtimeSubscriptionAuthorizer
import com.mwombeki.peak.realtime.internal.RealtimeSubscriptionTarget
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextException
import com.mwombeki.peak.shared.context.RequestContextResolver
import com.mwombeki.peak.shared.context.RequestIdentity
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import org.springframework.web.socket.server.HandshakeInterceptor
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class WebSocketConfig(
    private val handshakeContextResolver: WebSocketHandshakeContextResolver,
    private val securityAuditService: RealtimeSecurityAuditService,
    private val meterRegistry: MeterRegistry,
    private val realtimeBrokerTaskScheduler: TaskScheduler,
    private val webSocketProperties: RealtimeWebSocketProperties,
    private val subscriptionAuthorizer: RealtimeSubscriptionAuthorizer,
) : WebSocketMessageBrokerConfigurer {

    private val activeConnections = AtomicInteger(0)

    init {
        meterRegistry.gauge("peak.realtime.websocket.connections.active", activeConnections)
    }

    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        // /topic is where clients listen for live broadcast updates
        config.enableSimpleBroker("/topic")
            .setTaskScheduler(realtimeBrokerTaskScheduler)
            .setHeartbeatValue(longArrayOf(10000, 10000)) // 10s heartbeats
        
        // /app is the prefix clients use to send messages to us
        config.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val endpoint = registry.addEndpoint("/ws-connect")
            .addInterceptors(object : HandshakeInterceptor {
                override fun beforeHandshake(
                    request: ServerHttpRequest,
                    response: ServerHttpResponse,
                    wsHandler: WebSocketHandler,
                    attributes: MutableMap<String, Any>,
                ): Boolean {
                    val context = handshakeContextResolver.resolve(request) ?: return false
                    attributes[SESSION_CONTEXT_ATTRIBUTE] = context
                    return true
                }

                override fun afterHandshake(
                    request: ServerHttpRequest,
                    response: ServerHttpResponse,
                    wsHandler: WebSocketHandler,
                    exception: Exception?,
                ) = Unit
            })
        val allowedOrigins = webSocketProperties.cleanedAllowedOrigins
        if (allowedOrigins.isNotEmpty()) {
            endpoint.setAllowedOrigins(*allowedOrigins.toTypedArray())
        }
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(object : ChannelInterceptor {
            override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
                val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java) ?: return message

                when (accessor.command) {
                    StompCommand.CONNECT -> {
                        val attributes = accessor.sessionAttributes
                            ?: throw SecurityException("Authenticated WebSocket session is required.")
                        val identity = (attributes[SESSION_CONTEXT_ATTRIBUTE] as? RequestContext)?.identity
                        if (identity !is RequestIdentity.Tenant) {
                            throw SecurityException("Tenant identity is required for realtime WebSocket sessions.")
                        }
                        if (attributes.putIfAbsent(SESSION_COUNTED_ATTRIBUTE, true) == null) {
                            val count = activeConnections.incrementAndGet()
                            if (count > webSocketProperties.maxConnections) {
                                activeConnections.decrementAndGet()
                                attributes.remove(SESSION_COUNTED_ATTRIBUTE)
                                meterRegistry.counter(
                                    "peak.realtime.websocket.connections.rejected",
                                    "reason",
                                    "limit",
                                ).increment()
                                throw SecurityException("Realtime WebSocket connection limit reached.")
                            }
                        }
                        meterRegistry.counter("peak.realtime.websocket.connect_attempts").increment()
                    }
                    StompCommand.DISCONNECT -> {
                        releaseConnection(accessor.sessionAttributes)
                    }
                    StompCommand.SUBSCRIBE -> {
                        val destination = accessor.destination
                            ?: throw IllegalStateException("No channel destination provided.")
                        val context = accessor.sessionAttributes
                            ?.get(SESSION_CONTEXT_ATTRIBUTE) as? RequestContext
                            ?: throw SecurityException("Authenticated WebSocket session is required.")
                        val identity = context.identity
                        val target = RealtimeDestinationParser.parse(destination)
                        if (
                            target == null ||
                            !subscriptionAuthorizer.canSubscribeDestination(
                                identity,
                                target,
                                context.sessionClass,
                                boundPropertyId = context.boundPropertyId,
                                boundOutletId = context.boundOutletId,
                            )
                        ) {
                            recordDeniedSubscription(
                                context = context,
                                targetTenantId = target.targetTenantIdOr(
                                    identity.tenantIdOrUnknown(),
                                ),
                                targetPropertyId = target.propertyIdOrUnknown(),
                                destination = destination,
                            )
                            throw SecurityException(
                                "Access denied for realtime subscription destination.",
                            )
                        }
                        meterRegistry.counter("peak.realtime.websocket.subscriptions").increment()
                    }
                    StompCommand.SEND -> throw SecurityException(
                        "Client publishing is not enabled for realtime streams.",
                    )
                    else -> {}
                }
                return message
            }
        })
    }

    @EventListener
    fun onSessionDisconnect(event: SessionDisconnectEvent) {
        releaseConnection(StompHeaderAccessor.wrap(event.message).sessionAttributes)
    }

    private fun releaseConnection(attributes: MutableMap<String, Any>?) {
        if (attributes?.remove(SESSION_COUNTED_ATTRIBUTE) == true) {
            activeConnections.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        }
    }

    private fun recordDeniedSubscription(
        context: RequestContext,
        targetTenantId: UUID,
        targetPropertyId: UUID,
        destination: String,
    ) {
        meterRegistry.counter("peak.realtime.security.violations").increment()
        securityAuditService.recordDeniedSubscription(
            context = context,
            targetTenantId = targetTenantId,
            targetPropertyId = targetPropertyId,
            destination = destination,
        )
    }

    private companion object {
        const val SESSION_CONTEXT_ATTRIBUTE = "peak.realtime.request-context"
        const val SESSION_COUNTED_ATTRIBUTE = "peak.realtime.connection-counted"
        val INVALID_PROPERTY_ID: UUID = UUID(0, 0)

        fun RequestIdentity?.tenantIdOrUnknown(): UUID =
            (this as? RequestIdentity.Tenant)?.tenantId ?: INVALID_PROPERTY_ID

        fun RealtimeSubscriptionTarget?.propertyIdOrUnknown(): UUID =
            when (this) {
                is RealtimeSubscriptionTarget.PropertyStream -> propertyId
                is RealtimeSubscriptionTarget.PropertyOperations -> propertyId
                is RealtimeSubscriptionTarget.Outlet,
                is RealtimeSubscriptionTarget.Order,
                is RealtimeSubscriptionTarget.Payment,
                null,
                -> INVALID_PROPERTY_ID
            }

        /**
         * The tenant the subscription was aimed at, which is the whole point of auditing
         * a denial: the actor is already recorded as the row's tenant, so recording the
         * actor again as the target makes a cross-tenant attempt indistinguishable from a
         * same-tenant one. Only PropertyStream names a tenant in the destination; the
         * others carry a property, outlet, order or payment, so there the caller's tenant
         * is still the most truthful answer available without a lookup.
         */
        fun RealtimeSubscriptionTarget?.targetTenantIdOr(fallback: UUID): UUID =
            when (this) {
                is RealtimeSubscriptionTarget.PropertyStream -> tenantId
                is RealtimeSubscriptionTarget.PropertyOperations,
                is RealtimeSubscriptionTarget.Outlet,
                is RealtimeSubscriptionTarget.Order,
                is RealtimeSubscriptionTarget.Payment,
                null,
                -> fallback
            }
    }
}

@Component
class WebSocketHandshakeContextResolver(
    private val requestContextResolver: RequestContextResolver,
    private val meterRegistry: MeterRegistry,
) {
    fun resolve(request: ServerHttpRequest): RequestContext? {
        val servletRequest = (request as? ServletServerHttpRequest)?.servletRequest
            ?: return reject("unsupported_request")
        val authentication = SecurityContextHolder.getContext().authentication
            ?: request.principal as? Authentication
            ?: return reject("authentication_required")
        val context = try {
            requestContextResolver.resolve(servletRequest, authentication)
        } catch (_: RequestContextException) {
            return reject("invalid_request_context")
        }
        return if (context.identity is RequestIdentity.Tenant) {
            context
        } else {
            reject("tenant_identity_required")
        }
    }

    private fun reject(reason: String): Nothing? {
        meterRegistry.counter(
            "peak.realtime.websocket.handshakes.rejected",
            "reason",
            reason,
        ).increment()
        return null
    }
}

@Configuration
@EnableScheduling
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RealtimeBrokerSchedulerConfiguration {
    @Bean
    fun realtimeBrokerTaskScheduler(): TaskScheduler {
        return ThreadPoolTaskScheduler().apply {
            poolSize = 2
            setThreadNamePrefix("realtime-broker-")
            setRemoveOnCancelPolicy(true)
        }
    }
}
