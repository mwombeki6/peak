package com.mwombeki.peak.tenantmanagement.internal.web

import com.mwombeki.peak.tenantmanagement.api.TenantOnboardingPort
import com.mwombeki.peak.tenantmanagement.api.TenantRegisterRequest
import com.mwombeki.peak.tenantmanagement.api.TenantResponse
import com.mwombeki.peak.tenantmanagement.api.TenantStatus
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/platform/tenants")
class TenantOnboardingController(
    private val tenantOnboardingPort: TenantOnboardingPort,
) {
    @PostMapping
    fun registerTenant(
        @Valid @RequestBody request: TenantRegisterRequest,
    ): ResponseEntity<TenantResponse> {
        val response = tenantOnboardingPort.registerNewTenant(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/{id}")
    fun getTenant(@PathVariable id: UUID): ResponseEntity<TenantResponse> {
        val response = tenantOnboardingPort.getTenantById(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(response)
    }

    @PatchMapping("/{id}/status")
    fun updateStatus(
        @PathVariable id: UUID,
        @RequestParam status: TenantStatus,
    ): ResponseEntity<TenantResponse> {
        val response = tenantOnboardingPort.updateTenantStatus(id, status)
        return ResponseEntity.ok(response)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidRequest(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.BAD_REQUEST, "Invalid tenant request", ex.message)
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(ex: IllegalStateException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Tenant request conflict", ex.message)
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String?,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(
            status,
            detail ?: "Tenant request failed",
        )
        problem.title = title
        return ResponseEntity.status(status).body(problem)
    }
}
