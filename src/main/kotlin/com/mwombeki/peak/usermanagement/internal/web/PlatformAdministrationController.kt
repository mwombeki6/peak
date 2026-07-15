package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.shared.exception.ApiProblemFactory

import com.mwombeki.peak.usermanagement.api.AssignPlatformAdministratorCommand
import com.mwombeki.peak.usermanagement.api.AssignPlatformUserRoleCommand
import com.mwombeki.peak.usermanagement.api.CreatePlatformRoleCommand
import com.mwombeki.peak.usermanagement.api.CreatePlatformUserCommand
import com.mwombeki.peak.usermanagement.api.DeactivatePlatformRoleCommand
import com.mwombeki.peak.usermanagement.api.LinkPlatformOidcIdentityCommand
import com.mwombeki.peak.usermanagement.api.PlatformAdministrationConflictException
import com.mwombeki.peak.usermanagement.api.PlatformAdministrationInProgressException
import com.mwombeki.peak.usermanagement.api.PlatformAdministrationNotFoundException
import com.mwombeki.peak.usermanagement.api.PlatformAdministrationPort
import com.mwombeki.peak.usermanagement.api.PlatformAdministratorSummary
import com.mwombeki.peak.usermanagement.api.PlatformIdentityLinkReceipt
import com.mwombeki.peak.usermanagement.api.PlatformPermissionSummary
import com.mwombeki.peak.usermanagement.api.PlatformRoleMutationReceipt
import com.mwombeki.peak.usermanagement.api.PlatformRoleSummary
import com.mwombeki.peak.usermanagement.api.PlatformUserLifecycleAction
import com.mwombeki.peak.usermanagement.api.PlatformUserLifecycleCommand
import com.mwombeki.peak.usermanagement.api.PlatformUserMutationReceipt
import com.mwombeki.peak.usermanagement.api.PlatformUserRoleMutationReceipt
import com.mwombeki.peak.usermanagement.api.PlatformUserSummary
import com.mwombeki.peak.usermanagement.api.ProvisionTenantAdministratorCommand
import com.mwombeki.peak.usermanagement.api.RevokePlatformAdministratorCommand
import com.mwombeki.peak.usermanagement.api.RevokePlatformOidcIdentityCommand
import com.mwombeki.peak.usermanagement.api.RevokePlatformUserRoleCommand
import com.mwombeki.peak.usermanagement.api.TenantAdministratorProvisioningReceipt
import com.mwombeki.peak.usermanagement.api.TenantProfileVerificationReceipt
import com.mwombeki.peak.usermanagement.api.UpdatePlatformRoleCommand
import com.mwombeki.peak.usermanagement.api.UpdatePlatformUserCommand
import com.mwombeki.peak.usermanagement.api.VerifyTenantBusinessProfileCommand
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import java.net.URI
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/platform")
class PlatformAdministrationController(
    private val platformAdministrationPort: PlatformAdministrationPort,
    private val apiProblemFactory: ApiProblemFactory,
) {
    @GetMapping("/users")
    fun listPlatformUsers(): List<PlatformUserHttpResponse> {
        return platformAdministrationPort.listPlatformUsers()
            .map { it.toHttpResponse() }
    }

    @GetMapping("/users/{platformUserId}")
    fun getPlatformUser(
        @PathVariable platformUserId: UUID,
    ): ResponseEntity<PlatformUserHttpResponse> {
        val user = platformAdministrationPort.getPlatformUser(platformUserId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(user.toHttpResponse())
    }

    @GetMapping("/administrators")
    fun listPlatformAdministrators(): List<PlatformAdministratorHttpResponse> {
        return platformAdministrationPort.listPlatformAdministrators()
            .map { it.toHttpResponse() }
    }

    @PostMapping("/administrators/{platformUserId}/assign")
    fun assignPlatformAdministrator(
        @PathVariable platformUserId: UUID,
    ): PlatformUserRoleMutationHttpResponse {
        return platformAdministrationPort.assignPlatformAdministrator(
            AssignPlatformAdministratorCommand(platformUserId),
        ).toHttpResponse()
    }

    @PostMapping("/administrators/{platformUserId}/revoke")
    fun revokePlatformAdministrator(
        @PathVariable platformUserId: UUID,
    ): PlatformUserRoleMutationHttpResponse {
        return platformAdministrationPort.revokePlatformAdministrator(
            RevokePlatformAdministratorCommand(platformUserId),
        ).toHttpResponse()
    }

    @PostMapping("/users")
    fun createPlatformUser(
        @Valid @RequestBody request: CreatePlatformUserHttpRequest,
    ): ResponseEntity<PlatformUserMutationHttpResponse> {
        val receipt = platformAdministrationPort.createPlatformUser(
            CreatePlatformUserCommand(
                fullName = request.fullName,
                email = request.email,
                status = request.status ?: "invited",
            ),
        )
        return ResponseEntity
            .created(URI.create("/api/v1/platform/users/${receipt.platformUserId}"))
            .body(receipt.toHttpResponse())
    }

    @PutMapping("/users/{platformUserId}")
    fun updatePlatformUser(
        @PathVariable platformUserId: UUID,
        @Valid @RequestBody request: UpdatePlatformUserHttpRequest,
    ): PlatformUserMutationHttpResponse {
        return platformAdministrationPort.updatePlatformUser(
            UpdatePlatformUserCommand(
                platformUserId = platformUserId,
                fullName = request.fullName,
                email = request.email,
            ),
        ).toHttpResponse()
    }

    @PostMapping("/users/{platformUserId}/lock")
    fun lockPlatformUser(
        @PathVariable platformUserId: UUID,
    ): PlatformUserMutationHttpResponse {
        return platformAdministrationPort.changePlatformUserLifecycle(
            PlatformUserLifecycleCommand(platformUserId, PlatformUserLifecycleAction.LOCK),
        ).toHttpResponse()
    }

    @PostMapping("/users/{platformUserId}/disable")
    fun disablePlatformUser(
        @PathVariable platformUserId: UUID,
    ): PlatformUserMutationHttpResponse {
        return platformAdministrationPort.changePlatformUserLifecycle(
            PlatformUserLifecycleCommand(platformUserId, PlatformUserLifecycleAction.DISABLE),
        ).toHttpResponse()
    }

    @PostMapping("/users/{platformUserId}/reactivate")
    fun reactivatePlatformUser(
        @PathVariable platformUserId: UUID,
    ): PlatformUserMutationHttpResponse {
        return platformAdministrationPort.changePlatformUserLifecycle(
            PlatformUserLifecycleCommand(platformUserId, PlatformUserLifecycleAction.REACTIVATE),
        ).toHttpResponse()
    }

    @PostMapping("/users/{platformUserId}/roles/{platformRoleId}/assign")
    fun assignPlatformUserRole(
        @PathVariable platformUserId: UUID,
        @PathVariable platformRoleId: UUID,
    ): PlatformUserRoleMutationHttpResponse {
        return platformAdministrationPort.assignPlatformUserRole(
            AssignPlatformUserRoleCommand(platformUserId, platformRoleId),
        ).toHttpResponse()
    }

    @PostMapping("/users/{platformUserId}/roles/{platformRoleId}/revoke")
    fun revokePlatformUserRole(
        @PathVariable platformUserId: UUID,
        @PathVariable platformRoleId: UUID,
    ): PlatformUserRoleMutationHttpResponse {
        return platformAdministrationPort.revokePlatformUserRole(
            RevokePlatformUserRoleCommand(platformUserId, platformRoleId),
        ).toHttpResponse()
    }

    @PostMapping("/users/{platformUserId}/identity-links")
    fun linkPlatformOidcIdentity(
        @PathVariable platformUserId: UUID,
        @Valid @RequestBody request: LinkPlatformOidcIdentityHttpRequest,
    ): ResponseEntity<PlatformIdentityLinkHttpResponse> {
        val receipt = platformAdministrationPort.linkPlatformOidcIdentity(
            LinkPlatformOidcIdentityCommand(
                platformUserId = platformUserId,
                issuer = request.issuer,
                subject = request.subject,
                email = request.email,
            ),
        )
        return ResponseEntity
            .created(
                URI.create(
                    "/api/v1/platform/users/$platformUserId/identity-links/${receipt.identityLinkId}",
                ),
            )
            .body(receipt.toHttpResponse())
    }

    @PostMapping("/users/{platformUserId}/identity-links/{identityLinkId}/revoke")
    fun revokePlatformOidcIdentity(
        @PathVariable platformUserId: UUID,
        @PathVariable identityLinkId: UUID,
    ): PlatformIdentityLinkHttpResponse {
        return platformAdministrationPort.revokePlatformOidcIdentity(
            RevokePlatformOidcIdentityCommand(platformUserId, identityLinkId),
        ).toHttpResponse()
    }

    @GetMapping("/roles")
    fun listPlatformRoles(): List<PlatformRoleHttpResponse> {
        return platformAdministrationPort.listPlatformRoles()
            .map { it.toHttpResponse() }
    }

    @GetMapping("/roles/{platformRoleId}")
    fun getPlatformRole(
        @PathVariable platformRoleId: UUID,
    ): ResponseEntity<PlatformRoleHttpResponse> {
        val role = platformAdministrationPort.getPlatformRole(platformRoleId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(role.toHttpResponse())
    }

    @PostMapping("/roles")
    fun createPlatformRole(
        @Valid @RequestBody request: CreatePlatformRoleHttpRequest,
    ): ResponseEntity<PlatformRoleMutationHttpResponse> {
        val receipt = platformAdministrationPort.createPlatformRole(
            CreatePlatformRoleCommand(
                code = request.code,
                name = request.name,
                description = request.description,
                permissionCodes = request.permissionCodes,
            ),
        )
        return ResponseEntity
            .created(URI.create("/api/v1/platform/roles/${receipt.platformRoleId}"))
            .body(receipt.toHttpResponse())
    }

    @PutMapping("/roles/{platformRoleId}")
    fun updatePlatformRole(
        @PathVariable platformRoleId: UUID,
        @Valid @RequestBody request: UpdatePlatformRoleHttpRequest,
    ): PlatformRoleMutationHttpResponse {
        return platformAdministrationPort.updatePlatformRole(
            UpdatePlatformRoleCommand(
                platformRoleId = platformRoleId,
                name = request.name,
                description = request.description,
                permissionCodes = request.permissionCodes,
            ),
        ).toHttpResponse()
    }

    @DeleteMapping("/roles/{platformRoleId}")
    fun deactivatePlatformRole(
        @PathVariable platformRoleId: UUID,
    ): PlatformRoleMutationHttpResponse {
        return platformAdministrationPort.deactivatePlatformRole(
            DeactivatePlatformRoleCommand(platformRoleId),
        ).toHttpResponse()
    }

    @GetMapping("/permissions")
    fun listPlatformPermissions(): List<PlatformPermissionHttpResponse> {
        return platformAdministrationPort.listPlatformPermissions()
            .map { it.toHttpResponse() }
    }

    @PostMapping("/tenants/{tenantId}/administrators")
    fun provisionTenantAdministrator(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: ProvisionTenantAdministratorHttpRequest,
    ): ResponseEntity<TenantAdministratorProvisioningHttpResponse> {
        val receipt = platformAdministrationPort.provisionTenantAdministrator(
            ProvisionTenantAdministratorCommand(
                tenantId = tenantId,
                fullName = request.fullName,
                email = request.email,
                issuer = request.issuer,
                subject = request.subject,
            ),
        )
        return ResponseEntity
            .created(URI.create("/api/v1/tenants/$tenantId/users/${receipt.tenantUserId}"))
            .body(receipt.toHttpResponse())
    }

    @PostMapping("/tenants/{tenantId}/profile/verify")
    fun verifyTenantBusinessProfile(
        @PathVariable tenantId: UUID,
    ): TenantProfileVerificationHttpResponse {
        return platformAdministrationPort.verifyTenantBusinessProfile(
            VerifyTenantBusinessProfileCommand(tenantId),
        ).toHttpResponse()
    }

    @ExceptionHandler(PlatformAdministrationNotFoundException::class)
    fun handleNotFound(
        ex: PlatformAdministrationNotFoundException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.NOT_FOUND, "Platform administration target not found", ex.publicMessage())
    }

    @ExceptionHandler(PlatformAdministrationConflictException::class)
    fun handleConflict(
        ex: PlatformAdministrationConflictException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Platform administration conflict", ex.publicMessage())
    }

    @ExceptionHandler(PlatformAdministrationInProgressException::class)
    fun handleInProgress(
        ex: PlatformAdministrationInProgressException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Platform administration command in progress", ex.publicMessage())
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidRequest(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.BAD_REQUEST, "Invalid platform administration request", ex.publicMessage())
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        return apiProblemFactory.response(status, title, detail)
    }

    private fun RuntimeException.publicMessage(): String {
        val message = message.orEmpty()
        return if (message.startsWith("ERROR:")) {
            message.removePrefix("ERROR:").lineSequence().first().trim()
        } else {
            message
        }
    }

    private fun PlatformUserSummary.toHttpResponse(): PlatformUserHttpResponse {
        return PlatformUserHttpResponse(
            platformUserId = platformUserId,
            fullName = fullName,
            email = email,
            status = status,
            lockedUntil = lockedUntil,
            roleCodes = roleCodes,
            activeIdentityLinks = activeIdentityLinks,
        )
    }

    private fun PlatformAdministratorSummary.toHttpResponse(): PlatformAdministratorHttpResponse {
        return PlatformAdministratorHttpResponse(
            platformUserId = platformUserId,
            platformRoleId = platformRoleId,
            fullName = fullName,
            email = email,
            status = status,
            lockedUntil = lockedUntil,
            activeIdentityLinks = activeIdentityLinks,
            effective = effective,
        )
    }

    private fun PlatformRoleSummary.toHttpResponse(): PlatformRoleHttpResponse {
        return PlatformRoleHttpResponse(
            platformRoleId = platformRoleId,
            code = code,
            name = name,
            description = description,
            isSystem = isSystem,
            isActive = isActive,
            permissionCodes = permissionCodes,
            scope = "PLATFORM",
        )
    }

    private fun PlatformPermissionSummary.toHttpResponse(): PlatformPermissionHttpResponse {
        return PlatformPermissionHttpResponse(
            platformPermissionId = platformPermissionId,
            code = code,
            namespace = namespace,
            description = description,
        )
    }

    private fun PlatformUserMutationReceipt.toHttpResponse(): PlatformUserMutationHttpResponse {
        return PlatformUserMutationHttpResponse(
            platformUserId = platformUserId,
            status = status,
            changed = changed,
            replayed = replayed,
        )
    }

    private fun PlatformRoleMutationReceipt.toHttpResponse(): PlatformRoleMutationHttpResponse {
        return PlatformRoleMutationHttpResponse(
            platformRoleId = platformRoleId,
            isActive = isActive,
            changed = changed,
            replayed = replayed,
        )
    }

    private fun PlatformUserRoleMutationReceipt.toHttpResponse():
            PlatformUserRoleMutationHttpResponse {
        return PlatformUserRoleMutationHttpResponse(
            platformUserId = platformUserId,
            platformRoleId = platformRoleId,
            assigned = assigned,
            changed = changed,
            replayed = replayed,
        )
    }

    private fun PlatformIdentityLinkReceipt.toHttpResponse(): PlatformIdentityLinkHttpResponse {
        return PlatformIdentityLinkHttpResponse(
            platformUserId = platformUserId,
            identityLinkId = identityLinkId,
            revokedAt = revokedAt,
            changed = changed,
            replayed = replayed,
        )
    }

    private fun TenantAdministratorProvisioningReceipt.toHttpResponse():
            TenantAdministratorProvisioningHttpResponse {
        return TenantAdministratorProvisioningHttpResponse(
            tenantId = tenantId,
            tenantUserId = tenantUserId,
            tenantRoleId = tenantRoleId,
            identityLinkId = identityLinkId,
            changed = changed,
            replayed = replayed,
        )
    }

    private fun TenantProfileVerificationReceipt.toHttpResponse():
            TenantProfileVerificationHttpResponse {
        return TenantProfileVerificationHttpResponse(
            tenantId = tenantId,
            verificationStatus = verificationStatus,
            changed = changed,
            replayed = replayed,
        )
    }
}

data class CreatePlatformUserHttpRequest(
    @field:NotBlank
    val fullName: String,
    @field:NotBlank
    @field:Email
    val email: String,
    val status: String? = null,
)

data class UpdatePlatformUserHttpRequest(
    val fullName: String? = null,
    @field:Email
    val email: String? = null,
)

data class CreatePlatformRoleHttpRequest(
    @field:NotBlank
    val code: String,
    @field:NotBlank
    val name: String,
    val description: String? = null,
    @field:NotEmpty
    val permissionCodes: List<String>,
)

data class UpdatePlatformRoleHttpRequest(
    val name: String? = null,
    val description: String? = null,
    val permissionCodes: List<String>? = null,
)

data class LinkPlatformOidcIdentityHttpRequest(
    @field:NotBlank
    val issuer: String,
    @field:NotBlank
    val subject: String,
    @field:Email
    val email: String? = null,
)

data class ProvisionTenantAdministratorHttpRequest(
    @field:NotBlank
    val fullName: String,
    @field:NotBlank
    @field:Email
    val email: String,
    @field:NotBlank
    val issuer: String,
    @field:NotBlank
    val subject: String,
)

data class PlatformUserHttpResponse(
    val platformUserId: UUID,
    val fullName: String,
    val email: String,
    val status: String,
    val lockedUntil: Instant?,
    val roleCodes: List<String>,
    val activeIdentityLinks: Int,
)

data class PlatformAdministratorHttpResponse(
    val platformUserId: UUID,
    val platformRoleId: UUID,
    val fullName: String,
    val email: String,
    val status: String,
    val lockedUntil: Instant?,
    val activeIdentityLinks: Int,
    val effective: Boolean,
)

data class PlatformRoleHttpResponse(
    val platformRoleId: UUID,
    val code: String,
    val name: String,
    val description: String?,
    val isSystem: Boolean,
    val isActive: Boolean,
    val permissionCodes: List<String>,
    val scope: String,
)

data class PlatformPermissionHttpResponse(
    val platformPermissionId: UUID,
    val code: String,
    val namespace: String,
    val description: String?,
)

data class PlatformUserMutationHttpResponse(
    val platformUserId: UUID,
    val status: String,
    val changed: Boolean,
    val replayed: Boolean,
)

data class PlatformRoleMutationHttpResponse(
    val platformRoleId: UUID,
    val isActive: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)

data class PlatformUserRoleMutationHttpResponse(
    val platformUserId: UUID,
    val platformRoleId: UUID,
    val assigned: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)

data class PlatformIdentityLinkHttpResponse(
    val platformUserId: UUID,
    val identityLinkId: UUID,
    val revokedAt: Instant?,
    val changed: Boolean,
    val replayed: Boolean,
)

data class TenantAdministratorProvisioningHttpResponse(
    val tenantId: UUID,
    val tenantUserId: UUID,
    val tenantRoleId: UUID,
    val identityLinkId: UUID,
    val changed: Boolean,
    val replayed: Boolean,
)

data class TenantProfileVerificationHttpResponse(
    val tenantId: UUID,
    val verificationStatus: String,
    val changed: Boolean,
    val replayed: Boolean,
)
