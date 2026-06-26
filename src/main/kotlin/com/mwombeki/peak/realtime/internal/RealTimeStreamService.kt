package com.mwombeki.peak.realtime.internal

import com.mwombeki.peak.realtime.api.BroadcastEventRequest
import com.mwombeki.peak.realtime.api.RealtimePort
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Service
class RealtimeStreamService(
    private val messagingTemplate: SimpMessagingTemplate,
    private val sseRegistry: SseRegistry
) : RealtimePort {

    override fun broadcastLiveEvent(request: BroadcastEventRequest) {
        val targetDestination = "/topic/tenants/${request.tenantId}/properties/${request.propertyId}/stream"

        val standardizedMessage = mapOf(
            "eventType" to request.eventType,
            "timestamp" to java.time.Instant.now().toString(),
            "data" to request.payload
        )

        // 1. Broadcast to WebSockets
        messagingTemplate.convertAndSend(targetDestination, standardizedMessage as Any)

        // 2. Broadcast to SSE and keep a bounded replay window for reconnecting clients.
        val storedEvent = sseRegistry.recordEvent(
            tenantId = request.tenantId,
            propertyId = request.propertyId,
            eventType = request.eventType,
            data = standardizedMessage,
        )
        val sseEmitters = sseRegistry.getEmitters(request.tenantId, request.propertyId)
        sseEmitters.forEach { emitter ->
            try {
                emitter.send(
                    SseEmitter.event()
                        .id(storedEvent.id)
                        .name(request.eventType)
                        .data(standardizedMessage)
                )
                sseRegistry.recordDelivered(request.eventType)
            } catch (e: Exception) {
                sseRegistry.recordDeliveryFailure(request.eventType)
                sseRegistry.remove(request.tenantId, request.propertyId, emitter, "send_failure")
            }
        }

        logger.info(
            "Broadcast realtime event type={} destination={} sseSubscribers={}",
            request.eventType,
            targetDestination,
            sseEmitters.size,
        )
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(RealtimeStreamService::class.java)
    }
}
