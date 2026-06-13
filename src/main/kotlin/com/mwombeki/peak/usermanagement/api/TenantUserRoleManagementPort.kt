package com.mwombeki.peak.usermanagement.api

interface TenantUserRoleManagementPort {
    fun listTenantRoles(query: ListTenantRolesQuery): List<TenantRoleSummary>

    fun listTenantPermissions(query: ListTenantPermissionsQuery): List<TenantPermissionSummary>

    fun assignTenantUserRole(command: AssignTenantUserRoleCommand): TenantUserRoleAssignmentReceipt

    fun revokeTenantUserRole(command: RevokeTenantUserRoleCommand): TenantUserRoleAssignmentReceipt
}
