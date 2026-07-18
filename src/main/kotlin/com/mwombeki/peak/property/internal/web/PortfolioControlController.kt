package com.mwombeki.peak.property.internal.web

import com.mwombeki.peak.property.api.AddPortfolioRevisionCommand
import com.mwombeki.peak.property.api.AssignPortfolioPropertyCommand
import com.mwombeki.peak.property.api.ChangePortfolioTemplateCommand
import com.mwombeki.peak.property.api.CreateOrganizationUnitCommand
import com.mwombeki.peak.property.api.CreatePortfolioTemplateCommand
import com.mwombeki.peak.property.api.PortfolioControlConflictException
import com.mwombeki.peak.property.api.PortfolioControlNotFoundException
import com.mwombeki.peak.property.api.PortfolioControlPort
import com.mwombeki.peak.property.api.PortfolioRolloutTarget
import com.mwombeki.peak.property.api.PortfolioTemplateAction
import com.mwombeki.peak.property.api.RolloutPortfolioConfigCommand
import com.mwombeki.peak.property.api.UpdateOrganizationUnitCommand
import com.mwombeki.peak.property.api.UpdatePortfolioRolloutCommand
import com.mwombeki.peak.shared.exception.ApiProblemFactory
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
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
@RequestMapping("/api/v1/tenants/{tenantId}/portfolio")
class PortfolioControlController(private val portfolio: PortfolioControlPort) {
    @GetMapping
    fun overview(@PathVariable tenantId: UUID) = portfolio.overview(tenantId)

    @PostMapping("/units")
    fun createUnit(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: CreateOrganizationUnitHttpRequest,
    ) = ResponseEntity.status(HttpStatus.CREATED).body(portfolio.createUnit(
        CreateOrganizationUnitCommand(
            tenantId, request.parentId, request.unitType, request.code, request.name,
        ),
    ))

    @PutMapping("/units/{unitId}")
    fun updateUnit(
        @PathVariable tenantId: UUID,
        @PathVariable unitId: UUID,
        @Valid @RequestBody request: UpdateOrganizationUnitHttpRequest,
    ) = portfolio.updateUnit(UpdateOrganizationUnitCommand(
        tenantId, unitId, request.parentId, request.name, request.status, request.expectedVersion,
    ))

    @PutMapping("/units/{unitId}/properties/{propertyId}")
    fun assignProperty(
        @PathVariable tenantId: UUID,
        @PathVariable unitId: UUID,
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: AssignPortfolioPropertyHttpRequest,
    ) = portfolio.assignProperty(AssignPortfolioPropertyCommand(
        tenantId, unitId, propertyId, request.primary,
    ))

    @PostMapping("/templates")
    fun createTemplate(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: CreatePortfolioTemplateHttpRequest,
    ) = ResponseEntity.status(HttpStatus.CREATED).body(portfolio.createTemplate(
        CreatePortfolioTemplateCommand(tenantId, request.name, request.configDomain),
    ))

    @PostMapping("/templates/{templateId}/revisions")
    fun addRevision(
        @PathVariable tenantId: UUID,
        @PathVariable templateId: UUID,
        @Valid @RequestBody request: AddPortfolioRevisionHttpRequest,
    ) = portfolio.addRevision(AddPortfolioRevisionCommand(
        tenantId, templateId, request.config, request.changeSummary,
    ))

    @PostMapping("/templates/{templateId}/actions")
    fun changeTemplate(
        @PathVariable tenantId: UUID,
        @PathVariable templateId: UUID,
        @Valid @RequestBody request: ChangePortfolioTemplateHttpRequest,
    ) = portfolio.changeTemplate(ChangePortfolioTemplateCommand(
        tenantId, templateId, request.action, request.reason,
    ))

    @PostMapping("/templates/{templateId}/rollouts")
    fun rollout(
        @PathVariable tenantId: UUID,
        @PathVariable templateId: UUID,
        @Valid @RequestBody request: PortfolioRolloutHttpRequest,
    ) = ResponseEntity.status(HttpStatus.ACCEPTED).body(portfolio.rollout(
        RolloutPortfolioConfigCommand(
            tenantId, templateId, request.revision,
            request.targets.map { PortfolioRolloutTarget(
                it.organizationUnitId, it.propertyId, it.overrides,
            ) }, request.canary, request.scheduledAt,
        ),
    ))

    @PostMapping("/rollouts/{assignmentId}")
    fun updateRollout(
        @PathVariable tenantId: UUID,
        @PathVariable assignmentId: UUID,
        @Valid @RequestBody request: UpdatePortfolioRolloutHttpRequest,
    ) = portfolio.updateRollout(UpdatePortfolioRolloutCommand(
        tenantId, assignmentId, request.status, request.errorDetail,
    ))

    @GetMapping("/properties/{propertyId}/effective-config")
    fun effectiveConfig(
        @PathVariable tenantId: UUID,
        @PathVariable propertyId: UUID,
        @RequestParam @NotBlank domain: String,
    ) = ResponseEntity.ofNullable(portfolio.effectiveConfig(tenantId, propertyId, domain))
}

@RestControllerAdvice(assignableTypes = [PortfolioControlController::class])
class PortfolioControlExceptionAdvice(private val problems: ApiProblemFactory) {
    @ExceptionHandler(PortfolioControlNotFoundException::class)
    fun notFound(ex: PortfolioControlNotFoundException): ResponseEntity<ProblemDetail> =
        problems.response(HttpStatus.NOT_FOUND, "Portfolio target not found", ex.message ?: "Not found")

    @ExceptionHandler(PortfolioControlConflictException::class)
    fun conflict(ex: PortfolioControlConflictException): ResponseEntity<ProblemDetail> =
        problems.response(HttpStatus.CONFLICT, "Portfolio control conflict", ex.message ?: "Conflict")

    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    fun invalid(ex: RuntimeException): ResponseEntity<ProblemDetail> =
        problems.response(HttpStatus.BAD_REQUEST, "Invalid portfolio request", ex.message ?: "Invalid request")
}

data class CreateOrganizationUnitHttpRequest(
    val parentId: UUID? = null,
    @field:NotBlank val unitType: String,
    @field:NotBlank val code: String,
    @field:NotBlank @field:Size(max = 200) val name: String,
)

data class UpdateOrganizationUnitHttpRequest(
    val parentId: UUID? = null,
    val name: String? = null,
    val status: String? = null,
    @field:Positive val expectedVersion: Long,
)

data class AssignPortfolioPropertyHttpRequest(val primary: Boolean = false)

data class CreatePortfolioTemplateHttpRequest(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:NotBlank val configDomain: String,
)

data class AddPortfolioRevisionHttpRequest(
    @field:NotEmpty val config: Map<String, Any?>,
    @field:NotBlank @field:Size(max = 1000) val changeSummary: String,
)

data class ChangePortfolioTemplateHttpRequest(
    @field:NotNull val action: PortfolioTemplateAction,
    @field:NotBlank val reason: String,
)

data class PortfolioRolloutTargetHttpRequest(
    val organizationUnitId: UUID? = null,
    val propertyId: UUID? = null,
    val overrides: Map<String, Any?> = emptyMap(),
)

data class PortfolioRolloutHttpRequest(
    @field:Positive val revision: Int,
    @field:NotEmpty @field:Size(max = 200) val targets: List<PortfolioRolloutTargetHttpRequest>,
    val canary: Boolean = false,
    val scheduledAt: Instant? = null,
)

data class UpdatePortfolioRolloutHttpRequest(
    @field:NotBlank val status: String,
    val errorDetail: String? = null,
)
