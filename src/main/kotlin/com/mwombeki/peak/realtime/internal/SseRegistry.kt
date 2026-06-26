package com.mwombeki.peak.realtime.internal

import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Component
class SseRegistry {
    private val emitters = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>>()
    private val replayBuffers = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, ArrayDeque<StoredSseEvent>>>()
    private val activeConnections = AtomicInteger(0)
    private val eventSequence = AtomicLong(0)
    private val fallbackMeterRegistry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
    private var meterRegistry: MeterRegistry? = null

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    fun bindMeterRegistry(meterRegistry: MeterRegistry) {
        this.meterRegistry = meterRegistry
        meterRegistry.gauge("peak.realtime.sse.connections.active", activeConnections)
    }

    fun add(tenantId: UUID, propertyId: UUID, emitter: SseEmitter): Boolean {
        val propertyEmitters = emitters.computeIfAbsent(tenantId) { ConcurrentHashMap() }
            .computeIfAbsent(propertyId) { CopyOnWriteArrayList() }

        synchronized(propertyEmitters) {
            if (propertyEmitters.size >= MAX_CONNECTIONS_PER_PROPERTY) {
                counter("peak.realtime.sse.connections.rejected", "reason", "limit").increment()
                return false
            }
            propertyEmitters.add(emitter)
            activeConnections.incrementAndGet()
            counter("peak.realtime.sse.connections.opened").increment()
        }

        emitter.onCompletion { remove(tenantId, propertyId, emitter, "completion") }
        emitter.onTimeout { remove(tenantId, propertyId, emitter, "timeout") }
        emitter.onError { remove(tenantId, propertyId, emitter, "error") }
        return true
    }

    fun remove(
        tenantId: UUID,
        propertyId: UUID,
        emitter: SseEmitter,
        reason: String = "manual",
    ) {
        val removed = emitters[tenantId]?.get(propertyId)?.remove(emitter) == true
        if (removed) {
            activeConnections.decrementAndGet()
            counter("peak.realtime.sse.connections.closed", "reason", reason).increment()
            removeEmptyBuckets(tenantId, propertyId)
        }
    }

    fun getEmitters(tenantId: UUID, propertyId: UUID): List<SseEmitter> {
        return emitters[tenantId]?.get(propertyId) ?: emptyList()
    }

    fun activeConnectionCount(): Int {
        return activeConnections.get()
    }

    fun recordEvent(
        tenantId: UUID,
        propertyId: UUID,
        eventType: String,
        data: Any,
    ): StoredSseEvent {
        val event = StoredSseEvent(
            id = eventSequence.incrementAndGet().toString(),
            eventType = eventType,
            data = data,
        )
        val buffer = replayBuffers.computeIfAbsent(tenantId) { ConcurrentHashMap() }
            .computeIfAbsent(propertyId) { ArrayDeque() }
        synchronized(buffer) {
            buffer.addLast(event)
            while (buffer.size > MAX_REPLAY_EVENTS_PER_PROPERTY) {
                buffer.removeFirst()
                recordBackpressureDrop(eventType, "replay_buffer_overflow")
            }
        }
        counter("peak.realtime.sse.events.published", "eventType", eventType).increment()
        return event
    }

    fun replayAfter(
        tenantId: UUID,
        propertyId: UUID,
        lastEventId: String?,
    ): List<StoredSseEvent> {
        if (lastEventId.isNullOrBlank()) {
            return emptyList()
        }
        val after = lastEventId.toLongOrNull()
            ?: throw IllegalArgumentException("Last-Event-ID must be a numeric SSE event id.")
        val buffer = replayBuffers[tenantId]?.get(propertyId) ?: return emptyList()
        return synchronized(buffer) {
            buffer.filter { it.id.toLong() > after }
        }
    }

    fun recordDelivered(eventType: String) {
        counter("peak.realtime.sse.events.delivered", "eventType", eventType).increment()
    }

    fun recordDeliveryFailure(eventType: String) {
        counter("peak.realtime.sse.events.failed", "eventType", eventType).increment()
    }

    fun recordBackpressureDrop(eventType: String, reason: String) {
        counter("peak.realtime.sse.events.dropped", "eventType", eventType, "reason", reason).increment()
    }

    private fun removeEmptyBuckets(tenantId: UUID, propertyId: UUID) {
        val propertyEmitters = emitters[tenantId]?.get(propertyId)
        if (propertyEmitters != null && propertyEmitters.isEmpty()) {
            emitters[tenantId]?.remove(propertyId)
        }
        if (emitters[tenantId]?.isEmpty() == true) {
            emitters.remove(tenantId)
        }
    }

    private fun counter(
        name: String,
        vararg tags: String,
    ) = (meterRegistry ?: fallbackMeterRegistry).counter(name, *tags)

    private companion object {
        const val MAX_CONNECTIONS_PER_PROPERTY = 100
        const val MAX_REPLAY_EVENTS_PER_PROPERTY = 500
    }
}

data class StoredSseEvent(
    val id: String,
    val eventType: String,
    val data: Any,
)
