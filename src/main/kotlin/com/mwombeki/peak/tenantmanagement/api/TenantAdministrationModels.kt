package com.mwombeki.peak.tenantmanagement.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

interface TenantAdministrationPort {
    fun listTenantModules(tenantId: UUID): List<TenantModuleSummary>
    fun enableTenantModule(command: TenantModuleCommand): TenantModuleMutationReceipt
    fun disableTenantModule(command: TenantModuleCommand): TenantModuleMutationReceipt
    fun getTenantReadiness(tenantId: UUID): TenantReadinessResponse
    fun getTenantOnboarding(tenantId: UUID): TenantOnboardingResponse
}

data class TenantModuleCommand(
    val tenantId: UUID,
    val moduleId: String,
)

data class TenantModuleSummary(
    val tenantId: UUID,
    val moduleId: String,
    val name: String,
    val isEnabled: Boolean,
    val isConfigured: Boolean,
    val source: String,
)

data class TenantModuleMutationReceipt(
    val tenantId: UUID,
    val moduleId: String,
    val enabled: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)

data class TenantReadinessResponse(
    val tenantId: UUID,
    val isReady: Boolean,
    val missingRequirements: List<String>,
)

sealed class TenantAdministrationException(message: String) : RuntimeException(message)

class TenantAdministrationNotFoundException(
    message: String,
) : TenantAdministrationException(message)

class TenantAdministrationConflictException(
    message: String,
) : TenantAdministrationException(message)

class TenantAdministrationInProgressException(
    message: String,
) : TenantAdministrationException(message)

/**
 * Owner-side contract for system-driven tenant lifecycle changes.
 *
 * Callers retain responsibility for authorization, idempotency, audit, and
 * outbox publication. This port owns only the serialized tenant-table
 * transition and its canonical lifecycle row.
 */
@NamedInterface("api")
interface TenantLifecycleMutationPort {
    fun transition(command: TenantLifecycleTransitionCommand): TenantLifecycleTransition
}

@NamedInterface("api")
data class TenantLifecycleTransitionCommand(
    val tenantId: UUID,
    val operatorId: UUID,
    val allowedCurrentStatuses: Set<String>,
    val newStatus: String,
    val eventType: String,
    val reason: String,
    val requireActivationReadiness: Boolean = false,
)

@NamedInterface("api")
data class TenantLifecycleTransition(
    val tenantId: UUID,
    val previousStatus: String,
    val newStatus: String,
)

/** Owner-side access to the tenant-level module record. */
@NamedInterface("api")
interface TenantModuleConfigurationPort {
    fun enableConfiguredModule(command: ConfigureTenantModuleCommand)
    fun isEnabled(tenantId: UUID, moduleId: String): Boolean
}

@NamedInterface("api")
data class ConfigureTenantModuleCommand(
    val tenantId: UUID,
    val moduleId: String,
    val source: String,
)
