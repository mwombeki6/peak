package com.mwombeki.peak.realtime.internal

import com.mwombeki.peak.realtime.api.BroadcastEventRequest
import com.mwombeki.peak.realtime.api.RealtimePort
import org.springframework.messaging.simp.SimpMessagingTemplate
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

        // 2. Broadcast to SSE
        val sseEmitters = sseRegistry.getEmitters(request.tenantId, request.propertyId)
        sseEmitters.forEach { emitter ->
            try {
                emitter.send(
                    SseEmitter.event()
                        .name(request.eventType)
                        .data(standardizedMessage)
                )
            } catch (e: Exception) {
                // Emitter might be closed already
                sseRegistry.remove(request.tenantId, request.propertyId, emitter)
            }
        }

        println("⚡ [Realtime Broadcast] Streamed event '${request.eventType}' to destination: $targetDestination (WS + ${sseEmitters.size} SSE)")
    }
}