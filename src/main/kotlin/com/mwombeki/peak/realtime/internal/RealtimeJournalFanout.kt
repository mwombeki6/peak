package com.mwombeki.peak.realtime.internal

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
    prefix = "peak.realtime.journal",
    name = ["fanout-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class RealtimeJournalFanout(
    private val journal: RealtimeEventJournal,
    private val messagingTemplate: SimpMessagingTemplate,
    private val sseRegistry: SseRegistry,
    private val meterRegistry: MeterRegistry,
    private val fanoutExecutor: RealtimeFanoutExecutor,
    private val destinationRouter: RealtimeDestinationRouter,
) {
    private val cursor = AtomicLong(0)

    @PostConstruct
    fun initializeCursor() {
        cursor.set(journal.latestSequence())
    }

    @Scheduled(fixedDelayString = "\${peak.realtime.journal.poll-interval-ms:250}")
    fun poll() {
        var events = journal.pollAfter(cursor.get())
        while (events.isNotEmpty()) {
            events.forEach { event ->
                fanoutExecutor.execute(event) {
                    deliver(event)
                }
            }
            cursor.set(events.last().sequenceId)
            events = if (!journal.isFullPollBatch(events)) {
                emptyList()
            } else {
                journal.pollAfter(cursor.get())
            }
        }
    }

    @Scheduled(fixedDelayString = "\${peak.realtime.sse.heartbeat-interval-ms:15000}")
    fun heartbeat() {
        sseRegistry.heartbeat()
    }

    @Scheduled(fixedDelayString = "\${peak.realtime.journal.cleanup-interval-ms:60000}")
    fun cleanup() {
        val deleted = journal.deleteExpired()
        if (deleted > 0) {
            logger.info("Deleted {} expired realtime journal events", deleted)
        }
    }

    private fun deliver(event: StoredRealtimeEvent) {
        val envelope = RealtimeStreamService.envelope(event)
        destinationRouter.destinations(event).forEach { destination ->
            messagingTemplate.convertAndSend(destination, envelope)
        }
        val emitters = sseRegistry.getEmitters(event.tenantId, event.propertyId)
        emitters.forEach { emitter ->
            try {
                emitter.send(
                    SseEmitter.event()
                        .id(event.sequenceId.toString())
                        .name(event.eventType)
                        .data(envelope),
                )
                sseRegistry.recordDelivered(event.eventType)
            } catch (ex: Exception) {
                sseRegistry.recordDeliveryFailure(event.eventType)
                sseRegistry.remove(
                    event.tenantId,
                    event.propertyId,
                    emitter,
                    "send_failure",
                )
            }
        }
        meterRegistry.counter(
            "peak.realtime.events.fanned_out",
            "eventType",
            event.eventType,
            "destinations",
            destinationRouter.destinations(event).size.toString(),
        ).increment()
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(RealtimeJournalFanout::class.java)
    }
}
