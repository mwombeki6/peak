package com.mwombeki.peak.realtime.internal

import com.mwombeki.peak.realtime.api.BroadcastEventRequest
import com.mwombeki.peak.realtime.api.RealtimeEventRequest
import com.mwombeki.peak.realtime.api.RealtimePort
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RealtimeStreamService(
    private val eventJournal: RealtimeEventJournal,
) : RealtimePort {

    override fun broadcastLiveEvent(request: BroadcastEventRequest) {
        broadcastRealtimeEvent(
            RealtimeEventRequest(
                tenantId = request.tenantId,
                propertyId = request.propertyId,
                eventType = request.eventType,
                payload = request.payload,
            ),
        )
    }

    override fun broadcastRealtimeEvent(request: RealtimeEventRequest) {
        require(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Realtime events must be persisted inside the owning business transaction"
        }
        eventJournal.append(
            tenantId = request.tenantId,
            propertyId = request.propertyId,
            outletId = request.outletId,
            eventType = request.eventType,
            schemaVersion = request.schemaVersion,
            aggregateType = request.aggregateType,
            aggregateId = request.aggregateId,
            aggregateVersion = request.aggregateVersion,
            payload = request.payload,
        )
    }

    /** Maps a stored event to the canonical envelope delivered to subscribers. */
    companion object {
        fun envelope(event: StoredRealtimeEvent): Map<String, Any?> = mapOf(
            "sequenceId" to event.sequenceId,
            "eventId" to event.eventId.toString(),
            "type" to event.eventType,
            "schemaVersion" to event.schemaVersion,
            "aggregateType" to event.aggregateType,
            "aggregateId" to event.aggregateId?.toString(),
            "aggregateVersion" to event.aggregateVersion,
            "occurredAt" to event.createdAt.toString(),
            "tenantId" to event.tenantId.toString(),
            "propertyId" to event.propertyId.toString(),
            "outletId" to event.outletId?.toString(),
            "payload" to event.payload,
        )
    }
}