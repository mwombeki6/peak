package com.mwombeki.peak.reliability.internal

import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Publishes internal platform events to the structured operational log stream.
 *
 * The outbox worker marks an event delivered only after this sink has accepted it.
 * Payloads are deliberately not logged because they can contain business data.
 */
@Component
@ConditionalOnProperty(
    prefix = "peak.reliability.outbox.platform-operations",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class PlatformOperationsOutboxHandler(
    private val meterRegistry: ObjectProvider<MeterRegistry>,
) : OutboxEventHandler {
    override val destination = OutboxDestination.PLATFORM

    override suspend fun handle(event: ClaimedOutboxEvent) {
        logger.info(
            "Published platform operation event id={} type={} aggregateType={} aggregateId={} tenantId={} propertyId={} correlationId={}",
            event.id,
            event.eventType,
            event.aggregateType,
            event.aggregateId,
            event.tenantId,
            event.propertyId,
            event.correlationId,
        )
        meterRegistry.ifAvailable { registry ->
            registry.counter(
                "peak.outbox.platform.events.published",
                "event_type",
                event.eventType,
            ).increment()
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(PlatformOperationsOutboxHandler::class.java)
    }
}
