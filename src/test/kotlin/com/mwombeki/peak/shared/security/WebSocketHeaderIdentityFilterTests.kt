package com.mwombeki.peak.shared.security

import com.mwombeki.peak.shared.context.HeaderIdentityAuthentication
import com.mwombeki.peak.shared.context.OperationalSessionAuthentication
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import com.mwombeki.peak.shared.context.RequestContextProperties
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

class WebSocketHeaderIdentityFilterTests {

    @AfterTest
    fun clear() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun refusesOperationalBearerCombinedWithIdentityHeaders() {
        val filter = WebSocketHeaderIdentityFilter(RequestContextProperties(allowHeaderIdentity = true))
        val authentication = OperationalSessionAuthentication(
            sessionId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            tenantUserId = UUID.randomUUID(),
            deviceId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
        )
        SecurityContextHolder.getContext().authentication = authentication
        val request = MockHttpServletRequest("GET", "/ws-connect").apply {
            addHeader(HttpHeaders.AUTHORIZATION, "Bearer ops_secret")
            addHeader(PeakRequestHeaders.TENANT_ID, authentication.tenantId.toString())
            addHeader(PeakRequestHeaders.TENANT_USER_ID, authentication.tenantUserId.toString())
        }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun keepsOperationalSessionWhenHeadersAreAbsent() {
        val filter = WebSocketHeaderIdentityFilter(RequestContextProperties(allowHeaderIdentity = true))
        val authentication = OperationalSessionAuthentication(
            sessionId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            tenantUserId = UUID.randomUUID(),
            deviceId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
        )
        SecurityContextHolder.getContext().authentication = authentication
        val request = MockHttpServletRequest("GET", "/ws-connect")

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertTrue(SecurityContextHolder.getContext().authentication === authentication)
    }

    @Test
    fun appliesHeaderIdentityWhenNoBearerIsPresent() {
        val filter = WebSocketHeaderIdentityFilter(RequestContextProperties(allowHeaderIdentity = true))
        val tenantId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val request = MockHttpServletRequest("GET", "/ws-connect").apply {
            addHeader(PeakRequestHeaders.TENANT_ID, tenantId.toString())
            addHeader(PeakRequestHeaders.TENANT_USER_ID, userId.toString())
        }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        val applied = SecurityContextHolder.getContext().authentication as HeaderIdentityAuthentication
        assertTrue(applied.tenantId == tenantId)
        assertTrue(applied.tenantUserId == userId)
    }
}
