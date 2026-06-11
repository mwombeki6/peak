package com.mwombeki.peak.reliability.internal

import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import tools.jackson.databind.ObjectMapper

class RequestHasherTests {

    @Test
    fun hashesStableRequestShape() {
        val hasher = RequestHasher(ObjectMapper())
        val context = RequestContext(
            identity = RequestIdentity.Public(correlationId = "corr"),
            correlationId = "corr",
            idempotencyKey = "idem",
            httpMethod = "post",
            requestPath = "/api/v1/platform/tenants",
        )
        val payload = mapOf("slug" to "peak")

        val first = hasher.hash(context, "tenant.create", payload)
        val second = hasher.hash(context, "tenant.create", payload)
        val different = hasher.hash(context, "tenant.update", payload)

        assertEquals(first, second)
        assertNotEquals(first, different)
    }
}
