package com.mwombeki.peak.usermanagement.api

import java.util.UUID

data class TenantPermissionAccessRequest(
    val tenantId: UUID,
    val permissionCode: String,
    val denialMessage: String = "Tenant user lacks required permission",
) {
    init {
        require(permissionCode.isNotBlank()) {
            "Tenant access permission is required"
        }
        require(denialMessage.isNotBlank()) {
            "Tenant access denial message is required"
        }
    }
}

interface TenantPermissionAccessPort {
    fun requireAuthorized(request: TenantPermissionAccessRequest): UUID
}
