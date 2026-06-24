package com.mwombeki.peak.realtime.api

import java.util.UUID

data class BroadcastEventRequest(
    val tenantId: UUID,
    val propertyId: UUID,
    val eventType: String, // e.g., "ROOM_STATUS_CHANGED", "NEW_BOOKING"
    val payload: Map<String, Any>
)

interface RealtimePort {
    fun broadcastLiveEvent(request: BroadcastEventRequest)
}

/**
 * Domain event that should be broadcasted via Real-time streams.
 */
data class RealtimeEvent(
    val tenantId: UUID,
    val propertyId: UUID,
    val eventType: String,
    val payload: Map<String, Any>
)