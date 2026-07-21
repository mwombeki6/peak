package com.mwombeki.peak.platformgovernance.internal.web

import com.mwombeki.peak.platformgovernance.api.AssignPlatformReleaseCommand
import com.mwombeki.peak.platformgovernance.api.ChangePlatformReleaseCommand
import com.mwombeki.peak.platformgovernance.api.CompletePlatformJobRunCommand
import com.mwombeki.peak.platformgovernance.api.CreatePlatformIncidentCommand
import com.mwombeki.peak.platformgovernance.api.CreatePlatformReleaseCommand
import com.mwombeki.peak.platformgovernance.api.FeatureControlPort
import com.mwombeki.peak.platformgovernance.api.FleetControlConflictException
import com.mwombeki.peak.platformgovernance.api.FleetControlNotFoundException
import com.mwombeki.peak.platformgovernance.api.FleetControlPort
import com.mwombeki.peak.platformgovernance.api.RecordServiceHealthCommand
import com.mwombeki.peak.platformgovernance.api.RegisterPlatformJobCommand
import com.mwombeki.peak.platformgovernance.api.RegisterPlatformServiceCommand
import com.mwombeki.peak.platformgovernance.api.ReleaseAction
import com.mwombeki.peak.platformgovernance.api.ReleaseControlPort
import com.mwombeki.peak.platformgovernance.api.RunPlatformJobCommand
import com.mwombeki.peak.platformgovernance.api.UpdatePlatformAlertCommand
import com.mwombeki.peak.platformgovernance.api.UpdatePlatformIncidentCommand
import com.mwombeki.peak.platformgovernance.api.UpdateReleaseAssignmentCommand
import com.mwombeki.peak.platformgovernance.api.UpsertFeatureFlagCommand
import com.mwombeki.peak.shared.exception.ApiProblemFactory
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
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
@RequestMapping("/api/v1/platform/monitoring")
class FleetControlController(private val fleet: FleetControlPort) {
    @GetMapping
    fun snapshot() = fleet.fleetSnapshot()

    @PutMapping("/services")
    fun registerService(@Valid @RequestBody request: RegisterServiceHttpRequest) =
        fleet.registerService(RegisterPlatformServiceCommand(
            request.serviceKey, request.name, request.serviceType, request.ownerTeam, request.active,
        ))

    @PostMapping("/services/{serviceId}/health")
    fun recordHealth(
        @PathVariable serviceId: UUID,
        @Valid @RequestBody request: ServiceHealthHttpRequest,
    ) = fleet.recordHealth(RecordServiceHealthCommand(
        serviceId, request.status, request.latencyMs, request.details,
    ))

    @PutMapping("/jobs")
    fun registerJob(@Valid @RequestBody request: RegisterJobHttpRequest) =
        fleet.registerJob(RegisterPlatformJobCommand(
            request.jobKey, request.serviceId, request.description,
            request.scheduleCron, request.active,
        ))

    @PostMapping("/jobs/{jobId}/runs")
    fun runJob(
        @PathVariable jobId: UUID,
        @Valid @RequestBody request: RunJobHttpRequest,
    ) = ResponseEntity.status(HttpStatus.ACCEPTED).body(
        fleet.runJob(RunPlatformJobCommand(jobId, request.tenantId, request.metadata)),
    )

    @PostMapping("/job-runs/{runId}/complete")
    fun completeJob(
        @PathVariable runId: UUID,
        @Valid @RequestBody request: CompleteJobRunHttpRequest,
    ) = fleet.completeJobRun(CompletePlatformJobRunCommand(
        runId, request.status, request.errorMessage, request.metadata,
    ))

    @PostMapping("/alerts/{alertId}")
    fun updateAlert(
        @PathVariable alertId: UUID,
        @Valid @RequestBody request: UpdateAlertHttpRequest,
    ) = fleet.updateAlert(UpdatePlatformAlertCommand(alertId, request.status, request.reason))

    @PostMapping("/incidents")
    fun createIncident(@Valid @RequestBody request: CreateIncidentHttpRequest) =
        ResponseEntity.status(HttpStatus.CREATED).body(fleet.createIncident(
            CreatePlatformIncidentCommand(
                request.title, request.severity, request.summary, request.ownerPlatformUserId,
            ),
        ))

    @PostMapping("/incidents/{incidentId}")
    fun updateIncident(
        @PathVariable incidentId: UUID,
        @Valid @RequestBody request: UpdateIncidentHttpRequest,
    ) = fleet.updateIncident(UpdatePlatformIncidentCommand(
        incidentId, request.status, request.severity, request.summary,
        request.ownerPlatformUserId, request.reason,
    ))
}

@RestController
@RequestMapping("/api/v1/platform/releases")
class ReleaseControlController(private val releases: ReleaseControlPort) {
    @GetMapping
    fun list() = releases.listReleases()

    @PostMapping
    fun create(@Valid @RequestBody request: CreateReleaseHttpRequest) =
        ResponseEntity.status(HttpStatus.CREATED).body(releases.createRelease(
            CreatePlatformReleaseCommand(
                request.version, request.imageDigest, request.schemaVersion, request.releaseNotes,
            ),
        ))

    @PostMapping("/{releaseId}/actions")
    fun change(
        @PathVariable releaseId: UUID,
        @Valid @RequestBody request: ChangeReleaseHttpRequest,
    ) = releases.changeRelease(ChangePlatformReleaseCommand(releaseId, request.action, request.reason))

    @PostMapping("/{releaseId}/assignments")
    fun assign(
        @PathVariable releaseId: UUID,
        @Valid @RequestBody request: AssignReleaseHttpRequest,
    ) = ResponseEntity.status(HttpStatus.ACCEPTED).body(releases.assignRelease(
        AssignPlatformReleaseCommand(
            releaseId, request.tenantId, request.releaseChannel, request.scheduledAt,
        ),
    ))

    @PostMapping("/assignments/{assignmentId}")
    fun updateAssignment(
        @PathVariable assignmentId: UUID,
        @Valid @RequestBody request: UpdateReleaseAssignmentHttpRequest,
    ) = releases.updateAssignment(UpdateReleaseAssignmentCommand(
        assignmentId, request.status, request.actualVersion,
        request.rollbackReleaseId, request.errorDetail,
    ))
}

@RestController
@RequestMapping("/api/v1/platform/features")
class FeatureControlController(private val features: FeatureControlPort) {
    @GetMapping
    fun list(
        @RequestParam(required = false) tenantId: UUID?,
        @RequestParam(required = false) propertyId: UUID?,
    ) = features.listFlags(tenantId, propertyId)

    @PutMapping
    fun upsert(@Valid @RequestBody request: UpsertFeatureHttpRequest) =
        features.upsertFlag(UpsertFeatureFlagCommand(
            request.flagKey, request.description, request.scope,
            request.tenantId, request.propertyId, request.enabled,
            request.rolloutRules, request.reason,
        ))

    @GetMapping("/effective")
    fun effective(
        @RequestParam tenantId: UUID,
        @RequestParam(required = false) propertyId: UUID?,
    ) = features.effectiveFlags(tenantId, propertyId)
}

@RestControllerAdvice(assignableTypes = [
    FleetControlController::class, ReleaseControlController::class, FeatureControlController::class,
])
class FleetControlExceptionAdvice(private val problems: ApiProblemFactory) {
    @ExceptionHandler(FleetControlNotFoundException::class)
    fun notFound(ex: FleetControlNotFoundException): ResponseEntity<ProblemDetail> =
        problems.response(HttpStatus.NOT_FOUND, "Platform operations target not found", ex.message ?: "Not found")

    @ExceptionHandler(FleetControlConflictException::class)
    fun conflict(ex: FleetControlConflictException): ResponseEntity<ProblemDetail> =
        problems.response(HttpStatus.CONFLICT, "Platform operations conflict", ex.message ?: "Conflict")

    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    fun invalid(ex: RuntimeException): ResponseEntity<ProblemDetail> =
        problems.response(HttpStatus.BAD_REQUEST, "Invalid platform operations request", ex.message ?: "Invalid request")
}

data class RegisterServiceHttpRequest(
    @field:NotBlank val serviceKey: String,
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:NotBlank val serviceType: String,
    val ownerTeam: String? = null,
    val active: Boolean = true,
)

data class ServiceHealthHttpRequest(
    @field:NotBlank val status: String,
    @field:PositiveOrZero val latencyMs: Int? = null,
    val details: Map<String, Any?> = emptyMap(),
)

data class RegisterJobHttpRequest(
    @field:NotBlank val jobKey: String,
    val serviceId: UUID? = null,
    val description: String? = null,
    val scheduleCron: String? = null,
    val active: Boolean = true,
)

data class RunJobHttpRequest(
    val tenantId: UUID? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class CompleteJobRunHttpRequest(
    @field:NotBlank val status: String,
    val errorMessage: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class UpdateAlertHttpRequest(
    @field:NotBlank val status: String,
    @field:NotBlank val reason: String,
)

data class CreateIncidentHttpRequest(
    @field:NotBlank @field:Size(max = 200) val title: String,
    @field:NotBlank val severity: String,
    val summary: String? = null,
    val ownerPlatformUserId: UUID? = null,
)

data class UpdateIncidentHttpRequest(
    @field:NotBlank val status: String,
    val severity: String? = null,
    val summary: String? = null,
    val ownerPlatformUserId: UUID? = null,
    @field:NotBlank val reason: String,
)

data class CreateReleaseHttpRequest(
    @field:NotBlank @field:Pattern(regexp = "v1\\.[0-9]+\\.[0-9]+(?:-[a-z0-9.-]+)?") val version: String,
    @field:NotBlank @field:Pattern(regexp = "sha256:[0-9a-f]{64}") val imageDigest: String,
    @field:Positive val schemaVersion: Int,
    val releaseNotes: String? = null,
)

data class ChangeReleaseHttpRequest(
    @field:NotNull val action: ReleaseAction,
    @field:NotBlank val reason: String,
)

data class AssignReleaseHttpRequest(
    val tenantId: UUID? = null,
    @field:NotBlank val releaseChannel: String,
    val scheduledAt: Instant? = null,
)

data class UpdateReleaseAssignmentHttpRequest(
    @field:NotBlank val status: String,
    val actualVersion: String? = null,
    val rollbackReleaseId: UUID? = null,
    val errorDetail: String? = null,
)

data class UpsertFeatureHttpRequest(
    @field:NotBlank val flagKey: String,
    val description: String? = null,
    @field:NotBlank val scope: String,
    val tenantId: UUID? = null,
    val propertyId: UUID? = null,
    val enabled: Boolean,
    val rolloutRules: Map<String, Any?> = emptyMap(),
    @field:NotBlank val reason: String,
)
