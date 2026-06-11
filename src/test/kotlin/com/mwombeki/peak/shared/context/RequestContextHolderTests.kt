package com.mwombeki.peak.shared.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequestContextHolderTests {

    @Test
    fun storesAndClearsCurrentContext() {
        val holder = RequestContextHolder()
        val context = RequestContext(
            identity = RequestIdentity.Public(correlationId = "corr-1"),
            correlationId = "corr-1",
            idempotencyKey = null,
            httpMethod = "GET",
            requestPath = "/health",
        )

        holder.set(context)

        assertEquals(context, holder.current())
        assertEquals(context, holder.currentOrNull())

        holder.clear()

        assertEquals(null, holder.currentOrNull())
        assertFailsWith<RequestContextException> {
            holder.current()
        }
    }
}
