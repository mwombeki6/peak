package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.shared.exception.ApiProblemFactory

import com.mwombeki.peak.usermanagement.api.AssignTenantAdministratorCommand
import com.mwombeki.peak.usermanagement.api.AssignTenantUserRoleCommand
import com.mwombeki.peak.usermanagement.api.CreateTenantRoleCommand
import com.mwombeki.peak.usermanagement.api.DeactivateTenantRoleCommand
import com.mwombeki.peak.usermanagement.api.GetTenantRoleQuery
import com.mwombeki.peak.usermanagement.api.ListTenantAdministratorsQuery
import com.mwombeki.peak.usermanagement.api.ListTenantPermissionsQuery
import com.mwombeki.peak.usermanagement.api.ListTenantRolesQuery
import com.mwombeki.peak.usermanagement.api.RevokeTenantAdministratorCommand
import com.mwombeki.peak.usermanagement.api.RevokeTenantUserRoleCommand
import com.mwombeki.peak.usermanagement.api.TenantAdministratorSummary
import com.mwombeki.peak.usermanagement.api.TenantPermissionSummary
import com.mwombeki.peak.usermanagement.api.TenantRoleMutationReceipt
import com.mwombeki.peak.usermanagement.api.TenantRoleSummary
import com.mwombeki.peak.usermanagement.api.TenantUserRoleAssignmentReceipt
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementConflictException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementInProgressException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementNotFoundException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementPort
import com.mwombeki.peak.usermanagement.api.UpdateTenantRoleCommand
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
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
@RequestMapping("/api/v1")
class TenantUserRoleManagementController(
    private val roleManagementPort: TenantUserRoleManagementPort,
    private val apiProblemFactory: ApiProblemFactory,
) {
    @GetMapping("/tenants/{tenantId}/roles")
    fun listTenantRoles(
        @PathVariable tenantId: UUID,
    ): List<TenantRoleHttpResponse> {
        return roleManagementPort.listTenantRoles(
            ListTenantRolesQuery(tenantId),
        ).map { it.toHttpResponse() }
    }

    @GetMapping("/tenants/{tenantId}/roles/{tenantRoleId}")
    fun getTenantRole(
        @PathVariable tenantId: UUID,
        @PathVariable tenantRoleId: UUID,
    ): ResponseEntity<TenantRoleHttpResponse> {
        val role = roleManagementPort.getTenantRole(
            GetTenantRoleQuery(tenantId, tenantRoleId),
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(role.toHttpResponse())
    }

    @GetMapping("/tenants/{tenantId}/permissions")
    fun listTenantPermissions(
        @PathVariable tenantId: UUID,
    ): List<TenantPermissionHttpResponse> {
        return roleManagementPort.listTenantPermissions(
            ListTenantPermissionsQuery(tenantId),
        ).map { it.toHttpResponse() }
    }

    @GetMapping("/tenants/{tenantId}/administrators")
    fun listTenantAdministrators(
        @PathVariable tenantId: UUID,
    ): List<TenantAdministratorHttpResponse> {
        return roleManagementPort.listTenantAdministrators(
            ListTenantAdministratorsQuery(tenantId),
        ).map { it.toHttpResponse() }
    }

    @PostMapping("/tenants/{tenantId}/administrators/{userId}/assign")
    fun assignTenantAdministrator(
        @PathVariable tenantId: UUID,
        @PathVariable userId: UUID,
    ): TenantUserRoleAssignmentHttpResponse {
        return roleManagementPort.assignTenantAdministrator(
            AssignTenantAdministratorCommand(tenantId, userId),
        ).toHttpResponse()
    }

    @PostMapping("/tenants/{tenantId}/administrators/{userId}/revoke")
    fun revokeTenantAdministrator(
        @PathVariable tenantId: UUID,
        @PathVariable userId: UUID,
    ): TenantUserRoleAssignmentHttpResponse {
        return roleManagementPort.revokeTenantAdministrator(
            RevokeTenantAdministratorCommand(tenantId, userId),
        ).toHttpResponse()
    }

    @PostMapping("/tenants/{tenantId}/roles")
    fun createTenantRole(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: CreateTenantRoleHttpRequest,
    ): TenantRoleMutationHttpResponse {
        return roleManagementPort.createTenantRole(
            CreateTenantRoleCommand(
                tenantId = tenantId,
                code = request.code,
                name = request.name,
                description = request.description,
                permissionCodes = request.permissionCodes,
            ),
        ).toHttpResponse()
    }

    @PutMapping("/tenants/{tenantId}/roles/{tenantRoleId}")
    fun updateTenantRole(
        @PathVariable tenantId: UUID,
        @PathVariable tenantRoleId: UUID,
        @Valid @RequestBody request: UpdateTenantRoleHttpRequest,
    ): TenantRoleMutationHttpResponse {
        return roleManagementPort.updateTenantRole(
            UpdateTenantRoleCommand(
                tenantId = tenantId,
                tenantRoleId = tenantRoleId,
                name = request.name,
                description = request.description,
                permissionCodes = request.permissionCodes,
            ),
        ).toHttpResponse()
    }

    @DeleteMapping("/tenants/{tenantId}/roles/{tenantRoleId}")
    fun deactivateTenantRole(
        @PathVariable tenantId: UUID,
        @PathVariable tenantRoleId: UUID,
    ): TenantRoleMutationHttpResponse {
        return roleManagementPort.deactivateTenantRole(
            DeactivateTenantRoleCommand(tenantId, tenantRoleId),
        ).toHttpResponse()
    }

    @PostMapping("/tenants/{tenantId}/users/{userId}/roles/{tenantRoleId}/assign")
    fun assignTenantUserRole(
        @PathVariable tenantId: UUID,
        @PathVariable userId: UUID,
        @PathVariable tenantRoleId: UUID,
    ): TenantUserRoleAssignmentHttpResponse {
        return roleManagementPort.assignTenantUserRole(
            AssignTenantUserRoleCommand(
                tenantId = tenantId,
                userId = userId,
                tenantRoleId = tenantRoleId,
            ),
        ).toHttpResponse()
    }

    @PostMapping("/tenants/{tenantId}/users/{userId}/roles/{tenantRoleId}/revoke")
    fun revokeTenantUserRole(
        @PathVariable tenantId: UUID,
        @PathVariable userId: UUID,
        @PathVariable tenantRoleId: UUID,
    ): TenantUserRoleAssignmentHttpResponse {
        return roleManagementPort.revokeTenantUserRole(
            RevokeTenantUserRoleCommand(
                tenantId = tenantId,
                userId = userId,
                tenantRoleId = tenantRoleId,
            ),
        ).toHttpResponse()
    }

    @ExceptionHandler(TenantUserRoleManagementNotFoundException::class)
    fun handleNotFound(
        ex: TenantUserRoleManagementNotFoundException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.NOT_FOUND, "Tenant user role target not found", ex.publicMessage())
    }

    @ExceptionHandler(TenantUserRoleManagementConflictException::class)
    fun handleConflict(
        ex: TenantUserRoleManagementConflictException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Tenant user role conflict", ex.publicMessage())
    }

    @ExceptionHandler(TenantUserRoleManagementInProgressException::class)
    fun handleInProgress(
        ex: TenantUserRoleManagementInProgressException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Tenant user role change in progress", ex.publicMessage())
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidRequest(
        ex: IllegalArgumentException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.BAD_REQUEST, "Invalid tenant user role request", ex.publicMessage())
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

    private fun TenantRoleSummary.toHttpResponse(): TenantRoleHttpResponse {
        return TenantRoleHttpResponse(
            tenantRoleId = tenantRoleId,
            tenantId = tenantId,
            code = code,
            name = name,
            description = description,
            isSystem = isSystem,
            isActive = isActive,
            permissionCodes = permissionCodes,
            scope = "TENANT",
        )
    }

    private fun TenantPermissionSummary.toHttpResponse(): TenantPermissionHttpResponse {
        return TenantPermissionHttpResponse(
            permissionId = permissionId,
            tenantId = tenantId,
            code = code,
            description = description,
        )
    }

    private fun TenantAdministratorSummary.toHttpResponse(): TenantAdministratorHttpResponse {
        return TenantAdministratorHttpResponse(
            tenantId = tenantId,
            tenantRoleId = tenantRoleId,
            userId = userId,
            fullName = fullName,
            email = email,
            status = status,
            isActive = isActive,
            lockedUntil = lockedUntil,
            hasActiveIdentity = hasActiveIdentity,
        )
    }

    private fun TenantUserRoleAssignmentReceipt.toHttpResponse():
            TenantUserRoleAssignmentHttpResponse {
        return TenantUserRoleAssignmentHttpResponse(
            tenantId = tenantId,
            userId = userId,
            tenantRoleId = tenantRoleId,
            assigned = assigned,
            changed = changed,
            replayed = replayed,
        )
    }

    private fun TenantRoleMutationReceipt.toHttpResponse(): TenantRoleMutationHttpResponse {
        return TenantRoleMutationHttpResponse(
            tenantId = tenantId,
            tenantRoleId = tenantRoleId,
            isActive = isActive,
            changed = changed,
            replayed = replayed,
        )
    }
}

data class CreateTenantRoleHttpRequest(
    @field:NotBlank
    val code: String,
    @field:NotBlank
    val name: String,
    val description: String? = null,
    @field:NotEmpty
    val permissionCodes: List<String>,
)

data class UpdateTenantRoleHttpRequest(
    val name: String? = null,
    val description: String? = null,
    val permissionCodes: List<String>? = null,
)

data class TenantRoleHttpResponse(
    val tenantRoleId: UUID,
    val tenantId: UUID,
    val code: String,
    val name: String,
    val description: String?,
    val isSystem: Boolean,
    val isActive: Boolean,
    val permissionCodes: List<String>,
    val scope: String,
)

data class TenantPermissionHttpResponse(
    val permissionId: UUID,
    val tenantId: UUID,
    val code: String,
    val description: String?,
)

data class TenantAdministratorHttpResponse(
    val tenantId: UUID,
    val tenantRoleId: UUID,
    val userId: UUID,
    val fullName: String,
    val email: String,
    val status: String,
    val isActive: Boolean,
    val lockedUntil: Instant?,
    val hasActiveIdentity: Boolean,
)

data class TenantUserRoleAssignmentHttpResponse(
    val tenantId: UUID,
    val userId: UUID,
    val tenantRoleId: UUID,
    val assigned: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)

data class TenantRoleMutationHttpResponse(
    val tenantId: UUID,
    val tenantRoleId: UUID,
    val isActive: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)
