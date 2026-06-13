package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.usermanagement.api.AssignTenantUserRoleCommand
import com.mwombeki.peak.usermanagement.api.ListTenantPermissionsQuery
import com.mwombeki.peak.usermanagement.api.ListTenantRolesQuery
import com.mwombeki.peak.usermanagement.api.RevokeTenantUserRoleCommand
import com.mwombeki.peak.usermanagement.api.TenantPermissionSummary
import com.mwombeki.peak.usermanagement.api.TenantRoleSummary
import com.mwombeki.peak.usermanagement.api.TenantUserRoleAssignmentReceipt
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementConflictException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementInProgressException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementNotFoundException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementPort
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class TenantUserRoleManagementController(
    private val roleManagementPort: TenantUserRoleManagementPort,
) {
    @GetMapping("/tenants/{tenantId}/roles")
    fun listTenantRoles(
        @PathVariable tenantId: UUID,
    ): List<TenantRoleHttpResponse> {
        return roleManagementPort.listTenantRoles(
            ListTenantRolesQuery(tenantId),
        ).map { it.toHttpResponse() }
    }

    @GetMapping("/tenants/{tenantId}/permissions")
    fun listTenantPermissions(
        @PathVariable tenantId: UUID,
    ): List<TenantPermissionHttpResponse> {
        return roleManagementPort.listTenantPermissions(
            ListTenantPermissionsQuery(tenantId),
        ).map { it.toHttpResponse() }
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
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.title = title
        return ResponseEntity.status(status).body(problem)
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
}

data class TenantRoleHttpResponse(
    val tenantRoleId: UUID,
    val tenantId: UUID,
    val code: String,
    val name: String,
    val description: String?,
    val isSystem: Boolean,
    val isActive: Boolean,
    val permissionCodes: List<String>,
)

data class TenantPermissionHttpResponse(
    val permissionId: UUID,
    val tenantId: UUID,
    val code: String,
    val description: String?,
)

data class TenantUserRoleAssignmentHttpResponse(
    val tenantId: UUID,
    val userId: UUID,
    val tenantRoleId: UUID,
    val assigned: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)
