package com.mwombeki.peak.shared.security

import com.mwombeki.peak.shared.context.OperationalSessionAuthentication
import com.mwombeki.peak.shared.context.OperationalSessionLookup
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates an `ops_` bearer minted by PIN login on a registered device.
 *
 * Runs for REST and for the WebSocket upgrade. The token is looked up by hash
 * through a SECURITY DEFINER function, because the request has no tenant bound
 * yet. Invalid or revoked tokens leave the request unauthenticated rather than
 * falling through to header identity.
 */
class OperationalSessionAuthenticationFilter(
    private val lookup: OperationalSessionLookup,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = bearerToken(request)
        if (token != null && token.startsWith(OperationalSessionAuthentication.TOKEN_PREFIX)) {
            val session = lookup.findActive(token)
            if (session != null) {
                SecurityContextHolder.getContext().authentication =
                    OperationalSessionAuthentication(
                        sessionId = session.sessionId,
                        tenantId = session.tenantId,
                        tenantUserId = session.tenantUserId,
                        deviceId = session.deviceId,
                        propertyId = session.propertyId,
                    )
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun bearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)?.trim() ?: return null
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) {
            return null
        }
        return header.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
