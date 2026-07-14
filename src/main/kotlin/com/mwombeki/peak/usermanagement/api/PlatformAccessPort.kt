package com.mwombeki.peak.usermanagement.api

import java.util.UUID

data class PlatformAccessRequest(
    val tenantId: UUID?,
    val permissionCode: String,
    val operation: String,
    val auditSuccess: Boolean = true,
) {
    init {
        require(permissionCode.isNotBlank()) {
            "Platform access permission is required"
        }
        require(operation.isNotBlank()) {
            "Platform access operation is required"
        }
    }
}

interface PlatformAccessPort {
    fun authorize(request: PlatformAccessRequest): AuthorizationDecision

    fun requireAuthorized(request: PlatformAccessRequest) {
        val decision = authorize(request)
        require(decision.allowed) {
            decision.reason ?: "Platform access is not authorized"
        }
    }
}
