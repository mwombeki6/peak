package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.shared.exception.ApiProblemFactory
import com.mwombeki.peak.usermanagement.api.BreakGlassAccessPort
import com.mwombeki.peak.usermanagement.api.BreakGlassConflictException
import com.mwombeki.peak.usermanagement.api.BreakGlassDecision
import com.mwombeki.peak.usermanagement.api.BreakGlassNotFoundException
import com.mwombeki.peak.usermanagement.api.DecideBreakGlassAccessCommand
import com.mwombeki.peak.usermanagement.api.RequestBreakGlassAccessCommand
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestController
@RequestMapping("/api/v1/platform/support/access")
class BreakGlassAccessController(private val access: BreakGlassAccessPort) {
    @GetMapping
    fun list(
        @RequestParam(required = false) tenantId: UUID?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) limit: Int,
    ) = access.listAccess(tenantId, status, limit)

    @PostMapping
    fun request(@Valid @RequestBody request: RequestBreakGlassAccessHttpRequest) =
        ResponseEntity.status(HttpStatus.CREATED).body(access.requestAccess(
            RequestBreakGlassAccessCommand(
                request.tenantId, request.supportTicketId, request.actionCode,
                request.reason, request.durationMinutes, request.maxUses,
                request.assuranceLevel,
            ),
        ))

    @PostMapping("/{accessId}/decision")
    fun decide(
        @PathVariable accessId: UUID,
        @Valid @RequestBody request: DecideBreakGlassAccessHttpRequest,
    ) = access.decideAccess(DecideBreakGlassAccessCommand(
        accessId, request.decision, request.reason,
    ))

    @PostMapping("/{accessId}/activate")
    fun activate(@PathVariable accessId: UUID) = access.activateAccess(accessId)

    @PostMapping("/{accessId}/revoke")
    fun revoke(
        @PathVariable accessId: UUID,
        @Valid @RequestBody request: BreakGlassReasonHttpRequest,
    ) = access.revokeAccess(accessId, request.reason)
}

// A controller-specific advice must outrank GlobalExceptionHandler, whose
// @ExceptionHandler(Exception) catch-all otherwise turns every domain exception
// it does not name explicitly into a 500. Both default to LOWEST_PRECEDENCE, and
// the tie is broken arbitrarily.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [BreakGlassAccessController::class])
class BreakGlassExceptionAdvice(private val problems: ApiProblemFactory) {
    @ExceptionHandler(BreakGlassNotFoundException::class)
    fun notFound(ex: BreakGlassNotFoundException): ResponseEntity<ProblemDetail> =
        problems.response(HttpStatus.NOT_FOUND, "Privileged access not found", ex.message ?: "Not found")

    @ExceptionHandler(BreakGlassConflictException::class)
    fun conflict(ex: BreakGlassConflictException): ResponseEntity<ProblemDetail> =
        problems.response(HttpStatus.CONFLICT, "Privileged access conflict", ex.message ?: "Conflict")

    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    fun invalid(ex: RuntimeException): ResponseEntity<ProblemDetail> =
        problems.response(HttpStatus.BAD_REQUEST, "Invalid privileged access request", ex.message ?: "Invalid request")
}

data class RequestBreakGlassAccessHttpRequest(
    @field:NotNull val tenantId: UUID,
    @field:NotNull val supportTicketId: UUID,
    @field:NotBlank val actionCode: String,
    @field:NotBlank @field:Size(max = 1000) val reason: String,
    @field:Min(1) @field:Max(120) val durationMinutes: Long = 30,
    @field:Min(1) @field:Max(1000) val maxUses: Int = 100,
    @field:NotBlank val assuranceLevel: String = "mfa",
)

data class DecideBreakGlassAccessHttpRequest(
    @field:NotNull val decision: BreakGlassDecision,
    @field:NotBlank @field:Size(max = 1000) val reason: String,
)

data class BreakGlassReasonHttpRequest(
    @field:NotBlank @field:Size(max = 1000) val reason: String,
)
