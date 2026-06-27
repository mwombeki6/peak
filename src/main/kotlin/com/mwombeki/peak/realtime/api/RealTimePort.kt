package com.mwombeki.peak.realtime.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
data class BroadcastEventRequest(
    val tenantId: UUID,
    val propertyId: UUID,
    val eventType: String, // e.g., "ROOM_STATUS_CHANGED", "NEW_BOOKING"
    val payload: Map<String, Any?>
)

@NamedInterface("api")
interface RealtimePort {
    fun broadcastLiveEvent(request: BroadcastEventRequest)
}

/**
 * Domain event that should be broadcasted via Real-time streams.
 */
@NamedInterface("api")
data class RealtimeEvent(
    val tenantId: UUID,
    val propertyId: UUID,
    val eventType: String,
    val payload: Map<String, Any?>
)
