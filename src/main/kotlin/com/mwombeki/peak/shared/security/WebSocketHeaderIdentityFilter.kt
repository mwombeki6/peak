package com.mwombeki.peak.shared.security

import com.mwombeki.peak.shared.context.HeaderIdentityAuthentication
import com.mwombeki.peak.shared.context.OperationalSessionAuthentication
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import com.mwombeki.peak.shared.context.RequestContextProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates WebSocket handshakes from identity headers when header
 * identity is allowed for the runtime (dev/test only, same switch that
 * authorizes header identity for REST). An `ops_` bearer and identity
 * headers are exclusive: presenting both clears the security context so the
 * handshake fails rather than preferring one credential over the other.
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
        val existing = SecurityContextHolder.getContext().authentication
        val hasIdentityHeaders = hasTenantIdentityHeaders(request)
        val bearerAuthenticated =
            bearerIsOperational(request) ||
                (
                    existing != null &&
                        existing.isAuthenticated &&
                        existing !is HeaderIdentityAuthentication &&
                        // An anonymous token reports isAuthenticated == true and is not a
                        // credential. This filter is registered before AuthorizationFilter,
                        // which puts it after AnonymousAuthenticationFilter, so by the time
                        // it runs the context is never empty. Counting anonymous as a bearer
                        // made the XOR rule fire against a request that presented exactly one
                        // credential: the context was cleared and every handshake answered 401.
                        existing !is AnonymousAuthenticationToken
                    )

        if (bearerAuthenticated && hasIdentityHeaders) {
            SecurityContextHolder.clearContext()
            filterChain.doFilter(request, response)
            return
        }

        if (properties.allowHeaderIdentity && !bearerAuthenticated) {
            val authentication = authenticationFromHeaders(request)
            if (authentication != null) {
                SecurityContextHolder.getContext().authentication = authentication
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun hasTenantIdentityHeaders(request: HttpServletRequest): Boolean {
        return !request.getHeader(PeakRequestHeaders.TENANT_ID).isNullOrBlank() ||
            !request.getHeader(PeakRequestHeaders.TENANT_USER_ID).isNullOrBlank()
    }

    private fun bearerIsOperational(request: HttpServletRequest): Boolean {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)?.trim()
            ?: return false
        return header.startsWith("Bearer ${OperationalSessionAuthentication.TOKEN_PREFIX}", ignoreCase = true)
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