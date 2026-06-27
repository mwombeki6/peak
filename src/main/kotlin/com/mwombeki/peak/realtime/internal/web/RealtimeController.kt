package com.mwombeki.peak.realtime.internal.web

import com.mwombeki.peak.realtime.internal.SseRegistry
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

@RestController
@RequestMapping("/api/v1/realtime")
class RealtimeController(
    private val sseRegistry: SseRegistry,
    private val requestContextHolder: RequestContextHolder
) {

    @GetMapping("/tenants/{tenantId}/properties/{propertyId}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamEvents(
        @PathVariable tenantId: UUID,
        @PathVariable propertyId: UUID,
        @RequestHeader(name = "Last-Event-ID", required = false) lastEventId: String? = null,
    ): SseEmitter {
        val context = requestContextHolder.current()
        val identity = context.identity

        if (identity is RequestIdentity.Tenant) {
            if (identity.tenantId != tenantId) {
                throw SecurityException("Access Denied: You cannot subscribe to another tenant's live stream!")
            }
        } else if (identity !is RequestIdentity.Platform) {
            throw SecurityException("Access Denied: Missing valid credentials.")
        }

        val emitter = SseEmitter(60_000L)
        if (!sseRegistry.add(tenantId, propertyId, emitter)) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many active realtime SSE connections for this property.",
            )
        }

        try {
            emitter.send(
                SseEmitter.event()
                    .name("connection-established")
                    .data("Connected to stream for property $propertyId"),
            )
            sseRegistry.replayAfter(tenantId, propertyId, lastEventId).forEach { event ->
                emitter.send(
                    SseEmitter.event()
                        .id(event.id)
                        .name(event.eventType)
                        .data(event.data),
                )
                sseRegistry.recordDelivered(event.eventType)
            }
        } catch (ex: Exception) {
            sseRegistry.remove(tenantId, propertyId, emitter, "initial_send_failed")
            if (ex is IllegalArgumentException) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ex.message,
                    ex,
                )
            }
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Unable to establish realtime SSE stream.",
                ex,
            )
        }

        return emitter
    }
}
