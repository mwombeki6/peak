package com.mwombeki.peak.platformgovernance.api

import java.util.UUID

interface TenantGovernancePort {
    fun approveTenant(tenantId: UUID, operatorId: UUID, reason: String):GovernanceActionResponse

    fun suspendTenant(tenantId: UUID, operatorId: UUID, reason: String):GovernanceActionResponse
}

data class GovernanceActionResponse(
    val tenantId: UUID,
    val previousStatus: String,
    val newStatus: String,
    val message: String
)