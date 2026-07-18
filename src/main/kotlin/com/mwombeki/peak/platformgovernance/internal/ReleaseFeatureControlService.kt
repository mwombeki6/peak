package com.mwombeki.peak.platformgovernance.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.PlatformAuditEvent
import com.mwombeki.peak.platformgovernance.api.AssignPlatformReleaseCommand
import com.mwombeki.peak.platformgovernance.api.ChangePlatformReleaseCommand
import com.mwombeki.peak.platformgovernance.api.CreatePlatformReleaseCommand
import com.mwombeki.peak.platformgovernance.api.EffectiveFeatureFlag
import com.mwombeki.peak.platformgovernance.api.FeatureControlPort
import com.mwombeki.peak.platformgovernance.api.FeatureFlagSummary
import com.mwombeki.peak.platformgovernance.api.FleetControlConflictException
import com.mwombeki.peak.platformgovernance.api.FleetControlNotFoundException
import com.mwombeki.peak.platformgovernance.api.PlatformReleaseAssignmentSummary
import com.mwombeki.peak.platformgovernance.api.PlatformReleaseSummary
import com.mwombeki.peak.platformgovernance.api.ReleaseAction
import com.mwombeki.peak.platformgovernance.api.ReleaseControlPort
import com.mwombeki.peak.platformgovernance.api.UpdateReleaseAssignmentCommand
import com.mwombeki.peak.platformgovernance.api.UpsertFeatureFlagCommand
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.PlatformAccessPort
import com.mwombeki.peak.usermanagement.api.PlatformAccessRequest
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class ReleaseFeatureControlService(
    private val jdbcTemplate: JdbcTemplate,
    private val platformAccess: PlatformAccessPort,
    private val requestContextHolder: RequestContextHolder,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val objectMapper: ObjectMapper,
) : ReleaseControlPort, FeatureControlPort {

    @Transactional(readOnly = true)
    override fun listReleases(): List<PlatformReleaseSummary> {
        requireAccess("platform.releases.view", "platform.releases.list")
        return jdbcTemplate.query(
            "$RELEASE_SELECT ORDER BY release.created_at DESC",
            { rs, _ -> mapRelease(rs) },
        ).map { it.copy(assignments = assignments(it.releaseId)) }
    }

    @Transactional
    override fun createRelease(command: CreatePlatformReleaseCommand): PlatformReleaseSummary {
        requireAccess("platform.releases.manage", "platform.releases.create")
        val version = command.version.trim().also {
            require(it.matches(RELEASE_VERSION)) { "Release version must be semantic v1.x.y" }
        }
        val digest = command.imageDigest.trim().lowercase().also {
            require(it.matches(IMAGE_DIGEST)) { "Release image digest must be immutable SHA-256" }
        }
        require(command.schemaVersion in 1..100_000) { "Invalid release schema version" }
        return mutate(
            "platform.releases.create", command, PlatformReleaseSummary::class.java,
        ) { reservationId ->
            val id = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO platform_releases (
                    id, version, image_digest, schema_version, release_notes,
                    created_by_platform_user_id
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, version, digest, command.schemaVersion, command.releaseNotes?.trim(),
                currentPlatformUser(),
            )
            release(id).also { recordRelease(it, "platform.release.created", reservationId,
                mapOf("version" to version, "imageDigest" to digest)) }
        }
    }

    @Transactional
    override fun changeRelease(command: ChangePlatformReleaseCommand): PlatformReleaseSummary {
        requireAccess("platform.releases.manage", "platform.releases.change")
        require(command.reason.isNotBlank()) { "Release change reason is required" }
        return mutate(
            "platform.releases.change", command, PlatformReleaseSummary::class.java,
        ) { reservationId ->
            val before = lockedRelease(command.releaseId)
            val target = RELEASE_ACTION_TARGET.getValue(command.action)
            if (target !in RELEASE_TRANSITIONS[before.status].orEmpty()) {
                throw FleetControlConflictException(
                    "Release cannot transition from ${before.status} to $target",
                )
            }
            val actor = currentPlatformUser()
            if (command.action == ReleaseAction.APPROVE && actor == before.createdByPlatformUserId) {
                throw FleetControlConflictException("Release approval requires a second platform operator")
            }
            if (command.action == ReleaseAction.APPROVE) {
                jdbcTemplate.update(
                    """
                    UPDATE platform_releases SET status = 'approved',
                        approved_by_platform_user_id = ?, approved_at = now(),
                        release_notes = CONCAT_WS(E'\n', release_notes, ?)
                    WHERE id = ?
                    """.trimIndent(),
                    actor, "Approval: ${command.reason.trim()}", command.releaseId,
                )
            } else {
                jdbcTemplate.update(
                    """
                    UPDATE platform_releases SET status = ?,
                        release_notes = CONCAT_WS(E'\n', release_notes, ?)
                    WHERE id = ?
                    """.trimIndent(),
                    target, "${command.action.name}: ${command.reason.trim()}", command.releaseId,
                )
            }
            release(command.releaseId).also {
                recordRelease(it, "platform.release.${target}", reservationId,
                    mapOf("previousStatus" to before.status, "status" to target,
                        "reason" to command.reason.trim()))
            }
        }
    }

    @Transactional
    override fun assignRelease(
        command: AssignPlatformReleaseCommand,
    ): PlatformReleaseAssignmentSummary {
        requireAccess("platform.releases.manage", "platform.releases.assign")
        val release = release(command.releaseId)
        if (release.status !in DEPLOYABLE_RELEASE_STATUSES) {
            throw FleetControlConflictException("Only approved releases can be assigned")
        }
        val channel = command.releaseChannel.trim().lowercase().also {
            require(it in RELEASE_CHANNELS) { "Invalid release channel" }
        }
        command.tenantId?.let(::requireTenantExists)
        return mutate(
            "platform.releases.assign", command, PlatformReleaseAssignmentSummary::class.java,
        ) { reservationId ->
            val id = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO platform_release_assignments (
                    id, release_id, tenant_id, release_channel, status,
                    desired_version, scheduled_at
                ) VALUES (?, ?, ?, ?, 'scheduled', ?, COALESCE(?, now()))
                """.trimIndent(),
                id, command.releaseId, command.tenantId, channel,
                release.version, command.scheduledAt,
            )
            assignment(id).also {
                recordAssignment(it, "platform.release.assignment.scheduled", reservationId)
            }
        }
    }

    @Transactional
    override fun updateAssignment(
        command: UpdateReleaseAssignmentCommand,
    ): PlatformReleaseAssignmentSummary {
        requireAccess("platform.releases.manage", "platform.releases.assignment.update")
        val status = command.status.trim().lowercase().also {
            require(it in ASSIGNMENT_STATUSES) { "Invalid release assignment status" }
        }
        return mutate(
            "platform.releases.assignment.update", command,
            PlatformReleaseAssignmentSummary::class.java,
        ) { reservationId ->
            val before = lockedAssignment(command.assignmentId)
            if (status != before.status && status !in ASSIGNMENT_TRANSITIONS[before.status].orEmpty()) {
                throw FleetControlConflictException(
                    "Release assignment cannot transition from ${before.status} to $status",
                )
            }
            if (status == "verified" && command.actualVersion != before.desiredVersion) {
                throw FleetControlConflictException(
                    "Verified assignment actual version must match desired version",
                )
            }
            if (status == "rolled_back" && command.rollbackReleaseId == null) {
                throw IllegalArgumentException("Rollback release is required")
            }
            command.rollbackReleaseId?.let(::release)
            jdbcTemplate.update(
                """
                UPDATE platform_release_assignments SET status = ?,
                    actual_version = COALESCE(?, actual_version),
                    rollback_release_id = COALESCE(?, rollback_release_id),
                    error_detail = ?,
                    started_at = CASE WHEN ? = 'running' THEN COALESCE(started_at, now()) ELSE started_at END,
                    completed_at = CASE WHEN ? IN ('verified', 'failed', 'rolled_back') THEN now()
                        ELSE completed_at END
                WHERE id = ?
                """.trimIndent(),
                status, command.actualVersion, command.rollbackReleaseId,
                command.errorDetail?.take(2000), status, status, command.assignmentId,
            )
            assignment(command.assignmentId).also {
                recordAssignment(it, "platform.release.assignment.$status", reservationId)
            }
        }
    }

    @Transactional(readOnly = true)
    override fun listFlags(tenantId: UUID?, propertyId: UUID?): List<FeatureFlagSummary> {
        requireAccess("platform.features.view", "platform.features.list")
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        tenantId?.let { conditions += "flag.tenant_id = ?"; args += it }
        propertyId?.let { conditions += "flag.property_id = ?"; args += it }
        val where = conditions.takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE ").orEmpty()
        return jdbcTemplate.query(
            "$FEATURE_SELECT $where ORDER BY flag.flag_key, flag.scope",
            { rs, _ -> mapFeature(rs) }, *args.toTypedArray(),
        )
    }

    @Transactional
    override fun upsertFlag(command: UpsertFeatureFlagCommand): FeatureFlagSummary {
        requireAccess("platform.features.manage", "platform.features.upsert")
        require(command.reason.isNotBlank()) { "Feature rollout reason is required" }
        val key = command.flagKey.trim().lowercase().also {
            require(it.matches(FEATURE_KEY)) { "Invalid feature flag key" }
        }
        val scope = command.scope.trim().lowercase().also {
            require(it in FEATURE_SCOPES) { "Invalid feature flag scope" }
        }
        when (scope) {
            "platform" -> require(command.tenantId == null && command.propertyId == null) {
                "Platform feature cannot target tenant or property"
            }
            "tenant" -> require(command.tenantId != null && command.propertyId == null) {
                "Tenant feature requires only tenant target"
            }
            "property" -> require(command.tenantId != null && command.propertyId != null) {
                "Property feature requires tenant and property targets"
            }
        }
        command.tenantId?.let(::requireTenantExists)
        command.propertyId?.let { propertyId -> requirePropertyExists(requireNotNull(command.tenantId), propertyId) }
        validateRolloutRules(command.rolloutRules)
        return mutate(
            "platform.features.upsert", command, FeatureFlagSummary::class.java,
        ) { reservationId ->
            val existing = findFeature(key, scope, command.tenantId, command.propertyId)
            val id = existing?.flagId ?: UUID.randomUUID()
            if (existing == null) {
                jdbcTemplate.update(
                    """
                    INSERT INTO feature_flags (
                        id, flag_key, description, scope, tenant_id, property_id,
                        is_enabled, rollout_rules, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """.trimIndent(),
                    id, key, command.description?.trim(), scope, command.tenantId,
                    command.propertyId, command.enabled,
                    objectMapper.writeValueAsString(command.rolloutRules), currentPlatformUser(),
                )
            } else {
                jdbcTemplate.update(
                    """
                    UPDATE feature_flags SET description = ?, is_enabled = ?, rollout_rules = ?::jsonb
                    WHERE id = ?
                    """.trimIndent(),
                    command.description?.trim(), command.enabled,
                    objectMapper.writeValueAsString(command.rolloutRules), id,
                )
            }
            feature(id).also {
                recordFeature(it, "platform.feature.updated", reservationId,
                    mapOf("reason" to command.reason.trim(), "enabled" to command.enabled))
            }
        }
    }

    @Transactional(readOnly = true)
    override fun effectiveFlags(tenantId: UUID, propertyId: UUID?): List<EffectiveFeatureFlag> {
        requireAccess("platform.features.view", "platform.features.effective")
        requireTenantExists(tenantId)
        propertyId?.let { requirePropertyExists(tenantId, it) }
        val flags = jdbcTemplate.query(
            """
            $FEATURE_SELECT
            WHERE flag.scope = 'platform'
               OR (flag.scope = 'tenant' AND flag.tenant_id = ?)
               OR (flag.scope = 'property' AND flag.tenant_id = ? AND flag.property_id = ?)
            ORDER BY CASE flag.scope WHEN 'property' THEN 0 WHEN 'tenant' THEN 1 ELSE 2 END
            """.trimIndent(),
            { rs, _ -> mapFeature(rs) }, tenantId, tenantId, propertyId,
        )
        return flags.distinctBy { it.flagKey }.map { flag ->
            EffectiveFeatureFlag(
                flag.flagKey,
                enabled = flag.enabled && includedInPercentage(
                    flag.flagKey, tenantId, propertyId, flag.rolloutRules,
                ),
                sourceScope = flag.scope,
                rolloutRules = flag.rolloutRules,
            )
        }.sortedBy { it.flagKey }
    }

    private fun release(id: UUID) = releaseQuery(id, lock = false)
    private fun lockedRelease(id: UUID) = releaseQuery(id, lock = true)
    private fun releaseQuery(id: UUID, lock: Boolean): PlatformReleaseSummary = jdbcTemplate.query(
        "$RELEASE_SELECT WHERE release.id = ?${if (lock) " FOR UPDATE" else ""}",
        { rs, _ -> mapRelease(rs) }, id,
    ).singleOrNull()?.let { it.copy(assignments = assignments(it.releaseId)) }
        ?: throw FleetControlNotFoundException("Platform release was not found")

    private fun mapRelease(rs: ResultSet) = PlatformReleaseSummary(
        rs.getObject("id", UUID::class.java), rs.getString("version"),
        rs.getString("image_digest"), rs.getInt("schema_version"), rs.getString("status"),
        rs.getString("release_notes"), rs.getObject("created_by_platform_user_id", UUID::class.java),
        rs.getObject("approved_by_platform_user_id", UUID::class.java),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("approved_at")?.toInstant(),
        emptyList(),
    )

    private fun assignments(releaseId: UUID) = jdbcTemplate.query(
        "$ASSIGNMENT_SELECT WHERE assignment.release_id = ? ORDER BY assignment.scheduled_at DESC",
        { rs, _ -> mapAssignment(rs) }, releaseId,
    )

    private fun assignment(id: UUID) = assignmentQuery(id, false)
    private fun lockedAssignment(id: UUID) = assignmentQuery(id, true)
    private fun assignmentQuery(id: UUID, lock: Boolean): PlatformReleaseAssignmentSummary =
        jdbcTemplate.query(
            "$ASSIGNMENT_SELECT WHERE assignment.id = ?${if (lock) " FOR UPDATE" else ""}",
            { rs, _ -> mapAssignment(rs) }, id,
        ).singleOrNull() ?: throw FleetControlNotFoundException("Release assignment was not found")

    private fun mapAssignment(rs: ResultSet) = PlatformReleaseAssignmentSummary(
        rs.getObject("id", UUID::class.java), rs.getObject("release_id", UUID::class.java),
        rs.getObject("tenant_id", UUID::class.java), rs.getString("release_channel"),
        rs.getString("status"), rs.getString("desired_version"), rs.getString("actual_version"),
        rs.getTimestamp("scheduled_at").toInstant(), rs.getTimestamp("started_at")?.toInstant(),
        rs.getTimestamp("completed_at")?.toInstant(),
        rs.getObject("rollback_release_id", UUID::class.java), rs.getString("error_detail"),
    )

    private fun findFeature(
        key: String, scope: String, tenantId: UUID?, propertyId: UUID?,
    ): FeatureFlagSummary? = jdbcTemplate.query(
        """
        $FEATURE_SELECT WHERE flag.flag_key = ? AND flag.scope = ?
          AND flag.tenant_id IS NOT DISTINCT FROM ?
          AND flag.property_id IS NOT DISTINCT FROM ?
        """.trimIndent(),
        { rs, _ -> mapFeature(rs) }, key, scope, tenantId, propertyId,
    ).singleOrNull()

    private fun feature(id: UUID): FeatureFlagSummary = jdbcTemplate.query(
        "$FEATURE_SELECT WHERE flag.id = ?", { rs, _ -> mapFeature(rs) }, id,
    ).singleOrNull() ?: throw FleetControlNotFoundException("Feature flag was not found")

    private fun mapFeature(rs: ResultSet) = FeatureFlagSummary(
        rs.getObject("id", UUID::class.java), rs.getString("flag_key"),
        rs.getString("description"), rs.getString("scope"),
        rs.getObject("tenant_id", UUID::class.java), rs.getObject("property_id", UUID::class.java),
        rs.getBoolean("is_enabled"), jsonMap(rs.getString("rollout_rules")),
        rs.getTimestamp("updated_at").toInstant(),
    )

    private fun recordRelease(
        release: PlatformReleaseSummary, action: String, key: UUID, payload: Map<String, Any?>,
    ) = record(action, "platform_releases", release.releaseId, null, payload, key)

    private fun recordAssignment(
        assignment: PlatformReleaseAssignmentSummary, action: String, key: UUID,
    ) = record(action, "platform_release_assignments", assignment.assignmentId,
        assignment.tenantId, mapOf("status" to assignment.status,
            "desiredVersion" to assignment.desiredVersion,
            "actualVersion" to assignment.actualVersion), key)

    private fun recordFeature(
        flag: FeatureFlagSummary, action: String, key: UUID, payload: Map<String, Any?>,
    ) = record(action, "feature_flags", flag.flagId, flag.tenantId,
        payload + mapOf("flagKey" to flag.flagKey, "scope" to flag.scope), key)

    private fun record(
        action: String, type: String, id: UUID, tenantId: UUID?,
        payload: Map<String, Any?>, reservationId: UUID,
    ) {
        auditPort.recordPlatformEvent(PlatformAuditEvent(
            action = action, resource = AuditResource(type, id),
            targetTenantId = tenantId, after = payload,
        ))
        outboxPort.enqueue(OutboxEventCommand(
            aggregateType = type, aggregateId = id, tenantId = null,
            eventType = action, destination = OutboxDestination.PLATFORM,
            payload = payload, idempotencyKeyId = reservationId, priority = 3,
        ))
    }

    private fun <T : Any> mutate(
        operation: String, payload: Any, type: Class<T>, block: (UUID) -> T,
    ): T = try {
        when (val reservation = idempotencyPort.reserve(
            IdempotencyCommand(operation, payload, "platform_release_control"),
        )) {
            is IdempotencyReservation.Started -> block(reservation.recordId).also {
                idempotencyPort.markSucceeded(reservation.recordId, 200, it, entityId(it))
            }
            is IdempotencyReservation.Replay -> objectMapper.readValue(
                requireNotNull(reservation.responseBody) { "Stored release response is missing" }, type,
            )
            is IdempotencyReservation.InProgress -> throw FleetControlConflictException(
                "Release command is already in progress",
            )
            is IdempotencyReservation.Conflict -> throw FleetControlConflictException(
                "Idempotency key was used for another release command",
            )
        }
    } catch (ex: DuplicateKeyException) {
        throw FleetControlConflictException("Release or rollout target already exists")
    }

    private fun entityId(value: Any): UUID? = when (value) {
        is PlatformReleaseSummary -> value.releaseId
        is PlatformReleaseAssignmentSummary -> value.assignmentId
        is FeatureFlagSummary -> value.flagId
        else -> null
    }

    private fun requireAccess(permission: String, operation: String) =
        platformAccess.requireAuthorized(PlatformAccessRequest(null, permission, operation))

    private fun currentPlatformUser(): UUID = when (val identity = requestContextHolder.current().identity) {
        is RequestIdentity.Platform -> identity.platformUserId
        else -> throw IllegalStateException("Direct platform identity is required")
    }

    private fun requireTenantExists(id: UUID) {
        val exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM tenants WHERE id = ? AND deleted_at IS NULL)",
            Boolean::class.java, id,
        ) == true
        if (!exists) throw FleetControlNotFoundException("Release tenant was not found")
    }

    private fun requirePropertyExists(tenantId: UUID, propertyId: UUID) {
        val exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM properties WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL)",
            Boolean::class.java, tenantId, propertyId,
        ) == true
        if (!exists) throw FleetControlNotFoundException("Feature property was not found")
    }

    private fun validateRolloutRules(rules: Map<String, Any?>) {
        val percentage = (rules["percentage"] as? Number)?.toInt() ?: 100
        require(percentage in 0..100) { "Feature rollout percentage must be between 0 and 100" }
        require(rules.keys.all { it in FEATURE_RULE_KEYS }) { "Unsupported feature rollout rule" }
    }

    private fun includedInPercentage(
        key: String, tenantId: UUID, propertyId: UUID?, rules: Map<String, Any?>,
    ): Boolean {
        val percentage = (rules["percentage"] as? Number)?.toInt() ?: 100
        if (percentage == 100) return true
        if (percentage == 0) return false
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$key:$tenantId:${propertyId.orEmpty()}".toByteArray())
        val bucket = (ByteBuffer.wrap(bytes.copyOfRange(0, 4)).int.toLong() and 0xffffffffL) % 100
        return bucket < percentage
    }

    private fun UUID?.orEmpty(): String = this?.toString().orEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun jsonMap(raw: String): Map<String, Any?> =
        objectMapper.readValue(raw, Map::class.java) as Map<String, Any?>

    private companion object {
        val RELEASE_VERSION = Regex("v1\\.[0-9]+\\.[0-9]+(?:-[a-z0-9.-]+)?")
        val IMAGE_DIGEST = Regex("sha256:[0-9a-f]{64}")
        val FEATURE_KEY = Regex("[a-z][a-z0-9_.-]{1,99}")
        val FEATURE_SCOPES = setOf("platform", "tenant", "property")
        val FEATURE_RULE_KEYS = setOf("percentage", "startsAt", "endsAt", "cohort")
        val RELEASE_CHANNELS = setOf("canary", "early_access", "stable", "long_term_support")
        val DEPLOYABLE_RELEASE_STATUSES = setOf("approved", "canary", "rolling_out", "stable")
        val ASSIGNMENT_STATUSES = setOf("scheduled", "running", "verified", "failed", "paused", "rolled_back")
        val RELEASE_ACTION_TARGET = mapOf(
            ReleaseAction.APPROVE to "approved",
            ReleaseAction.START_CANARY to "canary",
            ReleaseAction.START_ROLLOUT to "rolling_out",
            ReleaseAction.MARK_STABLE to "stable",
            ReleaseAction.PAUSE to "paused",
            ReleaseAction.RECALL to "recalled",
        )
        val RELEASE_TRANSITIONS = mapOf(
            "draft" to setOf("approved"),
            "approved" to setOf("canary", "rolling_out", "paused", "recalled"),
            "canary" to setOf("rolling_out", "paused", "recalled"),
            "rolling_out" to setOf("stable", "paused", "recalled"),
            "stable" to setOf("paused", "recalled"),
            "paused" to setOf("canary", "rolling_out", "recalled"),
            "recalled" to emptySet(),
        )
        val ASSIGNMENT_TRANSITIONS = mapOf(
            "scheduled" to setOf("running", "paused", "failed"),
            "running" to setOf("verified", "failed", "paused", "rolled_back"),
            "paused" to setOf("running", "rolled_back"),
            "failed" to setOf("scheduled", "rolled_back"),
            "verified" to setOf("rolled_back"),
            "rolled_back" to emptySet(),
        )
        val RELEASE_SELECT = """
            SELECT release.id, release.version, release.image_digest, release.schema_version,
                   release.status, release.release_notes,
                   release.created_by_platform_user_id, release.approved_by_platform_user_id,
                   release.created_at, release.approved_at FROM platform_releases release
        """.trimIndent()
        val ASSIGNMENT_SELECT = """
            SELECT assignment.id, assignment.release_id, assignment.tenant_id,
                   assignment.release_channel, assignment.status, assignment.desired_version,
                   assignment.actual_version, assignment.scheduled_at, assignment.started_at,
                   assignment.completed_at, assignment.rollback_release_id,
                   assignment.error_detail FROM platform_release_assignments assignment
        """.trimIndent()
        val FEATURE_SELECT = """
            SELECT flag.id, flag.flag_key, flag.description, flag.scope, flag.tenant_id,
                   flag.property_id, flag.is_enabled, flag.rollout_rules, flag.updated_at
            FROM feature_flags flag
        """.trimIndent()
    }
}
