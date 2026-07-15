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

    @Test
    fun treatsAsyncRequestTimeoutAsNormalCompletion() {
        val request = MockHttpServletRequest("GET", "/api/v1/realtime/stream")

        val response = handler.handleAsyncRequestTimeout(request)

        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
    }

    @Test
    fun normalizesRequestParsingAndRoutingFailures() {
        MDC.put("correlation_id", "corr-invalid-request")
        val request = MockHttpServletRequest("POST", "/api/v1/test")

        val responses = listOf(
            handler.handleUnsupportedMediaType(request) to HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            handler.handleUnreadableMessage(request) to HttpStatus.BAD_REQUEST,
            handler.handleArgumentTypeMismatch(request) to HttpStatus.BAD_REQUEST,
            handler.handleUnsupportedMethod(request) to HttpStatus.METHOD_NOT_ALLOWED,
        )

        responses.forEach { (response, expectedStatus) ->
            val problem = assertNotNull(response.body)
            assertEquals(expectedStatus, response.statusCode)
            assertEquals("corr-invalid-request", problem.properties?.get("traceId"))
            assertEquals("/api/v1/test", problem.properties?.get("path"))
        }
    }
}
