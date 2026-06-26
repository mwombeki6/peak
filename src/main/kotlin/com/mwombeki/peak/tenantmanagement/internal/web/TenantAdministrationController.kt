package com.mwombeki.peak.tenantmanagement.internal.web

import com.mwombeki.peak.tenantmanagement.api.TenantAdministrationConflictException
import com.mwombeki.peak.tenantmanagement.api.TenantAdministrationInProgressException
import com.mwombeki.peak.tenantmanagement.api.TenantAdministrationNotFoundException
import com.mwombeki.peak.tenantmanagement.api.TenantAdministrationPort
import com.mwombeki.peak.tenantmanagement.api.TenantModuleCommand
import com.mwombeki.peak.tenantmanagement.api.TenantModuleMutationReceipt
import com.mwombeki.peak.tenantmanagement.api.TenantModuleSummary
import com.mwombeki.peak.tenantmanagement.api.TenantReadinessResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
class TenantAdministrationController(
    private val tenantAdministrationPort: TenantAdministrationPort,
) {
    @GetMapping("/modules")
    fun listTenantModules(
        @PathVariable tenantId: UUID,
    ): List<TenantModuleHttpResponse> {
        return tenantAdministrationPort.listTenantModules(tenantId)
            .map { it.toHttpResponse() }
    }

    @PostMapping("/modules")
    fun enableTenantModule(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: EnableTenantModuleHttpRequest,
    ): TenantModuleMutationHttpResponse {
        return tenantAdministrationPort.enableTenantModule(
            TenantModuleCommand(tenantId, request.moduleId),
        ).toHttpResponse()
    }

    @DeleteMapping("/modules/{moduleId}")
    fun disableTenantModule(
        @PathVariable tenantId: UUID,
        @PathVariable moduleId: String,
    ): TenantModuleMutationHttpResponse {
        return tenantAdministrationPort.disableTenantModule(
            TenantModuleCommand(tenantId, moduleId),
        ).toHttpResponse()
    }

    @GetMapping("/readiness")
    fun getTenantReadiness(
        @PathVariable tenantId: UUID,
    ): TenantReadinessResponse {
        return tenantAdministrationPort.getTenantReadiness(tenantId)
    }

    @ExceptionHandler(TenantAdministrationNotFoundException::class)
    fun handleNotFound(ex: TenantAdministrationNotFoundException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.NOT_FOUND, "Tenant administration target not found", ex.publicMessage())
    }

    @ExceptionHandler(TenantAdministrationConflictException::class)
    fun handleConflict(ex: TenantAdministrationConflictException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Tenant administration conflict", ex.publicMessage())
    }

    @ExceptionHandler(TenantAdministrationInProgressException::class)
    fun handleInProgress(
        ex: TenantAdministrationInProgressException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Tenant administration command in progress", ex.publicMessage())
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidRequest(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.BAD_REQUEST, "Invalid tenant administration request", ex.publicMessage())
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

    private fun TenantModuleSummary.toHttpResponse(): TenantModuleHttpResponse {
        return TenantModuleHttpResponse(
            tenantId = tenantId,
            moduleId = moduleId,
            name = name,
            isEnabled = isEnabled,
            isConfigured = isConfigured,
            source = source,
        )
    }

    private fun TenantModuleMutationReceipt.toHttpResponse(): TenantModuleMutationHttpResponse {
        return TenantModuleMutationHttpResponse(
            tenantId = tenantId,
            moduleId = moduleId,
            enabled = enabled,
            changed = changed,
            replayed = replayed,
        )
    }
}

data class EnableTenantModuleHttpRequest(
    @field:NotBlank
    val moduleId: String,
)

data class TenantModuleHttpResponse(
    val tenantId: UUID,
    val moduleId: String,
    val name: String,
    val isEnabled: Boolean,
    val isConfigured: Boolean,
    val source: String,
)

data class TenantModuleMutationHttpResponse(
    val tenantId: UUID,
    val moduleId: String,
    val enabled: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)
