package com.mwombeki.peak.usermanagement.api

import java.time.Instant
import java.util.UUID

interface PlatformAdministrationPort {
    fun listPlatformUsers(): List<PlatformUserSummary>
    fun getPlatformUser(platformUserId: UUID): PlatformUserSummary?
    fun createPlatformUser(command: CreatePlatformUserCommand): PlatformUserMutationReceipt
    fun updatePlatformUser(command: UpdatePlatformUserCommand): PlatformUserMutationReceipt
    fun changePlatformUserLifecycle(command: PlatformUserLifecycleCommand): PlatformUserMutationReceipt
    fun listPlatformRoles(): List<PlatformRoleSummary>
    fun getPlatformRole(platformRoleId: UUID): PlatformRoleSummary?
    fun createPlatformRole(command: CreatePlatformRoleCommand): PlatformRoleMutationReceipt
    fun updatePlatformRole(command: UpdatePlatformRoleCommand): PlatformRoleMutationReceipt
    fun deactivatePlatformRole(command: DeactivatePlatformRoleCommand): PlatformRoleMutationReceipt
    fun assignPlatformUserRole(command: AssignPlatformUserRoleCommand): PlatformUserRoleMutationReceipt
    fun revokePlatformUserRole(command: RevokePlatformUserRoleCommand): PlatformUserRoleMutationReceipt
    fun listPlatformPermissions(): List<PlatformPermissionSummary>
    fun linkPlatformOidcIdentity(command: LinkPlatformOidcIdentityCommand): PlatformIdentityLinkReceipt
    fun revokePlatformOidcIdentity(command: RevokePlatformOidcIdentityCommand): PlatformIdentityLinkReceipt
}

data class CreatePlatformUserCommand(
    val fullName: String,
    val email: String,
    val status: String = "invited",
)

data class UpdatePlatformUserCommand(
    val platformUserId: UUID,
    val fullName: String?,
    val email: String?,
)

data class PlatformUserLifecycleCommand(
    val platformUserId: UUID,
    val action: PlatformUserLifecycleAction,
)

enum class PlatformUserLifecycleAction(val databaseValue: String) {
    LOCK("locked"),
    DISABLE("disabled"),
    REACTIVATE("active"),
}

data class CreatePlatformRoleCommand(
    val code: String,
    val name: String,
    val description: String?,
    val permissionCodes: List<String>,
)

data class UpdatePlatformRoleCommand(
    val platformRoleId: UUID,
    val name: String?,
    val description: String?,
    val permissionCodes: List<String>?,
)

data class DeactivatePlatformRoleCommand(
    val platformRoleId: UUID,
)

data class AssignPlatformUserRoleCommand(
    val platformUserId: UUID,
    val platformRoleId: UUID,
)

data class RevokePlatformUserRoleCommand(
    val platformUserId: UUID,
    val platformRoleId: UUID,
)

data class LinkPlatformOidcIdentityCommand(
    val platformUserId: UUID,
    val issuer: String,
    val subject: String,
    val email: String?,
)

data class RevokePlatformOidcIdentityCommand(
    val platformUserId: UUID,
    val identityLinkId: UUID,
)

data class PlatformUserSummary(
    val platformUserId: UUID,
    val fullName: String,
    val email: String,
    val status: String,
    val lockedUntil: Instant?,
    val roleCodes: List<String>,
    val activeIdentityLinks: Int,
)

data class PlatformRoleSummary(
    val platformRoleId: UUID,
    val code: String,
    val name: String,
    val description: String?,
    val isSystem: Boolean,
    val isActive: Boolean,
    val permissionCodes: List<String>,
)

data class PlatformPermissionSummary(
    val platformPermissionId: UUID,
    val code: String,
    val namespace: String,
    val description: String?,
)

data class PlatformUserMutationReceipt(
    val platformUserId: UUID,
    val status: String,
    val changed: Boolean,
    val replayed: Boolean,
)

data class PlatformRoleMutationReceipt(
    val platformRoleId: UUID,
    val isActive: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)

data class PlatformUserRoleMutationReceipt(
    val platformUserId: UUID,
    val platformRoleId: UUID,
    val assigned: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)

data class PlatformIdentityLinkReceipt(
    val platformUserId: UUID,
    val identityLinkId: UUID,
    val revokedAt: Instant?,
    val changed: Boolean,
    val replayed: Boolean,
)

sealed class PlatformAdministrationException(message: String) : RuntimeException(message)

class PlatformAdministrationNotFoundException(
    message: String,
) : PlatformAdministrationException(message)

class PlatformAdministrationConflictException(
    message: String,
) : PlatformAdministrationException(message)

class PlatformAdministrationInProgressException(
    message: String,
) : PlatformAdministrationException(message)
