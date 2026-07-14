package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.shared.exception.ApiProblemFactory

import com.mwombeki.peak.usermanagement.api.AssignPropertyAdministratorCommand
import com.mwombeki.peak.usermanagement.api.AssignPropertyUserRoleCommand
import com.mwombeki.peak.usermanagement.api.CreatePropertyRoleCommand
import com.mwombeki.peak.usermanagement.api.DeactivatePropertyRoleCommand
import com.mwombeki.peak.usermanagement.api.GetPropertyRoleQuery
import com.mwombeki.peak.usermanagement.api.ListPropertyAdministratorsQuery
import com.mwombeki.peak.usermanagement.api.ListPropertyRolesQuery
import com.mwombeki.peak.usermanagement.api.ListUserPropertyRolesQuery
import com.mwombeki.peak.usermanagement.api.PropertyAdministratorSummary
import com.mwombeki.peak.usermanagement.api.PropertyRoleMutationReceipt
import com.mwombeki.peak.usermanagement.api.PropertyRoleSummary
import com.mwombeki.peak.usermanagement.api.PropertyUserRoleAssignmentReceipt
import com.mwombeki.peak.usermanagement.api.RevokePropertyAdministratorCommand
import com.mwombeki.peak.usermanagement.api.RevokePropertyUserRoleCommand
import com.mwombeki.peak.usermanagement.api.TenantPropertyRoleManagementPort
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementConflictException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementInProgressException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementNotFoundException
import com.mwombeki.peak.usermanagement.api.UpdatePropertyRoleCommand
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
@RequestMapping("/api/v1/tenants/{tenantId}/properties/{propertyId}")
class TenantPropertyRoleManagementController(
    private val propertyRoleManagementPort: TenantPropertyRoleManagementPort,
    private val apiProblemFactory: ApiProblemFactory,
) {
    @GetMapping("/roles")
    fun listPropertyRoles(
        @PathVariable tenantId: UUID,
        @PathVariable propertyId: UUID,
    ): List<PropertyRoleHttpResponse> {
        return propertyRoleManagementPort.listPropertyRoles(
            ListPropertyRolesQuery(tenantId, propertyId),
        ).map { it.toHttpResponse() }
    }

    @GetMapping("/roles/{propertyRoleId}")
    fun getPropertyRole(
        @PathVariable tenantId: UUID,
        @PathVariable propertyId: UUID,
        @PathVariable propertyRoleId: UUID,
    ): ResponseEntity<PropertyRoleHttpResponse> {
        val role = propertyRoleManagementPort.getPropertyRole(
            GetPropertyRoleQuery(tenantId, propertyId, propertyRoleId),
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(role.toHttpResponse())
    }

    @PostMapping("/roles")
    fun createPropertyRole(
        @PathVariable tenantId: UUID,
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: CreatePropertyRoleHttpRequest,
    ): PropertyRoleMutationHttpResponse {
        return propertyRoleManagementPort.createPropertyRole(
            CreatePropertyRoleCommand(
                tenantId = tenantId,
                propertyId = propertyId,
                name = request.name,
                permissionCodes = request.permissionCodes,
            ),
        ).toHttpResponse()
    }

    @PutMapping("/roles/{propertyRoleId}")
    fun updatePropertyRole(
        @PathVariable tenantId: UUID,
        @PathVariable propertyId: UUID,
        @PathVariable propertyRoleId: UUID,
        @Valid @RequestBody request: UpdatePropertyRoleHttpRequest,
    ): PropertyRoleMutationHttpResponse {
        return propertyRoleManagementPort.updatePropertyRole(
            UpdatePropertyRoleCommand(
                tenantId = tenantId,
                propertyId = propertyId,
                propertyRoleId = propertyRoleId,
                name = request.name,
                permissionCodes = request.permissionCodes,
            ),
        ).toHttpResponse()
    }

    @DeleteMapping("/roles/{propertyRoleId}")
    fun deactivatePropertyRole(
        @PathVariable tenantId: UUID,
        @PathVariable propertyId: UUID,
        @PathVariable propertyRoleId: UUID,
    ): PropertyRoleMutationHttpResponse {
        return propertyRoleManagementPort.deactivatePropertyRole(
            DeactivatePropertyRoleCommand(tenantId, propertyId, propertyRoleId),
        ).toHttpResponse()
    }

    @GetMapping("/users/{userId}/roles")
    fun listUserPropertyRoles(
        @PathVariable tenantId: UUID,
        @PathVariable propertyId: UUID,
        @PathVariable userId: UUID,
    ): List<PropertyRoleHttpResponse> {
        return propertyRoleManagementPort.listUserPropertyRoles(
            ListUserPropertyRolesQuery(tenantId, propertyId, userId),
        ).map { it.toHttpResponse() }
    }

    @GetMapping("/administrators")
    fun listPropertyAdministrators(
        @PathVariable tenantId: UUID,
        @PathVariable propertyId: UUID,
    ): List<PropertyAdministratorHttpResponse> {
        return propertyRoleManagementPort.listPropertyAdministrators(
            ListPropertyAdministratorsQuery(tenantId, propertyId),
        ).map { it.toHttpResponse() }
    }

    @PostMapping("/administrators/{userId}/assign")
    fun assignPropertyAdministrator(
        @PathVariable tenantId: UUID,
        @PathVariable propertyId: UUID,
        @PathVariable userId: UUID,
    ): PropertyUserRoleAssignmentHttpResponse {
        return propertyRoleManagementPort.assignPropertyAdministrator(
            AssignPropertyAdministratorCommand(tenantId, propertyId, userId),
        ).toHttpResponse()
    }

    @PostMapping("/administrators/{userId}/revoke")
    fun revokePropertyAdministrator(
        @PathVariable tenantId: UUID,
        @PathVariable propertyId: UUID,
        @PathVariable userId: UUID,
    ): PropertyUserRoleAssignmentHttpResponse {
        return propertyRoleManagementPort.revokePropertyAdministrator(
            RevokePropertyAdministratorCommand(tenantId, propertyId, userId),
        ).toHttpResponse()
    }

    @PostMapping("/users/{userId}/roles/{propertyRoleId}/assign")
    fun assignPropertyUserRole(
        @PathVariable tenantId: UUID,
        @PathVariable propertyId: UUID,
        @PathVariable userId: UUID,
        @PathVariable propertyRoleId: UUID,
    ): PropertyUserRoleAssignmentHttpResponse {
        return propertyRoleManagementPort.assignPropertyUserRole(
            AssignPropertyUserRoleCommand(tenantId, propertyId, userId, propertyRoleId),
        ).toHttpResponse()
    }

    @PostMapping("/users/{userId}/roles/{propertyRoleId}/revoke")
    fun revokePropertyUserRole(
        @PathVariable tenantId: UUID,
        @PathVariable propertyId: UUID,
        @PathVariable userId: UUID,
        @PathVariable propertyRoleId: UUID,
    ): PropertyUserRoleAssignmentHttpResponse {
        return propertyRoleManagementPort.revokePropertyUserRole(
            RevokePropertyUserRoleCommand(tenantId, propertyId, userId, propertyRoleId),
        ).toHttpResponse()
    }

    @ExceptionHandler(TenantUserRoleManagementNotFoundException::class)
    fun handleNotFound(
        ex: TenantUserRoleManagementNotFoundException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.NOT_FOUND, "Property role target not found", ex.publicMessage())
    }

    @ExceptionHandler(TenantUserRoleManagementConflictException::class)
    fun handleConflict(
        ex: TenantUserRoleManagementConflictException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Property role conflict", ex.publicMessage())
    }

    @ExceptionHandler(TenantUserRoleManagementInProgressException::class)
    fun handleInProgress(
        ex: TenantUserRoleManagementInProgressException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Property role change in progress", ex.publicMessage())
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidRequest(
        ex: IllegalArgumentException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.BAD_REQUEST, "Invalid property role request", ex.publicMessage())
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

    private fun PropertyRoleSummary.toHttpResponse(): PropertyRoleHttpResponse {
        return PropertyRoleHttpResponse(
            propertyRoleId = propertyRoleId,
            tenantId = tenantId,
            propertyId = propertyId,
            name = name,
            isSystem = isSystem,
            isActive = isActive,
            permissionCodes = permissionCodes,
            scope = scope,
        )
    }

    private fun PropertyRoleMutationReceipt.toHttpResponse(): PropertyRoleMutationHttpResponse {
        return PropertyRoleMutationHttpResponse(
            tenantId = tenantId,
            propertyId = propertyId,
            propertyRoleId = propertyRoleId,
            isActive = isActive,
            changed = changed,
            replayed = replayed,
        )
    }

    private fun PropertyUserRoleAssignmentReceipt.toHttpResponse():
            PropertyUserRoleAssignmentHttpResponse {
        return PropertyUserRoleAssignmentHttpResponse(
            tenantId = tenantId,
            propertyId = propertyId,
            userId = userId,
            propertyRoleId = propertyRoleId,
            assigned = assigned,
            changed = changed,
            replayed = replayed,
        )
    }

    private fun PropertyAdministratorSummary.toHttpResponse(): PropertyAdministratorHttpResponse {
        return PropertyAdministratorHttpResponse(
            tenantId = tenantId,
            propertyId = propertyId,
            propertyRoleId = propertyRoleId,
            userId = userId,
            fullName = fullName,
            email = email,
            status = status,
            isActive = isActive,
            lockedUntil = lockedUntil,
            hasActiveIdentity = hasActiveIdentity,
        )
    }
}

data class CreatePropertyRoleHttpRequest(
    @field:NotBlank
    val name: String,
    @field:NotEmpty
    val permissionCodes: List<String>,
)

data class UpdatePropertyRoleHttpRequest(
    val name: String? = null,
    val permissionCodes: List<String>? = null,
)

data class PropertyRoleHttpResponse(
    val propertyRoleId: UUID,
    val tenantId: UUID,
    val propertyId: UUID,
    val name: String,
    val isSystem: Boolean,
    val isActive: Boolean,
    val permissionCodes: List<String>,
    val scope: String,
)

data class PropertyRoleMutationHttpResponse(
    val tenantId: UUID,
    val propertyId: UUID,
    val propertyRoleId: UUID,
    val isActive: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)

data class PropertyUserRoleAssignmentHttpResponse(
    val tenantId: UUID,
    val propertyId: UUID,
    val userId: UUID,
    val propertyRoleId: UUID,
    val assigned: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)

data class PropertyAdministratorHttpResponse(
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
