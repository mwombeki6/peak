package com.mwombeki.peak.platformgovernance.api

import java.time.Instant
import java.util.UUID

data class PlatformOperator(
    val id: UUID,
    val keycloakId: String,
    val email: String,
    val fullName: String,
    val role: OperatorRole,
    val isActive: Boolean,
)

enum class OperatorRole {
    SUPER_ADMIN,
    OPERATOR,
    SUPPORT
}

data class TenantLifeCycleLog(
    val id: UUID,
    val tenantId: UUID,
    val operatorId: UUID,
    val previousStatus: String,
    val newStatus: String,
    val reason: String,
    val createdAt: Instant
)