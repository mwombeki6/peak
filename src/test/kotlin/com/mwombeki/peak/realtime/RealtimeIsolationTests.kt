package com.mwombeki.peak.realtime

import com.mwombeki.peak.realtime.internal.SseRegistry
import com.mwombeki.peak.realtime.internal.web.RealtimeController
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RealtimeIsolationTests {
    private val requestContextHolder = RequestContextHolder()
    private val sseRegistry = SseRegistry()
    private val controller = RealtimeController(sseRegistry, requestContextHolder)

    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val tenantUserId = UUID.randomUUID()
    private val propertyA = UUID.randomUUID()

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
    }

    @Test
    fun `should allow access to own tenant stream`() {
        bindTenantContext(tenantA)

        val emitter = controller.streamEvents(tenantA, propertyA)

        assertEquals(1, sseRegistry.getEmitters(tenantA, propertyA).size)
        emitter.complete()
    }

    @Test
    fun `should deny access to another tenant stream`() {
        bindTenantContext(tenantA)

        assertFailsWith<SecurityException> {
            controller.streamEvents(tenantB, propertyA)
        }
    }

    private fun bindTenantContext(tenantId: UUID) {
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Tenant(tenantId, tenantUserId),
                correlationId = "test-corr-id",
                idempotencyKey = null,
                httpMethod = "GET",
                requestPath = "/api/v1/realtime/tenants/$tenantId/properties/$propertyA/stream",
            ),
        )
    }
}
