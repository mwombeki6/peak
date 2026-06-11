package com.mwombeki.peak.usermanagement.api

interface AuthorizationPort {
    fun authorize(request: RouteAuthorizationRequest): AuthorizationDecision

    fun requireAuthorized(request: RouteAuthorizationRequest) {
        val decision = authorize(request)
        if (!decision.allowed) {
            throw AuthorizationDeniedException(
                decision.reason ?: "Request is not authorized",
            )
        }
    }
}
