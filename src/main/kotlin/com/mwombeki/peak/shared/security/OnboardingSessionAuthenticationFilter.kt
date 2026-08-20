package com.mwombeki.peak.shared.security

import com.mwombeki.peak.shared.context.OnboardingSessionAuthentication
import com.mwombeki.peak.shared.context.OnboardingSessionLookup
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates an `onb_` bearer minted when an onboarding applicant verifies their phone.
 *
 * Mirrors [OperationalSessionAuthenticationFilter]: the token is looked up by hash through a
 * SECURITY DEFINER function, because the request has no tenant or platform context to bind
 * to yet — an applicant is not a tenant. Invalid or revoked tokens leave the request
 * unauthenticated rather than falling through to header identity.
 */
class OnboardingSessionAuthenticationFilter(
    private val lookup: OnboardingSessionLookup,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = bearerToken(request)
        if (token != null && token.startsWith(OnboardingSessionAuthentication.TOKEN_PREFIX)) {
            val session = lookup.findActive(token)
            if (session != null) {
                SecurityContextHolder.getContext().authentication =
                    OnboardingSessionAuthentication(
                        sessionId = session.sessionId,
                        applicationId = session.applicationId,
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
