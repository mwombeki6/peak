package com.mwombeki.peak.realtime.internal

import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Component
class SseRegistry {
    // Map of tenantId -> propertyId -> List of Emitters
    private val emitters = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>>()

    fun add(tenantId: UUID, propertyId: UUID, emitter: SseEmitter) {
        emitters.computeIfAbsent(tenantId) { ConcurrentHashMap() }
            .computeIfAbsent(propertyId) { CopyOnWriteArrayList() }
            .add(emitter)

        emitter.onCompletion { remove(tenantId, propertyId, emitter) }
        emitter.onTimeout { remove(tenantId, propertyId, emitter) }
        emitter.onError { remove(tenantId, propertyId, emitter) }
    }

    fun remove(tenantId: UUID, propertyId: UUID, emitter: SseEmitter) {
        emitters[tenantId]?.get(propertyId)?.remove(emitter)
    }

    fun getEmitters(tenantId: UUID, propertyId: UUID): List<SseEmitter> {
        return emitters[tenantId]?.get(propertyId) ?: emptyList()
    }
}
