package com.mwombeki.peak.tenantmanagement.api

import java.util.UUID

interface TenantAdministrationPort {
    fun listTenantModules(tenantId: UUID): List<TenantModuleSummary>
    fun enableTenantModule(command: TenantModuleCommand): TenantModuleMutationReceipt
    fun disableTenantModule(command: TenantModuleCommand): TenantModuleMutationReceipt
    fun getTenantReadiness(tenantId: UUID): TenantReadinessResponse
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
