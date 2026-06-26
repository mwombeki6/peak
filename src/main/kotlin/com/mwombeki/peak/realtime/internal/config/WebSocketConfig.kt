package com.mwombeki.peak.realtime.internal.config

import com.mwombeki.peak.audit.api.AuditOutcome
import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.realtime.internal.RealtimeSubscriptionAuthorizer
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val requestContextHolder: RequestContextHolder,
    private val auditPort: AuditPort,
    private val meterRegistry: MeterRegistry,
    private val realtimeBrokerTaskScheduler: TaskScheduler,
    private val webSocketProperties: RealtimeWebSocketProperties,
    private val subscriptionAuthorizer: RealtimeSubscriptionAuthorizer,
) : WebSocketMessageBrokerConfigurer {

    private val activeConnections = AtomicInteger(0)

    init {
        meterRegistry.gauge("realtime.websocket.active_connections", activeConnections)
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
        val allowedOrigins = webSocketProperties.cleanedAllowedOrigins
        if (allowedOrigins.isNotEmpty()) {
            endpoint.setAllowedOrigins(*allowedOrigins.toTypedArray())
        }
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(object : ChannelInterceptor {
            override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
                val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java) ?: return message

                when (accessor.command) {
                    StompCommand.CONNECT -> {
                        activeConnections.incrementAndGet()
                        meterRegistry.counter("realtime.websocket.connect_attempts").increment()
                    }
                    StompCommand.DISCONNECT -> {
                        activeConnections.decrementAndGet()
                    }
                    StompCommand.SUBSCRIBE -> {
                        val destination = accessor.destination ?: throw IllegalStateException("No channel destination provided.")
                        val match = STREAM_DESTINATION_PATTERN.matchEntire(destination) ?: return message
                        val targetTenantId = UUID.fromString(match.groupValues[1])
                        val targetPropertyId = UUID.fromString(match.groupValues[2])
                        val identity = requestContextHolder.current().identity

                        if (!subscriptionAuthorizer.canSubscribe(identity, targetTenantId, targetPropertyId)) {
                            recordDeniedSubscription(identity, targetTenantId, targetPropertyId, destination)
                            throw SecurityException("Access denied for realtime property stream.")
                        }

                        meterRegistry.counter("realtime.websocket.subscriptions").increment()
                    }
                    else -> {}
                }
                return message
            }
        })
    }

    private fun recordDeniedSubscription(
        identity: RequestIdentity,
        targetTenantId: UUID,
        targetPropertyId: UUID,
        destination: String,
    ) {
        meterRegistry.counter("realtime.security.violations").increment()
        if (identity is RequestIdentity.Tenant) {
            auditPort.recordTenantEvent(
                TenantAuditEvent(
                    tenantId = identity.tenantId,
                    action = "realtime.subscription_denied",
                    resource = AuditResource("realtime_stream", targetPropertyId),
                    outcome = AuditOutcome.FAILURE,
                    after = mapOf(
                        "attempted_destination" to destination,
                        "target_tenant_id" to targetTenantId,
                        "target_property_id" to targetPropertyId,
                    ),
                ),
            )
        }
    }

    private companion object {
        val STREAM_DESTINATION_PATTERN = Regex("^/topic/tenants/([^/]+)/properties/([^/]+)/stream$")
    }
}

@Configuration
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
