package com.mwombeki.peak.realtime.internal

import com.mwombeki.peak.realtime.api.BroadcastEventRequest
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
        require(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Realtime events must be persisted inside the owning business transaction"
        }
        val standardizedMessage = mapOf(
            "eventType" to request.eventType,
            "timestamp" to java.time.Instant.now().toString(),
            "data" to request.payload
        )
        eventJournal.append(
            tenantId = request.tenantId,
            propertyId = request.propertyId,
            eventType = request.eventType,
            payload = standardizedMessage,
        )
    }
}
