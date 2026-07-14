package com.mwombeki.peak.usermanagement.api

import java.util.UUID

data class SupportTenantAccessRequest(
    val tenantId: UUID?,
    val permissionCode: String,
    val operation: String,
    val auditSuccess: Boolean = true,
) {
    init {
        require(permissionCode.isNotBlank()) {
            "Support access permission is required"
        }
        require(operation.isNotBlank()) {
            "Support access operation is required"
        }
    }
}

interface SupportTenantAccessPort {
    fun authorize(request: SupportTenantAccessRequest): AuthorizationDecision

    fun requireAuthorized(request: SupportTenantAccessRequest) {
        val decision = authorize(request)
        require(decision.allowed) {
            decision.reason ?: "Support tenant access is not authorized"
        }
    }
}
