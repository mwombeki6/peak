package com.mwombeki.peak.realtime.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

class SseRegistryTests {

    private val registry = SseRegistry()
    private val tenantId = UUID.randomUUID()
    private val propertyId = UUID.randomUUID()

    @Test
    fun `should add and retrieve emitters`() {
        val emitter = SseEmitter()
        assertTrue(registry.add(tenantId, propertyId, emitter))

        val emitters = registry.getEmitters(tenantId, propertyId)
        assertEquals(1, emitters.size)
        assertEquals(emitter, emitters[0])
        assertEquals(1, registry.activeConnectionCount())
    }

    @Test
    fun `should remove emitter manually`() {
        val emitter = SseEmitter()
        registry.add(tenantId, propertyId, emitter)
        registry.remove(tenantId, propertyId, emitter)

        val emitters = registry.getEmitters(tenantId, propertyId)
        assertTrue(emitters.isEmpty())
        assertEquals(0, registry.activeConnectionCount())
    }

    @Test
    fun `should return empty list for unknown tenant or property`() {
        val emitters = registry.getEmitters(UUID.randomUUID(), UUID.randomUUID())
        assertTrue(emitters.isEmpty())
    }

    @Test
    fun `should reject connections beyond per property limit`() {
        repeat(100) {
            assertTrue(registry.add(tenantId, propertyId, SseEmitter()))
        }

        assertFalse(registry.add(tenantId, propertyId, SseEmitter()))
        assertEquals(100, registry.getEmitters(tenantId, propertyId).size)
        assertEquals(100, registry.activeConnectionCount())
    }

}
