package com.mwombeki.peak.platformgovernance.api

import java.time.Instant
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface FleetControlPort {
    fun fleetSnapshot(): FleetSnapshot
    fun registerService(command: RegisterPlatformServiceCommand): PlatformServiceSummary
    fun recordHealth(command: RecordServiceHealthCommand): ServiceHealthSummary
    fun registerJob(command: RegisterPlatformJobCommand): PlatformJobSummary
    fun runJob(command: RunPlatformJobCommand): PlatformJobRunSummary
    fun completeJobRun(command: CompletePlatformJobRunCommand): PlatformJobRunSummary
    fun updateAlert(command: UpdatePlatformAlertCommand): PlatformAlertSummary
    fun createIncident(command: CreatePlatformIncidentCommand): PlatformIncidentSummary
    fun updateIncident(command: UpdatePlatformIncidentCommand): PlatformIncidentSummary
}

data class FleetSnapshot(
    val services: List<PlatformServiceSummary>,
    val recentHealth: List<ServiceHealthSummary>,
    val jobs: List<PlatformJobSummary>,
    val recentJobRuns: List<PlatformJobRunSummary>,
    val openAlerts: List<PlatformAlertSummary>,
    val activeIncidents: List<PlatformIncidentSummary>,
    val generatedAt: Instant,
)

data class PlatformServiceSummary(
    val serviceId: UUID,
    val serviceKey: String,
    val name: String,
    val serviceType: String,
    val ownerTeam: String?,
    val active: Boolean,
    val currentHealth: String,
    val lastCheckedAt: Instant?,
)

data class ServiceHealthSummary(
    val healthCheckId: UUID,
    val serviceId: UUID,
    val checkedAt: Instant,
    val status: String,
    val latencyMs: Int?,
    val details: Map<String, Any?>,
)

data class PlatformJobSummary(
    val jobId: UUID,
    val jobKey: String,
    val serviceId: UUID?,
    val description: String?,
    val scheduleCron: String?,
    val active: Boolean,
)

data class PlatformJobRunSummary(
    val runId: UUID,
    val jobId: UUID,
    val tenantId: UUID?,
    val status: String,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val durationMs: Int?,
    val errorMessage: String?,
    val metadata: Map<String, Any?>,
    val createdAt: Instant,
)

data class PlatformAlertSummary(
    val alertId: UUID,
    val serviceId: UUID?,
    val tenantId: UUID?,
    val alertKey: String,
    val severity: String,
    val status: String,
    val title: String,
    val body: String?,
    val openedAt: Instant,
    val acknowledgedBy: UUID?,
    val acknowledgedAt: Instant?,
    val resolvedAt: Instant?,
)

data class PlatformIncidentSummary(
    val incidentId: UUID,
    val incidentNumber: String,
    val title: String,
    val severity: String,
    val status: String,
    val startedAt: Instant,
    val resolvedAt: Instant?,
    val summary: String?,
    val ownerPlatformUserId: UUID?,
    val updatedAt: Instant,
)

data class RegisterPlatformServiceCommand(
    val serviceKey: String,
    val name: String,
    val serviceType: String,
    val ownerTeam: String?,
    val active: Boolean,
)

data class RecordServiceHealthCommand(
    val serviceId: UUID,
    val status: String,
    val latencyMs: Int?,
    val details: Map<String, Any?>,
)

data class RegisterPlatformJobCommand(
    val jobKey: String,
    val serviceId: UUID?,
    val description: String?,
    val scheduleCron: String?,
    val active: Boolean,
)

data class RunPlatformJobCommand(
    val jobId: UUID,
    val tenantId: UUID?,
    val metadata: Map<String, Any?>,
)

data class CompletePlatformJobRunCommand(
    val runId: UUID,
    val status: String,
    val errorMessage: String?,
    val metadata: Map<String, Any?>,
)

data class UpdatePlatformAlertCommand(
    val alertId: UUID,
    val status: String,
    val reason: String,
)

data class CreatePlatformIncidentCommand(
    val title: String,
    val severity: String,
    val summary: String?,
    val ownerPlatformUserId: UUID?,
)

data class UpdatePlatformIncidentCommand(
    val incidentId: UUID,
    val status: String,
    val severity: String?,
    val summary: String?,
    val ownerPlatformUserId: UUID?,
    val reason: String,
)

@NamedInterface("api")
interface ReleaseControlPort {
    fun listReleases(): List<PlatformReleaseSummary>
    fun createRelease(command: CreatePlatformReleaseCommand): PlatformReleaseSummary
    fun changeRelease(command: ChangePlatformReleaseCommand): PlatformReleaseSummary
    fun assignRelease(command: AssignPlatformReleaseCommand): PlatformReleaseAssignmentSummary
    fun updateAssignment(command: UpdateReleaseAssignmentCommand): PlatformReleaseAssignmentSummary
}

data class PlatformReleaseSummary(
    val releaseId: UUID,
    val version: String,
    val imageDigest: String,
    val schemaVersion: Int,
    val status: String,
    val releaseNotes: String?,
    val createdByPlatformUserId: UUID,
    val approvedByPlatformUserId: UUID?,
    val createdAt: Instant,
    val approvedAt: Instant?,
    val assignments: List<PlatformReleaseAssignmentSummary>,
)

data class PlatformReleaseAssignmentSummary(
    val assignmentId: UUID,
    val releaseId: UUID,
    val tenantId: UUID?,
    val releaseChannel: String,
    val status: String,
    val desiredVersion: String,
    val actualVersion: String?,
    val scheduledAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val rollbackReleaseId: UUID?,
    val errorDetail: String?,
)

data class CreatePlatformReleaseCommand(
    val version: String,
    val imageDigest: String,
    val schemaVersion: Int,
    val releaseNotes: String?,
)

enum class ReleaseAction { APPROVE, START_CANARY, START_ROLLOUT, MARK_STABLE, PAUSE, RECALL }

data class ChangePlatformReleaseCommand(
    val releaseId: UUID,
    val action: ReleaseAction,
    val reason: String,
)

data class AssignPlatformReleaseCommand(
    val releaseId: UUID,
    val tenantId: UUID?,
    val releaseChannel: String,
    val scheduledAt: Instant?,
)

data class UpdateReleaseAssignmentCommand(
    val assignmentId: UUID,
    val status: String,
    val actualVersion: String?,
    val rollbackReleaseId: UUID?,
    val errorDetail: String?,
)

@NamedInterface("api")
interface FeatureControlPort {
    fun listFlags(tenantId: UUID?, propertyId: UUID?): List<FeatureFlagSummary>
    fun upsertFlag(command: UpsertFeatureFlagCommand): FeatureFlagSummary
    fun effectiveFlags(tenantId: UUID, propertyId: UUID?): List<EffectiveFeatureFlag>
}

data class FeatureFlagSummary(
    val flagId: UUID,
    val flagKey: String,
    val description: String?,
    val scope: String,
    val tenantId: UUID?,
    val propertyId: UUID?,
    val enabled: Boolean,
    val rolloutRules: Map<String, Any?>,
    val updatedAt: Instant,
)

data class EffectiveFeatureFlag(
    val flagKey: String,
    val enabled: Boolean,
    val sourceScope: String,
    val rolloutRules: Map<String, Any?>,
)

data class UpsertFeatureFlagCommand(
    val flagKey: String,
    val description: String?,
    val scope: String,
    val tenantId: UUID?,
    val propertyId: UUID?,
    val enabled: Boolean,
    val rolloutRules: Map<String, Any?>,
    val reason: String,
)

class FleetControlNotFoundException(message: String) : RuntimeException(message)
class FleetControlConflictException(message: String) : RuntimeException(message)
