package com.mwombeki.peak.platformgovernance.internal.web

import com.mwombeki.peak.platformgovernance.api.AddSupportNoteCommand
import com.mwombeki.peak.platformgovernance.api.OpenSupportTicketCommand
import com.mwombeki.peak.platformgovernance.api.SupportControlConflictException
import com.mwombeki.peak.platformgovernance.api.SupportControlNotFoundException
import com.mwombeki.peak.platformgovernance.api.SupportControlPort
import com.mwombeki.peak.platformgovernance.api.SupportTicketQuery
import com.mwombeki.peak.platformgovernance.api.UpdateSupportTicketCommand
import com.mwombeki.peak.shared.exception.ApiProblemFactory
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/support/tickets")
class TenantSupportController(private val support: SupportControlPort) {
    @GetMapping
    fun list(
        @PathVariable tenantId: UUID,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) priority: String?,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) limit: Int,
    ) = support.listTickets(SupportTicketQuery(
        tenantId, status, priority, null, platformView = false, limit,
    ))

    @GetMapping("/{ticketId}")
    fun detail(@PathVariable tenantId: UUID, @PathVariable ticketId: UUID) =
        support.getTicket(tenantId, ticketId, platformView = false)

    @PostMapping
    fun open(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: OpenSupportTicketHttpRequest,
    ) = ResponseEntity.status(HttpStatus.CREATED).body(
        support.openTicket(OpenSupportTicketCommand(
            tenantId, request.propertyId, request.subject, request.description,
            request.priority, request.category, request.metadata,
        )),
    )

    @PostMapping("/{ticketId}/notes")
    fun note(
        @PathVariable tenantId: UUID,
        @PathVariable ticketId: UUID,
        @Valid @RequestBody request: SupportNoteHttpRequest,
    ) = support.addNote(AddSupportNoteCommand(
        tenantId, ticketId, request.note, "customer", platformView = false,
    ))
}

@RestController
@RequestMapping("/api/v1/platform/support/tickets")
class PlatformSupportController(private val support: SupportControlPort) {
    @GetMapping
    fun list(
        @RequestParam(required = false) tenantId: UUID?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) priority: String?,
        @RequestParam(required = false) assignedPlatformUserId: UUID?,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) limit: Int,
    ) = support.listTickets(SupportTicketQuery(
        tenantId, status, priority, assignedPlatformUserId, platformView = true, limit,
    ))

    @GetMapping("/{tenantId}/{ticketId}")
    fun detail(@PathVariable tenantId: UUID, @PathVariable ticketId: UUID) =
        support.getTicket(tenantId, ticketId, platformView = true)

    @PostMapping("/{tenantId}/{ticketId}/notes")
    fun note(
        @PathVariable tenantId: UUID,
        @PathVariable ticketId: UUID,
        @Valid @RequestBody request: SupportNoteHttpRequest,
    ) = support.addNote(AddSupportNoteCommand(
        tenantId, ticketId, request.note, request.visibility, platformView = true,
    ))

    @PutMapping("/{tenantId}/{ticketId}")
    fun update(
        @PathVariable tenantId: UUID,
        @PathVariable ticketId: UUID,
        @Valid @RequestBody request: UpdateSupportTicketHttpRequest,
    ) = support.updateTicket(UpdateSupportTicketCommand(
        tenantId, ticketId, request.status, request.priority,
        request.assignedPlatformUserId, request.reason,
    ))
}

@RestControllerAdvice(assignableTypes = [TenantSupportController::class, PlatformSupportController::class])
class SupportControlExceptionAdvice(private val problems: ApiProblemFactory) {
    @ExceptionHandler(SupportControlNotFoundException::class)
    fun notFound(ex: SupportControlNotFoundException): ResponseEntity<ProblemDetail> =
        problems.response(HttpStatus.NOT_FOUND, "Support ticket not found", ex.message ?: "Not found")

    @ExceptionHandler(SupportControlConflictException::class)
    fun conflict(ex: SupportControlConflictException): ResponseEntity<ProblemDetail> =
        problems.response(HttpStatus.CONFLICT, "Support control conflict", ex.message ?: "Conflict")

    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    fun invalid(ex: RuntimeException): ResponseEntity<ProblemDetail> =
        problems.response(HttpStatus.BAD_REQUEST, "Invalid support request", ex.message ?: "Invalid request")
}

data class OpenSupportTicketHttpRequest(
    val propertyId: UUID? = null,
    @field:NotBlank @field:Size(max = 200) val subject: String,
    @field:NotBlank @field:Size(max = 10_000) val description: String,
    @field:NotBlank val priority: String = "normal",
    @field:NotBlank val category: String = "general",
    val metadata: Map<String, Any?> = emptyMap(),
)

data class SupportNoteHttpRequest(
    @field:NotBlank @field:Size(max = 10_000) val note: String,
    @field:NotBlank val visibility: String = "customer",
)

data class UpdateSupportTicketHttpRequest(
    val status: String? = null,
    val priority: String? = null,
    val assignedPlatformUserId: UUID? = null,
    @field:NotBlank @field:Size(max = 1000) val reason: String,
)
