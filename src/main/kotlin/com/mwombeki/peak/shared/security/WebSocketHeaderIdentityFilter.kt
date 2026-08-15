package com.mwombeki.peak.shared.security

import com.mwombeki.peak.shared.context.HeaderIdentityAuthentication
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import com.mwombeki.peak.shared.context.RequestContextProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import java.util.UUID
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates WebSocket handshakes from identity headers when header
 * identity is allowed for the runtime (dev/test only, same switch that
 * authorizes header identity for REST). The platform's own POS client and
 * the developer bridge authenticate this way; the handshake interceptor
 * still validates tenant identity on the way in, so this filter only bridges
 * the gap between the servlet security chain and the STOMP session context.
 * Registered in front of the authorization decision by
 * [HttpSecurityConfiguration].
 */
class WebSocketHeaderIdentityFilter(
    private val properties: RequestContextProperties,
) : OncePerRequestFilter() {

    private val wsHandshakePath = "/ws-connect"

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI ?: return true
        val contextPath = request.contextPath
        val relative = if (contextPath.isNotEmpty() && path.startsWith(contextPath)) {
            path.substring(contextPath.length)
        } else path
        return !relative.startsWith(wsHandshakePath)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (properties.allowHeaderIdentity) {
            val authentication = authenticationFromHeaders(request)
            if (authentication != null) {
                SecurityContextHolder.getContext().authentication = authentication
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun authenticationFromHeaders(request: HttpServletRequest): Authentication? {
        val tenantId = request.getHeader(PeakRequestHeaders.TENANT_ID)
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return null
        val userId = request.getHeader(PeakRequestHeaders.TENANT_USER_ID)
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (userId == null) {
            return null
        }
        return HeaderIdentityAuthentication(tenantId, userId)
    }
}