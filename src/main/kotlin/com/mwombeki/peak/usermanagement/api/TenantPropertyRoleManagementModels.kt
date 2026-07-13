package com.mwombeki.peak.usermanagement.api

import java.time.Instant
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface TenantPropertyRoleManagementPort {
    fun listPropertyRoles(query: ListPropertyRolesQuery): List<PropertyRoleSummary>

    fun getPropertyRole(query: GetPropertyRoleQuery): PropertyRoleSummary?

    fun createPropertyRole(command: CreatePropertyRoleCommand): PropertyRoleMutationReceipt

    fun updatePropertyRole(command: UpdatePropertyRoleCommand): PropertyRoleMutationReceipt

    fun deactivatePropertyRole(command: DeactivatePropertyRoleCommand): PropertyRoleMutationReceipt

    fun listUserPropertyRoles(query: ListUserPropertyRolesQuery): List<PropertyRoleSummary>

    fun listPropertyAdministrators(
        query: ListPropertyAdministratorsQuery,
    ): List<PropertyAdministratorSummary>

    fun assignPropertyUserRole(command: AssignPropertyUserRoleCommand): PropertyUserRoleAssignmentReceipt

    fun revokePropertyUserRole(command: RevokePropertyUserRoleCommand): PropertyUserRoleAssignmentReceipt

    fun assignPropertyAdministrator(
        command: AssignPropertyAdministratorCommand,
    ): PropertyUserRoleAssignmentReceipt

    fun revokePropertyAdministrator(
        command: RevokePropertyAdministratorCommand,
    ): PropertyUserRoleAssignmentReceipt
}

@NamedInterface("api")
interface PropertyAccessBootstrapPort {
    fun ensurePropertyAdministrator(command: EnsurePropertyAdministratorCommand): PropertyAccessBootstrapReceipt
}

data class ListPropertyRolesQuery(
    val tenantId: UUID,
    val propertyId: UUID,
)

data class GetPropertyRoleQuery(
    val tenantId: UUID,
    val propertyId: UUID,
    val propertyRoleId: UUID,
)

data class CreatePropertyRoleCommand(
    val tenantId: UUID,
    val propertyId: UUID,
    val name: String,
    val permissionCodes: List<String>,
)

data class UpdatePropertyRoleCommand(
    val tenantId: UUID,
    val propertyId: UUID,
    val propertyRoleId: UUID,
    val name: String?,
    val permissionCodes: List<String>?,
)

data class DeactivatePropertyRoleCommand(
    val tenantId: UUID,
    val propertyId: UUID,
    val propertyRoleId: UUID,
)

data class ListUserPropertyRolesQuery(
    val tenantId: UUID,
    val propertyId: UUID,
    val userId: UUID,
)

data class AssignPropertyUserRoleCommand(
    val tenantId: UUID,
    val propertyId: UUID,
    val userId: UUID,
    val propertyRoleId: UUID,
)

data class RevokePropertyUserRoleCommand(
    val tenantId: UUID,
    val propertyId: UUID,
    val userId: UUID,
    val propertyRoleId: UUID,
)

data class ListPropertyAdministratorsQuery(
    val tenantId: UUID,
    val propertyId: UUID,
)

data class AssignPropertyAdministratorCommand(
    val tenantId: UUID,
    val propertyId: UUID,
    val userId: UUID,
)

data class RevokePropertyAdministratorCommand(
    val tenantId: UUID,
    val propertyId: UUID,
    val userId: UUID,
)

data class EnsurePropertyAdministratorCommand(
    val tenantId: UUID,
    val propertyId: UUID,
    val tenantUserId: UUID,
)

data class PropertyRoleSummary(
    val propertyRoleId: UUID,
    val tenantId: UUID,
    val propertyId: UUID,
    val name: String,
    val isSystem: Boolean,
    val isActive: Boolean,
    val permissionCodes: List<String>,
    val scope: String = "PROPERTY",
)

data class PropertyRoleMutationReceipt(
    val tenantId: UUID,
    val propertyId: UUID,
    val propertyRoleId: UUID,
    val isActive: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)

data class PropertyUserRoleAssignmentReceipt(
    val tenantId: UUID,
    val propertyId: UUID,
    val userId: UUID,
    val propertyRoleId: UUID,
    val assigned: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)

data class PropertyAccessBootstrapReceipt(
    val tenantId: UUID,
    val propertyId: UUID,
    val tenantUserId: UUID,
    val propertyRoleId: UUID,
    val changed: Boolean,
)

data class PropertyAdministratorSummary(
    val tenantId: UUID,
    val propertyId: UUID,
    val propertyRoleId: UUID,
    val userId: UUID,
    val fullName: String,
    val email: String,
    val status: String,
    val isActive: Boolean,
    val lockedUntil: Instant?,
    val hasActiveIdentity: Boolean,
)
