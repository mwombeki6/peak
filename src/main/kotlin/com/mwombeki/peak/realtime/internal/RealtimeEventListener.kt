package com.mwombeki.peak.realtime.internal

import com.mwombeki.peak.realtime.api.BroadcastEventRequest
import com.mwombeki.peak.realtime.api.RealtimeEvent
import com.mwombeki.peak.realtime.api.RealtimePort
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class RealtimeEventListener(
    private val realtimePort: RealtimePort
) {

    @EventListener
    fun handleRealtimeEvent(event: RealtimeEvent) {
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
