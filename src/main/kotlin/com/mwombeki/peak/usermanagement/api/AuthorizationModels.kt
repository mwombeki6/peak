package com.mwombeki.peak.usermanagement.api

import java.util.UUID

data class RouteAuthorizationRequest(
    val moduleId: String,
    val guardMode: GuardMode,
    val routeScope: RouteScope,
    val permissionCode: String? = null,
    val tenantId: UUID? = null,
    val propertyId: UUID? = null,
) {
    init {
        require(moduleId.isNotBlank()) {
            "Authorization module id is required"
        }
    }
}

data class AuthorizationDecision(
    val allowed: Boolean,
    val reason: String? = null,
) {
    companion object {
        fun allowed(): AuthorizationDecision = AuthorizationDecision(true)

        fun denied(reason: String): AuthorizationDecision = AuthorizationDecision(false, reason)
    }
}

enum class GuardMode {
    STAFF_PERMISSION,
    MODULE_ONLY,
    PLATFORM_PERMISSION,
}

enum class RouteScope {
    TENANT,
    PROPERTY,
    PUBLIC_PROPERTY,
    PLATFORM,
}

class AuthorizationDeniedException(
    message: String,
) : RuntimeException(message)
