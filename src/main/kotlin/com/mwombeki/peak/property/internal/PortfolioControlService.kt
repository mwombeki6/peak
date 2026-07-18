package com.mwombeki.peak.property.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.property.api.AddPortfolioRevisionCommand
import com.mwombeki.peak.property.api.AssignPortfolioPropertyCommand
import com.mwombeki.peak.property.api.ChangePortfolioTemplateCommand
import com.mwombeki.peak.property.api.CreateOrganizationUnitCommand
import com.mwombeki.peak.property.api.CreatePortfolioTemplateCommand
import com.mwombeki.peak.property.api.EffectivePortfolioConfig
import com.mwombeki.peak.property.api.OrganizationUnitSummary
import com.mwombeki.peak.property.api.PortfolioAssignmentSummary
import com.mwombeki.peak.property.api.PortfolioControlConflictException
import com.mwombeki.peak.property.api.PortfolioControlNotFoundException
import com.mwombeki.peak.property.api.PortfolioControlPort
import com.mwombeki.peak.property.api.PortfolioOverview
import com.mwombeki.peak.property.api.PortfolioPropertyAssignment
import com.mwombeki.peak.property.api.PortfolioRevisionSummary
import com.mwombeki.peak.property.api.PortfolioRolloutTarget
import com.mwombeki.peak.property.api.PortfolioTemplateAction
import com.mwombeki.peak.property.api.PortfolioTemplateSummary
import com.mwombeki.peak.property.api.RolloutPortfolioConfigCommand
import com.mwombeki.peak.property.api.UpdateOrganizationUnitCommand
import com.mwombeki.peak.property.api.UpdatePortfolioRolloutCommand
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessPort
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessRequest
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class PortfolioControlService(
    private val jdbcTemplate: JdbcTemplate,
    private val tenantAccess: TenantPermissionAccessPort,
    private val requestContextHolder: RequestContextHolder,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val objectMapper: ObjectMapper,
) : PortfolioControlPort {

    @Transactional(readOnly = true)
    override fun overview(tenantId: UUID): PortfolioOverview {
        requireAccess(tenantId, "tenant.portfolio.view", "tenant.portfolio.view")
        return PortfolioOverview(
            units = jdbcTemplate.query(
                "$UNIT_SELECT WHERE unit.tenant_id = ? ORDER BY unit.path",
                { rs, _ -> mapUnit(rs) }, tenantId,
            ).map(::withProperties),
            templates = jdbcTemplate.query(
                "$TEMPLATE_SELECT WHERE template.tenant_id = ? ORDER BY template.config_domain, template.name",
                { rs, _ -> mapTemplate(rs) }, tenantId,
            ).map(::withRevisions),
            recentAssignments = jdbcTemplate.query(
                "$ASSIGNMENT_SELECT WHERE assignment.tenant_id = ? " +
                    "ORDER BY assignment.created_at DESC LIMIT 200",
                { rs, _ -> mapAssignment(rs) }, tenantId,
            ),
        )
    }

    @Transactional
    override fun createUnit(command: CreateOrganizationUnitCommand): OrganizationUnitSummary {
        requireAccess(command.tenantId, "tenant.portfolio.manage", "tenant.portfolio.unit.create")
        val type = command.unitType.normalizedUnitType()
        val code = command.code.normalizedCode()
        require(command.name.isNotBlank() && command.name.length <= 200) { "Organization unit name is required" }
        return mutate(
            "tenant.portfolio.unit.create", command, OrganizationUnitSummary::class.java,
        ) { reservationId ->
            val parent = command.parentId?.let { lockedUnit(command.tenantId, it) }
            val path = "${parent?.path.orEmpty()}/$code".replace("//", "/")
            require(path.length <= 500) { "Organization hierarchy path is too deep" }
            val id = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO organization_units (
                    id, tenant_id, parent_id, unit_type, code, name, path, created_by_user_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, command.tenantId, command.parentId, type, code,
                command.name.trim(), path, currentTenantUser(command.tenantId),
            )
            unit(command.tenantId, id).also {
                record(command.tenantId, "tenant.portfolio.unit.created", "organization_units",
                    id, mapOf("path" to path, "unitType" to type), reservationId)
            }
        }
    }

    @Transactional
    override fun updateUnit(command: UpdateOrganizationUnitCommand): OrganizationUnitSummary {
        requireAccess(command.tenantId, "tenant.portfolio.manage", "tenant.portfolio.unit.update")
        return mutate(
            "tenant.portfolio.unit.update", command, OrganizationUnitSummary::class.java,
        ) { reservationId ->
            val before = lockedUnit(command.tenantId, command.unitId)
            if (before.version != command.expectedVersion) {
                throw PortfolioControlConflictException("Organization unit version has changed")
            }
            val status = command.status?.trim()?.lowercase() ?: before.status
            require(status in UNIT_STATUSES) { "Invalid organization unit status" }
            val parentChanged = command.parentId != before.parentId
            val newPath = if (parentChanged) {
                val parent = command.parentId?.let { lockedUnit(command.tenantId, it) }
                if (parent != null && (parent.unitId == before.unitId ||
                    parent.path.startsWith("${before.path}/"))
                ) throw PortfolioControlConflictException("Organization hierarchy cannot contain a cycle")
                "${parent?.path.orEmpty()}/${before.code}".replace("//", "/")
            } else before.path
            require(newPath.length <= 500) { "Organization hierarchy path is too deep" }
            jdbcTemplate.update(
                """
                UPDATE organization_units
                SET parent_id = ?, name = COALESCE(?, name), status = ?, path = ?, version = version + 1
                WHERE tenant_id = ? AND id = ? AND version = ?
                """.trimIndent(),
                command.parentId, command.name?.trim(), status, newPath,
                command.tenantId, command.unitId, command.expectedVersion,
            ).also { if (it != 1) throw PortfolioControlConflictException("Organization unit changed concurrently") }
            if (newPath != before.path) {
                jdbcTemplate.update(
                    """
                    UPDATE organization_units
                    SET path = ? || substring(path from ?), version = version + 1
                    WHERE tenant_id = ? AND path LIKE ?
                    """.trimIndent(),
                    newPath, before.path.length + 1, command.tenantId, "${before.path}/%",
                )
            }
            unit(command.tenantId, command.unitId).also {
                record(command.tenantId, "tenant.portfolio.unit.updated", "organization_units",
                    command.unitId, mapOf("previousPath" to before.path, "path" to newPath,
                        "status" to status), reservationId)
            }
        }
    }

    @Transactional
    override fun assignProperty(command: AssignPortfolioPropertyCommand): OrganizationUnitSummary {
        requireAccess(command.tenantId, "tenant.portfolio.manage", "tenant.portfolio.property.assign")
        return mutate(
            "tenant.portfolio.property.assign", command, OrganizationUnitSummary::class.java,
        ) { reservationId ->
            lockedUnit(command.tenantId, command.unitId)
            requireProperty(command.tenantId, command.propertyId)
            if (command.primary) {
                jdbcTemplate.update(
                    """
                    UPDATE organization_unit_properties SET is_primary = false
                    WHERE tenant_id = ? AND property_id = ? AND is_primary
                    """.trimIndent(), command.tenantId, command.propertyId,
                )
            }
            jdbcTemplate.update(
                """
                INSERT INTO organization_unit_properties (
                    tenant_id, organization_unit_id, property_id, is_primary, assigned_by_user_id
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (organization_unit_id, property_id) DO UPDATE SET
                    is_primary = EXCLUDED.is_primary,
                    assigned_by_user_id = EXCLUDED.assigned_by_user_id,
                    assigned_at = now()
                """.trimIndent(),
                command.tenantId, command.unitId, command.propertyId, command.primary,
                currentTenantUser(command.tenantId),
            )
            unit(command.tenantId, command.unitId).also {
                record(command.tenantId, "tenant.portfolio.property.assigned",
                    "organization_unit_properties", command.unitId,
                    mapOf("propertyId" to command.propertyId, "primary" to command.primary),
                    reservationId)
            }
        }
    }

    @Transactional
    override fun createTemplate(command: CreatePortfolioTemplateCommand): PortfolioTemplateSummary {
        requireAccess(command.tenantId, "tenant.portfolio.manage", "tenant.portfolio.template.create")
        require(command.name.isNotBlank() && command.name.length <= 200) { "Template name is required" }
        val domain = command.configDomain.normalizedDomain()
        return mutate(
            "tenant.portfolio.template.create", command, PortfolioTemplateSummary::class.java,
        ) { reservationId ->
            val id = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO portfolio_config_templates (
                    id, tenant_id, name, config_domain, created_by_user_id
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                id, command.tenantId, command.name.trim(), domain,
                currentTenantUser(command.tenantId),
            )
            template(command.tenantId, id).also {
                record(command.tenantId, "tenant.portfolio.template.created",
                    "portfolio_config_templates", id, mapOf("configDomain" to domain), reservationId)
            }
        }
    }

    @Transactional
    override fun addRevision(command: AddPortfolioRevisionCommand): PortfolioTemplateSummary {
        requireAccess(command.tenantId, "tenant.portfolio.manage", "tenant.portfolio.revision.create")
        require(command.config.isNotEmpty()) { "Portfolio configuration cannot be empty" }
        require(command.changeSummary.isNotBlank() && command.changeSummary.length <= 1000) {
            "Revision change summary is required"
        }
        return mutate(
            "tenant.portfolio.revision.create", command, PortfolioTemplateSummary::class.java,
        ) { reservationId ->
            val before = lockedTemplate(command.tenantId, command.templateId)
            if (before.status == "retired") {
                throw PortfolioControlConflictException("Retired template cannot receive revisions")
            }
            val revision = before.currentRevision + 1
            val json = objectMapper.writeValueAsString(command.config)
            val hash = jdbcTemplate.queryForObject(
                "SELECT encode(digest((?::jsonb)::text, 'sha256'), 'hex')",
                String::class.java, json,
            ) ?: error("Failed to hash portfolio configuration")
            jdbcTemplate.update(
                """
                INSERT INTO portfolio_config_revisions (
                    tenant_id, template_id, revision, config_json, content_hash,
                    change_summary, created_by_user_id
                ) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
                """.trimIndent(),
                command.tenantId, command.templateId, revision, json, hash,
                command.changeSummary.trim(), currentTenantUser(command.tenantId),
            )
            jdbcTemplate.update(
                "UPDATE portfolio_config_templates SET current_revision = ? WHERE tenant_id = ? AND id = ?",
                revision, command.tenantId, command.templateId,
            )
            template(command.tenantId, command.templateId).also {
                record(command.tenantId, "tenant.portfolio.revision.created",
                    "portfolio_config_templates", command.templateId,
                    mapOf("revision" to revision, "contentHash" to hash), reservationId)
            }
        }
    }

    @Transactional
    override fun changeTemplate(command: ChangePortfolioTemplateCommand): PortfolioTemplateSummary {
        requireAccess(command.tenantId, "tenant.portfolio.manage", "tenant.portfolio.template.change")
        require(command.reason.isNotBlank()) { "Template change reason is required" }
        return mutate(
            "tenant.portfolio.template.change", command, PortfolioTemplateSummary::class.java,
        ) { reservationId ->
            val before = lockedTemplate(command.tenantId, command.templateId)
            val target = when (command.action) {
                PortfolioTemplateAction.ACTIVATE -> "active"
                PortfolioTemplateAction.RETIRE -> "retired"
                PortfolioTemplateAction.REOPEN -> "draft"
            }
            if (command.action == PortfolioTemplateAction.ACTIVATE && before.currentRevision == 0) {
                throw PortfolioControlConflictException("Template needs a revision before activation")
            }
            if (target !in TEMPLATE_TRANSITIONS[before.status].orEmpty()) {
                throw PortfolioControlConflictException(
                    "Template cannot transition from ${before.status} to $target",
                )
            }
            jdbcTemplate.update(
                "UPDATE portfolio_config_templates SET status = ? WHERE tenant_id = ? AND id = ?",
                target, command.tenantId, command.templateId,
            )
            template(command.tenantId, command.templateId).also {
                record(command.tenantId, "tenant.portfolio.template.$target",
                    "portfolio_config_templates", command.templateId,
                    mapOf("previousStatus" to before.status, "status" to target,
                        "reason" to command.reason.trim()), reservationId)
            }
        }
    }

    @Transactional
    override fun rollout(command: RolloutPortfolioConfigCommand): List<PortfolioAssignmentSummary> {
        requireAccess(command.tenantId, "tenant.portfolio.manage", "tenant.portfolio.rollout.create")
        require(command.targets.isNotEmpty() && command.targets.size <= 200) {
            "Portfolio rollout requires between 1 and 200 targets"
        }
        return mutate(
            "tenant.portfolio.rollout.create", command, Array<PortfolioAssignmentSummary>::class.java,
        ) { reservationId ->
            val template = lockedTemplate(command.tenantId, command.templateId)
            if (template.status != "active") throw PortfolioControlConflictException(
                "Only active templates can be rolled out",
            )
            if (command.revision !in 1..template.currentRevision ||
                revision(command.tenantId, command.templateId, command.revision) == null
            ) throw PortfolioControlNotFoundException("Portfolio template revision was not found")
            val userId = currentTenantUser(command.tenantId)
            command.targets.map { target ->
                validateTarget(command.tenantId, target)
                val previous = currentAppliedRevision(command.tenantId, command.templateId, target)
                val id = UUID.randomUUID()
                jdbcTemplate.update(
                    """
                    INSERT INTO portfolio_config_assignments (
                        id, tenant_id, template_id, revision, organization_unit_id,
                        property_id, status, override_json, previous_revision,
                        scheduled_at, applied_by_user_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, COALESCE(?, now()), ?)
                    """.trimIndent(),
                    id, command.tenantId, command.templateId, command.revision,
                    target.organizationUnitId, target.propertyId,
                    if (command.canary) "canary" else "scheduled",
                    objectMapper.writeValueAsString(target.overrides), previous,
                    command.scheduledAt, userId,
                )
                assignment(command.tenantId, id)
            }.toTypedArray().also { assignments ->
                record(command.tenantId, "tenant.portfolio.rollout.scheduled",
                    "portfolio_config_templates", command.templateId,
                    mapOf("revision" to command.revision, "canary" to command.canary,
                        "assignmentIds" to assignments.map { it.assignmentId }), reservationId)
            }
        }.toList()
    }

    @Transactional
    override fun updateRollout(command: UpdatePortfolioRolloutCommand): PortfolioAssignmentSummary {
        requireAccess(command.tenantId, "tenant.portfolio.manage", "tenant.portfolio.rollout.update")
        val target = command.status.trim().lowercase().also {
            require(it in ASSIGNMENT_STATUSES) { "Invalid rollout status" }
        }
        return mutate(
            "tenant.portfolio.rollout.update", command, PortfolioAssignmentSummary::class.java,
        ) { reservationId ->
            val before = lockedAssignment(command.tenantId, command.assignmentId)
            if (target != before.status && target !in ASSIGNMENT_TRANSITIONS[before.status].orEmpty()) {
                throw PortfolioControlConflictException(
                    "Rollout cannot transition from ${before.status} to $target",
                )
            }
            if (target == "rolled_back" && before.previousRevision == null) {
                throw PortfolioControlConflictException("Rollout has no previous revision to restore")
            }
            val effectiveRevision = if (target == "rolled_back") {
                requireNotNull(before.previousRevision)
            } else before.revision
            jdbcTemplate.update(
                """
                UPDATE portfolio_config_assignments
                SET status = ?, revision = ?,
                    applied_at = CASE WHEN ? IN ('applied', 'rolled_back') THEN now() ELSE applied_at END,
                    applied_by_user_id = ?, error_detail = ?
                WHERE tenant_id = ? AND id = ?
                """.trimIndent(),
                target, effectiveRevision, target, currentTenantUser(command.tenantId),
                command.errorDetail?.take(2000), command.tenantId, command.assignmentId,
            )
            assignment(command.tenantId, command.assignmentId).also {
                record(command.tenantId, "tenant.portfolio.rollout.$target",
                    "portfolio_config_assignments", command.assignmentId,
                    mapOf("previousStatus" to before.status, "status" to target,
                        "revision" to effectiveRevision), reservationId)
            }
        }
    }

    @Transactional(readOnly = true)
    override fun effectiveConfig(
        tenantId: UUID,
        propertyId: UUID,
        domain: String,
    ): EffectivePortfolioConfig? {
        requireAccess(tenantId, "tenant.portfolio.view", "tenant.portfolio.effective")
        requireProperty(tenantId, propertyId)
        val normalizedDomain = domain.normalizedDomain()
        return jdbcTemplate.query(
            """
            SELECT assignment.template_id, assignment.revision,
                   CASE WHEN assignment.property_id IS NOT NULL THEN 'property'
                        ELSE 'organization_unit' END AS source,
                   revision.config_json || assignment.override_json AS effective_config,
                   encode(digest(((revision.config_json || assignment.override_json)::jsonb)::text,
                       'sha256'), 'hex') AS content_hash
            FROM portfolio_config_assignments assignment
            JOIN portfolio_config_templates template
              ON template.tenant_id = assignment.tenant_id AND template.id = assignment.template_id
            JOIN portfolio_config_revisions revision
              ON revision.tenant_id = assignment.tenant_id
             AND revision.template_id = assignment.template_id
             AND revision.revision = assignment.revision
            LEFT JOIN organization_units target_unit
              ON target_unit.tenant_id = assignment.tenant_id
             AND target_unit.id = assignment.organization_unit_id
            WHERE assignment.tenant_id = ? AND template.config_domain = ?
              AND template.status = 'active' AND assignment.status IN ('applied', 'rolled_back')
              AND (
                  assignment.property_id = ?
                  OR EXISTS (
                      SELECT 1 FROM organization_unit_properties membership
                      JOIN organization_units assigned_unit
                        ON assigned_unit.tenant_id = membership.tenant_id
                       AND assigned_unit.id = membership.organization_unit_id
                      WHERE membership.tenant_id = assignment.tenant_id
                        AND membership.property_id = ?
                        AND (assigned_unit.path = target_unit.path
                             OR assigned_unit.path LIKE target_unit.path || '/%')
                  )
              )
            ORDER BY CASE WHEN assignment.property_id IS NOT NULL THEN 0 ELSE 1 END,
                     length(target_unit.path) DESC NULLS LAST,
                     assignment.applied_at DESC, assignment.id DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> EffectivePortfolioConfig(
                propertyId, normalizedDomain,
                rs.getObject("template_id", UUID::class.java), rs.getInt("revision"),
                rs.getString("source"), jsonMap(rs.getString("effective_config")),
                rs.getString("content_hash"),
            ) },
            tenantId, normalizedDomain, propertyId, propertyId,
        ).singleOrNull()
    }

    private fun unit(tenantId: UUID, id: UUID) = unitQuery(tenantId, id, false)
    private fun lockedUnit(tenantId: UUID, id: UUID) = unitQuery(tenantId, id, true)
    private fun unitQuery(tenantId: UUID, id: UUID, lock: Boolean): OrganizationUnitSummary =
        jdbcTemplate.query(
            "$UNIT_SELECT WHERE unit.tenant_id = ? AND unit.id = ?${if (lock) " FOR UPDATE" else ""}",
            { rs, _ -> mapUnit(rs) }, tenantId, id,
        ).singleOrNull()?.let(::withProperties)
            ?: throw PortfolioControlNotFoundException("Organization unit was not found")

    private fun mapUnit(rs: ResultSet) = OrganizationUnitSummary(
        rs.getObject("id", UUID::class.java), rs.getObject("tenant_id", UUID::class.java),
        rs.getObject("parent_id", UUID::class.java), rs.getString("unit_type"),
        rs.getString("code"), rs.getString("name"), rs.getString("status"),
        rs.getString("path"), rs.getLong("version"), emptyList(),
    )

    private fun withProperties(unit: OrganizationUnitSummary) = unit.copy(
        properties = jdbcTemplate.query(
            """
            SELECT membership.property_id, property.name, membership.is_primary,
                   membership.assigned_at
            FROM organization_unit_properties membership
            JOIN properties property ON property.tenant_id = membership.tenant_id
                                    AND property.id = membership.property_id
            WHERE membership.tenant_id = ? AND membership.organization_unit_id = ?
            ORDER BY membership.is_primary DESC, property.name
            """.trimIndent(),
            { rs, _ -> PortfolioPropertyAssignment(
                rs.getObject("property_id", UUID::class.java), rs.getString("name"),
                rs.getBoolean("is_primary"), rs.getTimestamp("assigned_at").toInstant(),
            ) }, unit.tenantId, unit.unitId,
        ),
    )

    private fun template(tenantId: UUID, id: UUID) = templateQuery(tenantId, id, false)
    private fun lockedTemplate(tenantId: UUID, id: UUID) = templateQuery(tenantId, id, true)
    private fun templateQuery(tenantId: UUID, id: UUID, lock: Boolean): PortfolioTemplateSummary =
        jdbcTemplate.query(
            "$TEMPLATE_SELECT WHERE template.tenant_id = ? AND template.id = ?${if (lock) " FOR UPDATE" else ""}",
            { rs, _ -> mapTemplate(rs) }, tenantId, id,
        ).singleOrNull()?.let(::withRevisions)
            ?: throw PortfolioControlNotFoundException("Portfolio template was not found")

    private fun mapTemplate(rs: ResultSet) = PortfolioTemplateSummary(
        rs.getObject("id", UUID::class.java), rs.getObject("tenant_id", UUID::class.java),
        rs.getString("name"), rs.getString("config_domain"), rs.getString("status"),
        rs.getInt("current_revision"), emptyList(),
    )

    private fun withRevisions(template: PortfolioTemplateSummary) = template.copy(
        revisions = jdbcTemplate.query(
            """
            SELECT id, revision, config_json, content_hash, change_summary, created_at
            FROM portfolio_config_revisions
            WHERE tenant_id = ? AND template_id = ? ORDER BY revision DESC
            """.trimIndent(),
            { rs, _ -> mapRevision(rs) }, template.tenantId, template.templateId,
        ),
    )

    private fun mapRevision(rs: ResultSet) = PortfolioRevisionSummary(
        rs.getObject("id", UUID::class.java), rs.getInt("revision"),
        jsonMap(rs.getString("config_json")), rs.getString("content_hash"),
        rs.getString("change_summary"), rs.getTimestamp("created_at").toInstant(),
    )

    private fun revision(tenantId: UUID, templateId: UUID, revision: Int): PortfolioRevisionSummary? =
        jdbcTemplate.query(
            """
            SELECT id, revision, config_json, content_hash, change_summary, created_at
            FROM portfolio_config_revisions
            WHERE tenant_id = ? AND template_id = ? AND revision = ?
            """.trimIndent(),
            { rs, _ -> mapRevision(rs) }, tenantId, templateId, revision,
        ).singleOrNull()

    private fun assignment(tenantId: UUID, id: UUID) = assignmentQuery(tenantId, id, false)
    private fun lockedAssignment(tenantId: UUID, id: UUID) = assignmentQuery(tenantId, id, true)
    private fun assignmentQuery(
        tenantId: UUID, id: UUID, lock: Boolean,
    ): PortfolioAssignmentSummary = jdbcTemplate.query(
        "$ASSIGNMENT_SELECT WHERE assignment.tenant_id = ? AND assignment.id = ?" +
            if (lock) " FOR UPDATE" else "",
        { rs, _ -> mapAssignment(rs) }, tenantId, id,
    ).singleOrNull() ?: throw PortfolioControlNotFoundException("Portfolio rollout was not found")

    private fun mapAssignment(rs: ResultSet) = PortfolioAssignmentSummary(
        rs.getObject("id", UUID::class.java), rs.getObject("tenant_id", UUID::class.java),
        rs.getObject("template_id", UUID::class.java), rs.getInt("revision"),
        rs.getObject("organization_unit_id", UUID::class.java),
        rs.getObject("property_id", UUID::class.java), rs.getString("status"),
        jsonMap(rs.getString("override_json")),
        rs.getObject("previous_revision")?.let { rs.getInt("previous_revision") },
        rs.getTimestamp("scheduled_at").toInstant(), rs.getTimestamp("applied_at")?.toInstant(),
        rs.getString("error_detail"),
    )

    private fun validateTarget(tenantId: UUID, target: PortfolioRolloutTarget) {
        require((target.organizationUnitId == null) xor (target.propertyId == null)) {
            "Rollout target must specify exactly one organization unit or property"
        }
        target.organizationUnitId?.let { unit(tenantId, it) }
        target.propertyId?.let { requireProperty(tenantId, it) }
    }

    private fun currentAppliedRevision(
        tenantId: UUID, templateId: UUID, target: PortfolioRolloutTarget,
    ): Int? = jdbcTemplate.query(
        """
        SELECT revision FROM portfolio_config_assignments
        WHERE tenant_id = ? AND template_id = ?
          AND organization_unit_id IS NOT DISTINCT FROM ?
          AND property_id IS NOT DISTINCT FROM ?
          AND status IN ('applied', 'rolled_back')
        ORDER BY applied_at DESC, id DESC LIMIT 1
        """.trimIndent(),
        { rs, _ -> rs.getInt("revision") }, tenantId, templateId,
        target.organizationUnitId, target.propertyId,
    ).singleOrNull()

    private fun requireProperty(tenantId: UUID, propertyId: UUID) {
        val exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM properties WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL)",
            Boolean::class.java, tenantId, propertyId,
        ) == true
        if (!exists) throw PortfolioControlNotFoundException("Portfolio property was not found")
    }

    private fun record(
        tenantId: UUID, action: String, type: String, id: UUID,
        payload: Map<String, Any?>, reservationId: UUID,
    ) {
        auditPort.recordTenantEvent(TenantAuditEvent(
            tenantId, action, AuditResource(type, id), after = payload,
        ))
        outboxPort.enqueue(OutboxEventCommand(
            aggregateType = type, aggregateId = id, tenantId = tenantId,
            eventType = action, destination = OutboxDestination.PLATFORM,
            payload = payload, idempotencyKeyId = reservationId, priority = 2,
        ))
    }

    private fun <T : Any> mutate(
        operation: String, payload: Any, type: Class<T>, block: (UUID) -> T,
    ): T = try {
        when (val reservation = idempotencyPort.reserve(
            IdempotencyCommand(operation, payload, "portfolio_control"),
        )) {
            is IdempotencyReservation.Started -> block(reservation.recordId).also {
                idempotencyPort.markSucceeded(reservation.recordId, 200, it, entityId(it))
            }
            is IdempotencyReservation.Replay -> objectMapper.readValue(
                requireNotNull(reservation.responseBody) { "Stored portfolio response is missing" }, type,
            )
            is IdempotencyReservation.InProgress -> throw PortfolioControlConflictException(
                "Portfolio command is already in progress",
            )
            is IdempotencyReservation.Conflict -> throw PortfolioControlConflictException(
                "Idempotency key was used for another portfolio command",
            )
        }
    } catch (ex: DuplicateKeyException) {
        throw PortfolioControlConflictException("Portfolio code, name, path, or assignment already exists")
    }

    private fun entityId(value: Any): UUID? = when (value) {
        is OrganizationUnitSummary -> value.unitId
        is PortfolioTemplateSummary -> value.templateId
        is PortfolioAssignmentSummary -> value.assignmentId
        else -> null
    }

    private fun requireAccess(tenantId: UUID, permission: String, operation: String) =
        tenantAccess.requireAuthorized(TenantPermissionAccessRequest(tenantId, permission, operation))

    private fun currentTenantUser(tenantId: UUID): UUID = when (
        val identity = requestContextHolder.current().identity
    ) {
        is RequestIdentity.Tenant -> identity.tenantUserId.also {
            require(identity.tenantId == tenantId) { "Tenant identity does not match portfolio" }
        }
        else -> throw IllegalStateException("Tenant identity is required")
    }

    private fun String.normalizedUnitType() = trim().lowercase().also {
        require(it in UNIT_TYPES) { "Invalid organization unit type" }
    }

    private fun String.normalizedCode() = trim().lowercase().also {
        require(it.matches(UNIT_CODE)) { "Invalid organization unit code" }
    }

    private fun String.normalizedDomain() = trim().lowercase().also {
        require(it in CONFIG_DOMAINS) { "Invalid portfolio configuration domain" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonMap(raw: String): Map<String, Any?> =
        objectMapper.readValue(raw, Map::class.java) as Map<String, Any?>

    private companion object {
        val UNIT_TYPES = setOf("portfolio", "brand", "region", "hub", "management_group")
        val UNIT_STATUSES = setOf("active", "inactive", "archived")
        val UNIT_CODE = Regex("[a-z0-9][a-z0-9-]{0,59}")
        val CONFIG_DOMAINS = setOf(
            "operations", "security", "finance", "payments", "fiscal", "reporting",
            "communications", "integrations",
        )
        val TEMPLATE_TRANSITIONS = mapOf(
            "draft" to setOf("active", "retired"),
            "active" to setOf("retired"),
            "retired" to setOf("draft"),
        )
        val ASSIGNMENT_STATUSES = setOf("scheduled", "canary", "applying", "applied", "failed", "rolled_back")
        val ASSIGNMENT_TRANSITIONS = mapOf(
            "scheduled" to setOf("canary", "applying", "failed"),
            "canary" to setOf("applying", "applied", "failed"),
            "applying" to setOf("applied", "failed", "rolled_back"),
            "applied" to setOf("rolled_back"),
            "failed" to setOf("scheduled", "applying", "rolled_back"),
            "rolled_back" to emptySet(),
        )
        val UNIT_SELECT = """
            SELECT unit.id, unit.tenant_id, unit.parent_id, unit.unit_type,
                   unit.code, unit.name, unit.status, unit.path, unit.version
            FROM organization_units unit
        """.trimIndent()
        val TEMPLATE_SELECT = """
            SELECT template.id, template.tenant_id, template.name,
                   template.config_domain, template.status, template.current_revision
            FROM portfolio_config_templates template
        """.trimIndent()
        val ASSIGNMENT_SELECT = """
            SELECT assignment.id, assignment.tenant_id, assignment.template_id,
                   assignment.revision, assignment.organization_unit_id,
                   assignment.property_id, assignment.status, assignment.override_json,
                   assignment.previous_revision, assignment.scheduled_at,
                   assignment.applied_at, assignment.error_detail
            FROM portfolio_config_assignments assignment
        """.trimIndent()
    }
}
