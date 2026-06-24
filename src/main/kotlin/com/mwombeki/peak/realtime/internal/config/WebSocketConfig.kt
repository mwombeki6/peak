package com.mwombeki.peak.realtime.internal.config

import com.mwombeki.peak.audit.api.AuditOutcome
import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
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
    private val meterRegistry: MeterRegistry
) : WebSocketMessageBrokerConfigurer {

    private val activeConnections = AtomicInteger(0)

    init {
        meterRegistry.gauge("realtime.websocket.active_connections", activeConnections)
    }

    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        // /topic is where clients listen for live broadcast updates
        config.enableSimpleBroker("/topic")
            .setHeartbeatValue(longArrayOf(10000, 10000)) // 10s heartbeats
        
        // /app is the prefix clients use to send messages to us
        config.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        // The URL the frontend connects to initially
        registry.addEndpoint("/ws-connect").setAllowedOrigins("*")
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

                        // Destination format: /topic/tenants/{tenantId}/properties/{propertyId}/stream
                        val routeParts = destination.split("/")
                        if (routeParts.size >= 6 && routeParts[2] == "tenants") {
                            val targetTenantIdStr = routeParts[3]
                            val targetTenantId = UUID.fromString(targetTenantIdStr)

                            // Grab who is currently trying to subscribe from our security context
                            val context = requestContextHolder.current()
                            val identity = context.identity

                            if (identity is RequestIdentity.Tenant) {
                                val activeTenantId = identity.tenantId

                                // 🚨 THE SECURITY SHIELD: If they don't match, drop the connection immediately!
                                if (activeTenantId != targetTenantId) {
                                    recordSuspiciousAccess(activeTenantId, targetTenantId, destination)
                                    throw SecurityException("Access Denied: You cannot subscribe to another tenant's live stream!")
                                }
                            } else if (identity !is RequestIdentity.Platform) {
                                throw SecurityException("Access Denied: Missing valid credentials.")
                            }
                            
                            meterRegistry.counter("realtime.websocket.subscriptions", "tenantId", targetTenantIdStr).increment()
                        }
                    }
                    else -> {}
                }
                return message
            }
        })
    }

    private fun recordSuspiciousAccess(activeTenantId: UUID, targetTenantId: UUID, destination: String) {
        meterRegistry.counter("realtime.security.violations").increment()
        
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = activeTenantId,
                action = "SUSPICIOUS_REALTIME_SUBSCRIPTION",
                resource = AuditResource("realtime_stream", targetTenantId),
                outcome = AuditOutcome.FAILURE,
                after = mapOf(
                    "attempted_destination" to destination,
                    "target_tenant_id" to targetTenantId
                )
            )
        )
    }
}

