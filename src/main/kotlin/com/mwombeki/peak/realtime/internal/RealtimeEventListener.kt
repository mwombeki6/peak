package com.mwombeki.peak.realtime.internal

import com.mwombeki.peak.realtime.api.BroadcastEventRequest
import com.mwombeki.peak.realtime.api.RealtimePort
import com.mwombeki.peak.shared.context.RealtimeStreamEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class RealtimeEventListener(
    private val realtimePort: RealtimePort
) {

    @EventListener
    fun handleRealtimeEvent(event: RealtimeStreamEvent) {
        realtimePort.broadcastLiveEvent(
            BroadcastEventRequest(
                tenantId = event.tenantId,
                propertyId = event.propertyId,
                eventType = event.eventType,
                payload = event.payload
            )
        )
    }
}
