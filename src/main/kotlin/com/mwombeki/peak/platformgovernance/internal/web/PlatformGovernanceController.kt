package com.mwombeki.peak.platformgovernance.internal.web

import com.mwombeki.peak.shared.exception.ApiProblemFactory

import com.mwombeki.peak.platformgovernance.api.TenantGovernancePort
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/platform/tenants")
class PlatformGovernanceController(
    private val governancePort: TenantGovernancePort,
    private val requestContextHolder: RequestContextHolder,
    private val apiProblemFactory: ApiProblemFactory,
) {

    @PostMapping("/{id}/approve")
    fun approveTenant(
        @PathVariable id: UUID,
        @RequestBody request: GovernanceActionRequest,
    ): ResponseEntity<Any> {
        val result = governancePort.approveTenant(
            tenantId = id,
            operatorId = activePlatformOperatorId(),
            reason = request.reason,
        )
        return ResponseEntity.ok(result)
    }

    @PostMapping("/{id}/suspend")
    fun suspendTenant(
        @PathVariable id: UUID,
        @RequestBody request: GovernanceActionRequest,
    ): ResponseEntity<Any> {
        val result = governancePort.suspendTenant(
            tenantId = id,
            operatorId = activePlatformOperatorId(),
            reason = request.reason,
        )
        return ResponseEntity.ok(result)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidRequest(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.BAD_REQUEST, "Invalid governance request", ex.message)
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(ex: IllegalStateException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Governance conflict", ex.message)
    }

    private fun activePlatformOperatorId(): UUID {
        return when (val identity = requestContextHolder.current().identity) {
            is RequestIdentity.Platform -> identity.platformUserId
            is RequestIdentity.Support -> identity.platformUserId
            else -> throw IllegalStateException("Platform identity is required")
        }
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String?,
    ): ResponseEntity<ProblemDetail> {
        return apiProblemFactory.response(status, title, detail ?: "Governance request failed")
    }
}

data class GovernanceActionRequest(
    val reason: String,
)
