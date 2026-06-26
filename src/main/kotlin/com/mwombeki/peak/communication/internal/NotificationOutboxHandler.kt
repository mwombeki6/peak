package com.mwombeki.peak.communication.internal

import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

@Component
class NotificationOutboxHandler(
    private val meterRegistry: ObjectProvider<MeterRegistry>,
) : OutboxEventHandler {
    override val destination = OutboxDestination.NOTIFICATION

    override suspend fun handle(event: ClaimedOutboxEvent) {
        meterRegistry.ifAvailable { registry ->
            registry.counter(
                "peak.communication.notification.outbox.handled",
                "eventType",
                event.eventType,
            ).increment()
        }
        logger.info(
            "Accepted communication notification outbox event {} tenantId={} propertyId={} eventType={}",
            event.id,
            event.tenantId,
            event.propertyId,
            event.eventType,
        )
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(NotificationOutboxHandler::class.java)
    }
}
