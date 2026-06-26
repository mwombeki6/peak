package com.mwombeki.peak.usermanagement.api

import java.util.UUID

data class ListTenantRolesQuery(
    val tenantId: UUID,
)

data class ListTenantPermissionsQuery(
    val tenantId: UUID,
)

data class GetTenantRoleQuery(
    val tenantId: UUID,
    val tenantRoleId: UUID,
)

data class CreateTenantRoleCommand(
    val tenantId: UUID,
    val code: String,
    val name: String,
    val description: String?,
    val permissionCodes: List<String>,
)

data class UpdateTenantRoleCommand(
    val tenantId: UUID,
    val tenantRoleId: UUID,
    val name: String?,
    val description: String?,
    val permissionCodes: List<String>?,
)

data class DeactivateTenantRoleCommand(
    val tenantId: UUID,
    val tenantRoleId: UUID,
)

data class AssignTenantUserRoleCommand(
    val tenantId: UUID,
    val userId: UUID,
    val tenantRoleId: UUID,
)

data class RevokeTenantUserRoleCommand(
    val tenantId: UUID,
    val userId: UUID,
    val tenantRoleId: UUID,
)

data class TenantRoleSummary(
    val tenantRoleId: UUID,
    val tenantId: UUID,
    val code: String,
    val name: String,
    val description: String?,
    val isSystem: Boolean,
    val isActive: Boolean,
    val permissionCodes: List<String>,
)

data class TenantPermissionSummary(
    val permissionId: UUID,
    val tenantId: UUID,
    val code: String,
    val description: String?,
)

data class TenantUserRoleAssignmentReceipt(
    val tenantId: UUID,
    val userId: UUID,
    val tenantRoleId: UUID,
    val assigned: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)

data class TenantRoleMutationReceipt(
    val tenantId: UUID,
    val tenantRoleId: UUID,
    val isActive: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)

sealed class TenantUserRoleManagementException(message: String) : RuntimeException(message)

class TenantUserRoleManagementNotFoundException(
    message: String,
) : TenantUserRoleManagementException(message)

class TenantUserRoleManagementConflictException(
    message: String,
) : TenantUserRoleManagementException(message)

class TenantUserRoleManagementInProgressException(
    message: String,
) : TenantUserRoleManagementException(message)
