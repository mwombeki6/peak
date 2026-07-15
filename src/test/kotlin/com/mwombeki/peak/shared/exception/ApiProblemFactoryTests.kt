package com.mwombeki.peak.shared.exception

import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.http.HttpStatus

class ApiProblemFactoryTests {
    private val contextHolder = RequestContextHolder()
    private val factory = ApiProblemFactory(contextHolder)

    @AfterTest
    fun clearContext() {
        contextHolder.clear()
    }

    @Test
    fun `adds correlation and path while redacting sensitive values`() {
        val tenantId = UUID.randomUUID()
        contextHolder.set(
            RequestContext(
                identity = RequestIdentity.Tenant(tenantId, UUID.randomUUID()),
                correlationId = "corr-problem-redaction",
                idempotencyKey = null,
                httpMethod = "POST",
                requestPath = "/api/v1/tenants/$tenantId/test",
            ),
        )

        val response = factory.response(
            HttpStatus.BAD_REQUEST,
            "Invalid request",
            "email guest@example.com token=secret-value url=https://storage.example/report?signature=secret",
        )

        val problem = assertNotNull(response.body)
        assertEquals("corr-problem-redaction", problem.properties?.get("traceId"))
        assertEquals("/api/v1/tenants/$tenantId/test", problem.properties?.get("path"))
        assertTrue(problem.detail.orEmpty().contains("[redacted-address]"))
        assertTrue(problem.detail.orEmpty().contains("token=[redacted]"))
        assertTrue(problem.detail.orEmpty().contains("?[redacted]"))
        assertFalse(problem.detail.orEmpty().contains("guest@example.com"))
        assertFalse(problem.detail.orEmpty().contains("secret-value"))
    }

    @Test
    fun `removes database diagnostics after the public first line`() {
        val response = factory.response(
            HttpStatus.CONFLICT,
            "Conflict",
            "ERROR: Public business rule\n  Detail: relation internal_table violated",
        )

        assertEquals("Public business rule", response.body?.detail)
    }
}
