package com.mwombeki.peak.shared.security

import com.mwombeki.peak.shared.config.PeakRuntimeMode
import com.mwombeki.peak.shared.config.PeakRuntimeProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RuntimeRouteBoundaryInterceptorTests {

    @Test
    fun allowsAllRoutesWhenBoundaryIsDisabled() {
        assertAllowed(
            mode = PeakRuntimeMode.PLATFORM,
            path = "/api/v1/reservations",
            enabled = false,
        )
    }

    @Test
    fun apiRuntimeExposesHospitalityRoutesAndHidesPlatformRoutes() {
        assertAllowed(PeakRuntimeMode.API, "/api/v1/reservations")
        assertAllowed(PeakRuntimeMode.API, "/api/v1/public/properties")
        assertDenied(PeakRuntimeMode.API, "/api/v1/platform/tenants")
        assertDenied(PeakRuntimeMode.API, "/api/platform/tenants")
    }

    @Test
    fun platformRuntimeExposesOnlyPlatformRoutes() {
        assertAllowed(PeakRuntimeMode.PLATFORM, "/api/v1/platform/tenants")
        assertAllowed(PeakRuntimeMode.PLATFORM, "/api/platform/monitoring")
        assertDenied(PeakRuntimeMode.PLATFORM, "/api/v1/reservations")
        assertDenied(PeakRuntimeMode.PLATFORM, "/api/v1/public/properties")
        assertDenied(PeakRuntimeMode.PLATFORM, "/api")
    }

    @Test
    fun nonWebRuntimesHideEveryApiRoute() {
        listOf(
            PeakRuntimeMode.WORKER,
            PeakRuntimeMode.MIGRATION,
            PeakRuntimeMode.BOOTSTRAP,
        ).forEach { mode ->
            assertDenied(mode, "/api/v1/reservations")
            assertDenied(mode, "/api/v1/platform/tenants")
        }
    }

    @Test
    fun ignoresNonApiPathsAndHonorsServletContextPath() {
        assertAllowed(PeakRuntimeMode.PLATFORM, "/actuator/health")

        val request = MockHttpServletRequest("GET", "/peak/api/v1/platform/tenants").apply {
            contextPath = "/peak"
        }
        val response = MockHttpServletResponse()

        assertTrue(interceptor(PeakRuntimeMode.PLATFORM).preHandle(request, response, Any()))
    }

    private fun assertAllowed(
        mode: PeakRuntimeMode,
        path: String,
        enabled: Boolean = true,
    ) {
        val response = MockHttpServletResponse()

        assertTrue(
            interceptor(mode, enabled)
                .preHandle(MockHttpServletRequest("GET", path), response, Any()),
        )
        assertEquals(200, response.status)
    }

    private fun assertDenied(
        mode: PeakRuntimeMode,
        path: String,
    ) {
        val response = MockHttpServletResponse()

        assertFalse(
            interceptor(mode)
                .preHandle(MockHttpServletRequest("GET", path), response, Any()),
        )
        assertEquals(404, response.status)
        assertEquals("application/problem+json", response.contentType)
        assertTrue(response.contentAsString.contains("\"title\":\"Not Found\""))
        assertTrue(response.contentAsString.contains("\"detail\":\"Resource not found\""))
        assertFalse(response.contentAsString.contains("platform"))
    }

    private fun interceptor(
        mode: PeakRuntimeMode,
        enabled: Boolean = true,
    ): RuntimeRouteBoundaryInterceptor =
        RuntimeRouteBoundaryInterceptor(
            properties = RuntimeRouteBoundaryProperties(enabled),
            runtimeProperties = PeakRuntimeProperties(mode),
            problemWriter = SecurityProblemWriter(),
        )
}
