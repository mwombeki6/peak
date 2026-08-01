package com.mwombeki.peak.tenantmanagement.api

import java.time.Instant
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface PlatformTenantActivationPort {
    fun readiness(tenantId: UUID): TenantActivationReadiness
    fun requireReady(tenantId: UUID): TenantActivationReadiness
}

data class TenantActivationReadiness(
    val tenantId: UUID,
    val ready: Boolean,
    val lifecycleStatus: String,
    val administratorStatus: String,
    val effectiveAdministrators: Int,
    val pendingInitialInvitations: Int,
    val gates: List<TenantActivationGate>,
    val blockerCodes: List<String>,
    val nextActions: List<TenantActivationAction>,
    val evaluatedAt: Instant,
)

data class TenantActivationGate(
    val code: String,
    val label: String,
    val satisfied: Boolean,
    val detail: String,
)

data class TenantActivationAction(
    val code: String,
    val label: String,
    val responsibleParty: String,
    val method: String?,
    val path: String?,
)

class TenantActivationNotReadyException(
    val tenantId: UUID,
    val blockerCodes: List<String>,
) : IllegalStateException(
    "Tenant activation is blocked by incomplete onboarding requirements",
)
