package com.mwombeki.peak.realtime.internal

import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.time.Duration

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SseRegistry(
    private val properties: RealtimeSseProperties = RealtimeSseProperties(),
) {
    private val emitters = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>>()
    private val activeConnections = AtomicInteger(0)
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
            if (propertyEmitters.size >= properties.maxConnectionsPerProperty) {
                counter("peak.realtime.sse.connections.rejected", "reason", "limit").increment()
                return false
            }
            propertyEmitters.add(emitter)
            activeConnections.incrementAndGet()
            counter("peak.realtime.sse.connections.opened").increment()
        }

        emitter.onCompletion { remove(tenantId, propertyId, emitter, "completion") }
        emitter.onTimeout {
            remove(tenantId, propertyId, emitter, "timeout")
            emitter.complete()
        }
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

    fun timeoutMillis(): Long = properties.timeout.toMillis()

    fun recordDelivered(eventType: String) {
        counter("peak.realtime.sse.events.delivered", "eventType", eventType).increment()
    }

    fun recordDeliveryFailure(eventType: String) {
        counter("peak.realtime.sse.events.failed", "eventType", eventType).increment()
    }

    fun heartbeat() {
        emitters.forEach { (tenantId, properties) ->
            properties.forEach { (propertyId, propertyEmitters) ->
                propertyEmitters.forEach { emitter ->
                    try {
                        emitter.send(SseEmitter.event().name("heartbeat").data(""))
                    } catch (ex: Exception) {
                        remove(tenantId, propertyId, emitter, "heartbeat_failure")
                    }
                }
            }
        }
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

}

@ConfigurationProperties(prefix = "peak.realtime.sse")
data class RealtimeSseProperties(
    val maxConnectionsPerProperty: Int = 100,
    val timeout: Duration = Duration.ofMinutes(30),
) {
    init {
        require(maxConnectionsPerProperty in 1..10_000)
        require(!timeout.isNegative && !timeout.isZero)
    }
}
