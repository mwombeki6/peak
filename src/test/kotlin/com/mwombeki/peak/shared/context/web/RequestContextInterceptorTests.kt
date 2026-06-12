package com.mwombeki.peak.shared.context.web

import com.mwombeki.peak.shared.context.ExternalIdentityResolver
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestContextProperties
import com.mwombeki.peak.shared.context.RequestContextResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RequestContextInterceptorTests {

    @Test
    fun bindsContextAndCorrelationHeader() {
        val holder = RequestContextHolder()
        val interceptor = interceptor(holder)
        val request = MockHttpServletRequest("GET", "/api/v1/public/ping")
        request.addHeader(PeakRequestHeaders.CORRELATION_ID, "corr-interceptor")
        val response = MockHttpServletResponse()

        val shouldContinue = interceptor.preHandle(request, response, Any())

        assertEquals(true, shouldContinue)
        assertEquals("corr-interceptor", response.getHeader(PeakRequestHeaders.CORRELATION_ID))
        assertEquals("corr-interceptor", holder.current().correlationId)

        interceptor.afterCompletion(request, response, Any(), null)

        assertEquals(null, holder.currentOrNull())
    }

    @Test
    fun rejectsInvalidContextWithProblemDetails() {
        val holder = RequestContextHolder()
        val interceptor = interceptor(holder)
        val request = MockHttpServletRequest("GET", "/api/v1/platform/tenants")
        request.addHeader(PeakRequestHeaders.PLATFORM_USER_ID, "not-a-uuid")
        val response = MockHttpServletResponse()

        val shouldContinue = interceptor.preHandle(request, response, Any())

        assertEquals(false, shouldContinue)
        assertEquals(400, response.status)
        assertEquals("application/problem+json", response.contentType)
        assertNotNull(response.contentAsString)
        assertEquals(null, holder.currentOrNull())
    }

    private fun interceptor(
        holder: RequestContextHolder,
    ): RequestContextInterceptor {
        return RequestContextInterceptor(
            resolver = RequestContextResolver(
                RequestContextProperties(allowHeaderIdentity = false),
                ExternalIdentityResolver { null },
            ),
            holder = holder,
        )
    }
}
