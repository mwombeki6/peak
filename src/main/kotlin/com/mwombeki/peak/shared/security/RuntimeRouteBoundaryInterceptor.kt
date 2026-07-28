package com.mwombeki.peak.shared.security

import com.mwombeki.peak.shared.config.PeakRuntimeMode
import com.mwombeki.peak.shared.config.PeakRuntimeProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class RuntimeRouteBoundaryInterceptor(
    private val properties: RuntimeRouteBoundaryProperties,
    private val runtimeProperties: PeakRuntimeProperties,
    private val problemWriter: SecurityProblemWriter,
) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (!properties.enabled) {
            return true
        }

        val path = request.requestURI.removePrefix(request.contextPath)
        if (!path.isApiPath()) {
            return true
        }

        val allowed = when (runtimeProperties.mode) {
            PeakRuntimeMode.API -> !path.isPlatformApiPath()
            PeakRuntimeMode.PLATFORM -> path.isPlatformApiPath()
            PeakRuntimeMode.WORKER,
            PeakRuntimeMode.MIGRATION,
            PeakRuntimeMode.BOOTSTRAP,
            -> false
        }

        if (allowed) {
            return true
        }

        problemWriter.write(
            response = response,
            status = HttpStatus.NOT_FOUND,
            title = "Not Found",
            detail = "Resource not found",
        )
        return false
    }

    private fun String.isApiPath(): Boolean =
        this == API_ROOT || startsWith("$API_ROOT/")

    private fun String.isPlatformApiPath(): Boolean =
        PLATFORM_API_PATH.matches(this)

    private companion object {
        const val API_ROOT = "/api"
        val PLATFORM_API_PATH = Regex("^/api/(?:v[1-9][0-9]*/)?platform(?:/.*)?$")
    }
}
