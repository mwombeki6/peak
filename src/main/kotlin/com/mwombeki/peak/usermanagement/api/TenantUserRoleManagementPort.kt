package com.mwombeki.peak.usermanagement.api

interface TenantUserRoleManagementPort {
    fun listTenantRoles(query: ListTenantRolesQuery): List<TenantRoleSummary>

    fun getTenantRole(query: GetTenantRoleQuery): TenantRoleSummary?

    fun listTenantPermissions(query: ListTenantPermissionsQuery): List<TenantPermissionSummary>

    fun createTenantRole(command: CreateTenantRoleCommand): TenantRoleMutationReceipt

    fun updateTenantRole(command: UpdateTenantRoleCommand): TenantRoleMutationReceipt

    fun deactivateTenantRole(command: DeactivateTenantRoleCommand): TenantRoleMutationReceipt

    fun assignTenantUserRole(command: AssignTenantUserRoleCommand): TenantUserRoleAssignmentReceipt

    fun revokeTenantUserRole(command: RevokeTenantUserRoleCommand): TenantUserRoleAssignmentReceipt
}
