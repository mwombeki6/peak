package com.mwombeki.peak.usermanagement.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

/**
 * Aggregate administrator evidence for platform onboarding.
 *
 * The contract deliberately exposes counts and state only; callers cannot use
 * it to enumerate tenant identities or acquire tenant authority.
 */
@NamedInterface("api")
interface TenantAdministratorReadinessPort {
    fun readiness(tenantId: UUID): TenantAdministratorReadiness
}

data class TenantAdministratorReadiness(
    val tenantId: UUID,
    val effectiveAdministrators: Int,
    val pendingInitialInvitations: Int,
    val status: String,
)
