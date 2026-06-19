package com.mwombeki.peak.shared.exception

import com.mwombeki.peak.shared.context.PeakRequestHeaders
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException

class GlobalExceptionHandlerTests {

    private val handler = GlobalExceptionHandler()

    @AfterTest
    fun clearMdc() {
        MDC.clear()
    }

    @Test
    fun usesCorrelationIdFromMdcForBusinessErrors() {
        MDC.put("correlation_id", "corr-from-mdc")
        val request = MockHttpServletRequest("POST", "/api/test")

        val response = handler.handleBusinessException(
            BusinessException(
                message = "Invalid operation",
                status = HttpStatus.CONFLICT,
                errorCode = "INVALID_OPERATION",
            ),
            request,
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("corr-from-mdc", response.body?.traceId)
    }

    @Test
    fun fallsBackToCorrelationHeaderForGenericErrors() {
        val request = MockHttpServletRequest("GET", "/api/test")
        request.addHeader(PeakRequestHeaders.CORRELATION_ID, "corr-from-header")

        val response = handler.handleGenericException(
            IllegalStateException("boom"),
            request,
        )

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals("corr-from-header", response.body?.traceId)
    }

    @Test
    fun addsTraceIdAndPathToResponseStatusProblemDetails() {
        MDC.put("correlation_id", "corr-problem")
        val request = MockHttpServletRequest("GET", "/api/missing")

        val response = handler.handleResponseStatusException(
            ResponseStatusException(HttpStatus.NOT_FOUND, "Not here"),
            request,
        )

        val problem = assertNotNull(response.body)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("corr-problem", problem.properties?.get("traceId"))
        assertEquals("/api/missing", problem.properties?.get("path"))
    }
}
