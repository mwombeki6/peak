package com.mwombeki.peak.property.api

import java.time.Instant
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface PortfolioControlPort {
    fun overview(tenantId: UUID): PortfolioOverview
    fun createUnit(command: CreateOrganizationUnitCommand): OrganizationUnitSummary
    fun updateUnit(command: UpdateOrganizationUnitCommand): OrganizationUnitSummary
    fun assignProperty(command: AssignPortfolioPropertyCommand): OrganizationUnitSummary
    fun createTemplate(command: CreatePortfolioTemplateCommand): PortfolioTemplateSummary
    fun addRevision(command: AddPortfolioRevisionCommand): PortfolioTemplateSummary
    fun changeTemplate(command: ChangePortfolioTemplateCommand): PortfolioTemplateSummary
    fun rollout(command: RolloutPortfolioConfigCommand): List<PortfolioAssignmentSummary>
    fun updateRollout(command: UpdatePortfolioRolloutCommand): PortfolioAssignmentSummary
    fun effectiveConfig(tenantId: UUID, propertyId: UUID, domain: String): EffectivePortfolioConfig?
}

data class PortfolioOverview(
    val units: List<OrganizationUnitSummary>,
    val templates: List<PortfolioTemplateSummary>,
    val recentAssignments: List<PortfolioAssignmentSummary>,
)

data class OrganizationUnitSummary(
    val unitId: UUID,
    val tenantId: UUID,
    val parentId: UUID?,
    val unitType: String,
    val code: String,
    val name: String,
    val status: String,
    val path: String,
    val version: Long,
    val properties: List<PortfolioPropertyAssignment>,
)

data class PortfolioPropertyAssignment(
    val propertyId: UUID,
    val propertyName: String,
    val primary: Boolean,
    val assignedAt: Instant,
)

data class PortfolioTemplateSummary(
    val templateId: UUID,
    val tenantId: UUID,
    val name: String,
    val configDomain: String,
    val status: String,
    val currentRevision: Int,
    val revisions: List<PortfolioRevisionSummary>,
)

data class PortfolioRevisionSummary(
    val revisionId: UUID,
    val revision: Int,
    val config: Map<String, Any?>,
    val contentHash: String,
    val changeSummary: String,
    val createdAt: Instant,
)

data class PortfolioAssignmentSummary(
    val assignmentId: UUID,
    val tenantId: UUID,
    val templateId: UUID,
    val revision: Int,
    val organizationUnitId: UUID?,
    val propertyId: UUID?,
    val status: String,
    val overrides: Map<String, Any?>,
    val previousRevision: Int?,
    val scheduledAt: Instant,
    val appliedAt: Instant?,
    val errorDetail: String?,
)

data class EffectivePortfolioConfig(
    val propertyId: UUID,
    val configDomain: String,
    val templateId: UUID,
    val revision: Int,
    val source: String,
    val config: Map<String, Any?>,
    val contentHash: String,
)

data class CreateOrganizationUnitCommand(
    val tenantId: UUID,
    val parentId: UUID?,
    val unitType: String,
    val code: String,
    val name: String,
)

data class UpdateOrganizationUnitCommand(
    val tenantId: UUID,
    val unitId: UUID,
    val parentId: UUID?,
    val name: String?,
    val status: String?,
    val expectedVersion: Long,
)

data class AssignPortfolioPropertyCommand(
    val tenantId: UUID,
    val unitId: UUID,
    val propertyId: UUID,
    val primary: Boolean,
)

data class CreatePortfolioTemplateCommand(
    val tenantId: UUID,
    val name: String,
    val configDomain: String,
)

data class AddPortfolioRevisionCommand(
    val tenantId: UUID,
    val templateId: UUID,
    val config: Map<String, Any?>,
    val changeSummary: String,
)

enum class PortfolioTemplateAction { ACTIVATE, RETIRE, REOPEN }

data class ChangePortfolioTemplateCommand(
    val tenantId: UUID,
    val templateId: UUID,
    val action: PortfolioTemplateAction,
    val reason: String,
)

data class PortfolioRolloutTarget(
    val organizationUnitId: UUID?,
    val propertyId: UUID?,
    val overrides: Map<String, Any?> = emptyMap(),
)

data class RolloutPortfolioConfigCommand(
    val tenantId: UUID,
    val templateId: UUID,
    val revision: Int,
    val targets: List<PortfolioRolloutTarget>,
    val canary: Boolean,
    val scheduledAt: Instant?,
)

data class UpdatePortfolioRolloutCommand(
    val tenantId: UUID,
    val assignmentId: UUID,
    val status: String,
    val errorDetail: String?,
)

class PortfolioControlNotFoundException(message: String) : RuntimeException(message)
class PortfolioControlConflictException(message: String) : RuntimeException(message)
