package com.mwombeki.peak.platformgovernance.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.PlatformAuditEvent
import com.mwombeki.peak.platformgovernance.api.CompletePlatformJobRunCommand
import com.mwombeki.peak.platformgovernance.api.CreatePlatformIncidentCommand
import com.mwombeki.peak.platformgovernance.api.FleetControlConflictException
import com.mwombeki.peak.platformgovernance.api.FleetControlNotFoundException
import com.mwombeki.peak.platformgovernance.api.FleetControlPort
import com.mwombeki.peak.platformgovernance.api.FleetSnapshot
import com.mwombeki.peak.platformgovernance.api.PlatformAlertSummary
import com.mwombeki.peak.platformgovernance.api.PlatformIncidentSummary
import com.mwombeki.peak.platformgovernance.api.PlatformJobRunSummary
import com.mwombeki.peak.platformgovernance.api.PlatformJobSummary
import com.mwombeki.peak.platformgovernance.api.PlatformServiceSummary
import com.mwombeki.peak.platformgovernance.api.RecordServiceHealthCommand
import com.mwombeki.peak.platformgovernance.api.RegisterPlatformJobCommand
import com.mwombeki.peak.platformgovernance.api.RegisterPlatformServiceCommand
import com.mwombeki.peak.platformgovernance.api.RunPlatformJobCommand
import com.mwombeki.peak.platformgovernance.api.ServiceHealthSummary
import com.mwombeki.peak.platformgovernance.api.UpdatePlatformAlertCommand
import com.mwombeki.peak.platformgovernance.api.UpdatePlatformIncidentCommand
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
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class FleetControlService(
    private val jdbcTemplate: JdbcTemplate,
    private val platformAccess: PlatformAccessPort,
    private val requestContextHolder: RequestContextHolder,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val objectMapper: ObjectMapper,
) : FleetControlPort {

    @Transactional(readOnly = true)
    override fun fleetSnapshot(): FleetSnapshot {
        requireAccess("platform.monitoring.view", "platform.monitoring.snapshot")
        return FleetSnapshot(
            services = services(),
            recentHealth = jdbcTemplate.query(
                "$HEALTH_SELECT ORDER BY health.checked_at DESC LIMIT 200",
                { rs, _ -> mapHealth(rs) },
            ),
            jobs = jdbcTemplate.query(
                "$JOB_SELECT ORDER BY job.job_key", { rs, _ -> mapJob(rs) },
            ),
            recentJobRuns = jdbcTemplate.query(
                "$JOB_RUN_SELECT ORDER BY run.created_at DESC LIMIT 200",
                { rs, _ -> mapJobRun(rs) },
            ),
            openAlerts = jdbcTemplate.query(
                "$ALERT_SELECT WHERE alert.status IN ('open', 'acknowledged') " +
                    "ORDER BY CASE alert.severity WHEN 'critical' THEN 0 WHEN 'warning' THEN 1 ELSE 2 END, " +
                    "alert.opened_at DESC LIMIT 200",
                { rs, _ -> mapAlert(rs) },
            ),
            activeIncidents = jdbcTemplate.query(
                "$INCIDENT_SELECT WHERE incident.status NOT IN ('resolved', 'closed') " +
                    "ORDER BY incident.started_at DESC LIMIT 100",
                { rs, _ -> mapIncident(rs) },
            ),
            generatedAt = Instant.now(),
        )
    }

    @Transactional
    override fun registerService(command: RegisterPlatformServiceCommand): PlatformServiceSummary {
        requireAccess("platform.monitoring.manage", "platform.monitoring.service.register")
        val key = command.serviceKey.safeKey("service key")
        val type = command.serviceType.trim().lowercase().also {
            require(it in SERVICE_TYPES) { "Invalid platform service type" }
        }
        require(command.name.isNotBlank() && command.name.length <= 200) { "Service name is required" }
        return mutate(
            "platform.monitoring.service.register", command, PlatformServiceSummary::class.java,
        ) { reservationId ->
            jdbcTemplate.update(
                """
                INSERT INTO platform_services (service_key, name, service_type, owner_team, is_active)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (service_key) DO UPDATE SET
                    name = EXCLUDED.name, service_type = EXCLUDED.service_type,
                    owner_team = EXCLUDED.owner_team, is_active = EXCLUDED.is_active
                """.trimIndent(),
                key, command.name.trim(), type, command.ownerTeam?.trim(), command.active,
            )
            serviceByKey(key).also {
                record("platform.monitoring.service.registered", "platform_services", it.serviceId,
                    mapOf("serviceKey" to key, "active" to command.active), reservationId)
            }
        }
    }

    @Transactional
    override fun recordHealth(command: RecordServiceHealthCommand): ServiceHealthSummary {
        requireAccess("platform.monitoring.manage", "platform.monitoring.health.record")
        val status = command.status.trim().lowercase().also {
            require(it in HEALTH_STATUSES) { "Invalid service health status" }
        }
        require(command.latencyMs == null || command.latencyMs >= 0) { "Latency cannot be negative" }
        return mutate(
            "platform.monitoring.health.record", command, ServiceHealthSummary::class.java,
        ) { reservationId ->
            val service = service(command.serviceId)
            val id = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO service_health_checks (id, service_id, status, latency_ms, details)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """.trimIndent(),
                id, command.serviceId, status, command.latencyMs,
                objectMapper.writeValueAsString(command.details),
            )
            reconcileHealthAlert(service, status, command.details)
            health(id).also {
                record("platform.monitoring.health.recorded", "service_health_checks", id,
                    mapOf("serviceId" to command.serviceId, "status" to status), reservationId)
            }
        }
    }

    @Transactional
    override fun registerJob(command: RegisterPlatformJobCommand): PlatformJobSummary {
        requireAccess("platform.monitoring.manage", "platform.monitoring.job.register")
        val key = command.jobKey.safeKey("job key")
        command.serviceId?.let(::service)
        command.scheduleCron?.let {
            require(it.length in 5..120 && !it.contains('\n')) { "Invalid job schedule" }
        }
        return mutate(
            "platform.monitoring.job.register", command, PlatformJobSummary::class.java,
        ) { reservationId ->
            jdbcTemplate.update(
                """
                INSERT INTO platform_jobs (
                    job_key, service_id, description, schedule_cron, is_active
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (job_key) DO UPDATE SET
                    service_id = EXCLUDED.service_id, description = EXCLUDED.description,
                    schedule_cron = EXCLUDED.schedule_cron, is_active = EXCLUDED.is_active
                """.trimIndent(),
                key, command.serviceId, command.description?.trim(),
                command.scheduleCron?.trim(), command.active,
            )
            jobByKey(key).also {
                record("platform.monitoring.job.registered", "platform_jobs", it.jobId,
                    mapOf("jobKey" to key, "active" to command.active), reservationId)
            }
        }
    }

    @Transactional
    override fun runJob(command: RunPlatformJobCommand): PlatformJobRunSummary {
        requireAccess("platform.monitoring.manage", "platform.monitoring.job.run")
        return mutate(
            "platform.monitoring.job.run", command, PlatformJobRunSummary::class.java,
        ) { reservationId ->
            val job = job(command.jobId)
            if (!job.active) throw FleetControlConflictException("Inactive job cannot be run")
            command.tenantId?.let { tenantId ->
                val exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM tenants WHERE id = ? AND deleted_at IS NULL)",
                    Boolean::class.java, tenantId,
                ) == true
                if (!exists) throw FleetControlNotFoundException("Job tenant was not found")
            }
            val runId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO platform_job_runs (id, job_id, tenant_id, status, metadata)
                VALUES (?, ?, ?, 'queued', ?::jsonb)
                """.trimIndent(),
                runId, command.jobId, command.tenantId,
                objectMapper.writeValueAsString(command.metadata),
            )
            jobRun(runId).also {
                record("platform.monitoring.job.queued", "platform_job_runs", runId,
                    mapOf("jobId" to command.jobId, "tenantId" to command.tenantId), reservationId,
                    command.tenantId)
                outboxPort.enqueue(OutboxEventCommand(
                    aggregateType = "platform_job_runs", aggregateId = runId,
                    tenantId = null, eventType = "platform.job.run.requested",
                    destination = OutboxDestination.PLATFORM,
                    payload = mapOf("runId" to runId, "jobId" to command.jobId,
                        "metadata" to command.metadata),
                    idempotencyKeyId = reservationId, priority = 3,
                ))
            }
        }
    }

    @Transactional
    override fun completeJobRun(command: CompletePlatformJobRunCommand): PlatformJobRunSummary {
        requireAccess("platform.monitoring.manage", "platform.monitoring.job.complete")
        val status = command.status.trim().lowercase().also {
            require(it in TERMINAL_JOB_STATUSES) { "Invalid terminal job status" }
        }
        return mutate(
            "platform.monitoring.job.complete", command, PlatformJobRunSummary::class.java,
        ) { reservationId ->
            val before = lockedJobRun(command.runId)
            if (before.status in TERMINAL_JOB_STATUSES) {
                if (before.status == status) return@mutate before
                throw FleetControlConflictException("Job run is already terminal")
            }
            jdbcTemplate.update(
                """
                UPDATE platform_job_runs
                SET status = ?, started_at = COALESCE(started_at, created_at), finished_at = now(),
                    duration_ms = GREATEST(0, EXTRACT(EPOCH FROM
                        (now() - COALESCE(started_at, created_at))) * 1000)::integer,
                    error_message = ?, metadata = metadata || ?::jsonb
                WHERE id = ?
                """.trimIndent(),
                status, command.errorMessage?.take(2000),
                objectMapper.writeValueAsString(command.metadata), command.runId,
            )
            jobRun(command.runId).also {
                record("platform.monitoring.job.$status", "platform_job_runs", command.runId,
                    mapOf("status" to status), reservationId, it.tenantId)
            }
        }
    }

    @Transactional
    override fun updateAlert(command: UpdatePlatformAlertCommand): PlatformAlertSummary {
        requireAccess("platform.monitoring.manage", "platform.monitoring.alert.update")
        require(command.reason.isNotBlank()) { "Alert changes require a reason" }
        val status = command.status.trim().lowercase().also {
            require(it in ALERT_STATUSES) { "Invalid alert status" }
        }
        return mutate(
            "platform.monitoring.alert.update", command, PlatformAlertSummary::class.java,
        ) { reservationId ->
            val before = alert(command.alertId)
            val actor = currentPlatformUser()
            jdbcTemplate.update(
                """
                UPDATE platform_alerts SET status = ?,
                    acknowledged_by = CASE WHEN ? = 'acknowledged' THEN ? ELSE acknowledged_by END,
                    acknowledged_at = CASE WHEN ? = 'acknowledged' THEN now() ELSE acknowledged_at END,
                    resolved_at = CASE WHEN ? = 'resolved' THEN now()
                        WHEN ? IN ('open', 'acknowledged') THEN NULL ELSE resolved_at END,
                    metadata = metadata || jsonb_build_object('lastReason', ?)
                WHERE id = ?
                """.trimIndent(),
                status, status, actor, status, status, status, command.reason.trim(), command.alertId,
            )
            alert(command.alertId).also {
                record("platform.monitoring.alert.$status", "platform_alerts", command.alertId,
                    mapOf("previousStatus" to before.status, "status" to status,
                        "reason" to command.reason.trim()), reservationId, it.tenantId)
            }
        }
    }

    @Transactional
    override fun createIncident(command: CreatePlatformIncidentCommand): PlatformIncidentSummary {
        requireAccess("platform.monitoring.manage", "platform.monitoring.incident.create")
        require(command.title.isNotBlank() && command.title.length <= 200) { "Incident title is required" }
        val severity = command.severity.normalizedSeverity()
        command.ownerPlatformUserId?.let(::requireActivePlatformUser)
        return mutate(
            "platform.monitoring.incident.create", command, PlatformIncidentSummary::class.java,
        ) { reservationId ->
            val id = UUID.randomUUID()
            val number = "INC-${Instant.now().atZone(ZoneOffset.UTC).format(INCIDENT_TIME)}-${id.toString().take(6).uppercase()}"
            jdbcTemplate.update(
                """
                INSERT INTO platform_incidents (
                    id, incident_number, title, severity, status, summary, owner_platform_user_id
                ) VALUES (?, ?, ?, ?, 'open', ?, ?)
                """.trimIndent(),
                id, number, command.title.trim(), severity,
                command.summary?.trim(), command.ownerPlatformUserId,
            )
            incident(id).also {
                record("platform.monitoring.incident.created", "platform_incidents", id,
                    mapOf("incidentNumber" to number, "severity" to severity), reservationId)
            }
        }
    }

    @Transactional
    override fun updateIncident(command: UpdatePlatformIncidentCommand): PlatformIncidentSummary {
        requireAccess("platform.monitoring.manage", "platform.monitoring.incident.update")
        require(command.reason.isNotBlank()) { "Incident changes require a reason" }
        val status = command.status.trim().lowercase().also {
            require(it in INCIDENT_STATUSES) { "Invalid incident status" }
        }
        command.ownerPlatformUserId?.let(::requireActivePlatformUser)
        return mutate(
            "platform.monitoring.incident.update", command, PlatformIncidentSummary::class.java,
        ) { reservationId ->
            val before = lockedIncident(command.incidentId)
            validateIncidentTransition(before.status, status)
            val severity = command.severity?.normalizedSeverity() ?: before.severity
            jdbcTemplate.update(
                """
                UPDATE platform_incidents SET status = ?, severity = ?,
                    summary = COALESCE(?, summary),
                    owner_platform_user_id = COALESCE(?, owner_platform_user_id),
                    resolved_at = CASE WHEN ? IN ('resolved', 'closed')
                        THEN COALESCE(resolved_at, now()) ELSE NULL END,
                    metadata = metadata || jsonb_build_object('lastReason', ?)
                WHERE id = ?
                """.trimIndent(),
                status, severity, command.summary?.trim(), command.ownerPlatformUserId,
                status, command.reason.trim(), command.incidentId,
            )
            incident(command.incidentId).also {
                record("platform.monitoring.incident.$status", "platform_incidents", it.incidentId,
                    mapOf("previousStatus" to before.status, "status" to status,
                        "severity" to severity, "reason" to command.reason.trim()), reservationId)
            }
        }
    }

    private fun services(): List<PlatformServiceSummary> = jdbcTemplate.query(
        SERVICE_SELECT, { rs, _ -> mapService(rs) },
    )

    private fun service(id: UUID): PlatformServiceSummary = jdbcTemplate.query(
        "$SERVICE_SELECT WHERE service.id = ?", { rs, _ -> mapService(rs) }, id,
    ).singleOrNull() ?: throw FleetControlNotFoundException("Platform service was not found")

    private fun serviceByKey(key: String): PlatformServiceSummary = jdbcTemplate.query(
        "$SERVICE_SELECT WHERE service.service_key = ?", { rs, _ -> mapService(rs) }, key,
    ).single()

    private fun mapService(rs: ResultSet) = PlatformServiceSummary(
        rs.getObject("id", UUID::class.java), rs.getString("service_key"), rs.getString("name"),
        rs.getString("service_type"), rs.getString("owner_team"), rs.getBoolean("is_active"),
        rs.getString("current_health"), rs.getTimestamp("last_checked_at")?.toInstant(),
    )

    private fun health(id: UUID): ServiceHealthSummary = jdbcTemplate.query(
        "$HEALTH_SELECT WHERE health.id = ?", { rs, _ -> mapHealth(rs) }, id,
    ).single()

    private fun mapHealth(rs: ResultSet) = ServiceHealthSummary(
        rs.getObject("id", UUID::class.java), rs.getObject("service_id", UUID::class.java),
        rs.getTimestamp("checked_at").toInstant(), rs.getString("status"),
        rs.getObject("latency_ms")?.let { rs.getInt("latency_ms") },
        jsonMap(rs.getString("details")),
    )

    private fun job(id: UUID): PlatformJobSummary = jdbcTemplate.query(
        "$JOB_SELECT WHERE job.id = ?", { rs, _ -> mapJob(rs) }, id,
    ).singleOrNull() ?: throw FleetControlNotFoundException("Platform job was not found")

    private fun jobByKey(key: String): PlatformJobSummary = jdbcTemplate.query(
        "$JOB_SELECT WHERE job.job_key = ?", { rs, _ -> mapJob(rs) }, key,
    ).single()

    private fun mapJob(rs: ResultSet) = PlatformJobSummary(
        rs.getObject("id", UUID::class.java), rs.getString("job_key"),
        rs.getObject("service_id", UUID::class.java), rs.getString("description"),
        rs.getString("schedule_cron"), rs.getBoolean("is_active"),
    )

    private fun jobRun(id: UUID): PlatformJobRunSummary = jdbcTemplate.query(
        "$JOB_RUN_SELECT WHERE run.id = ?", { rs, _ -> mapJobRun(rs) }, id,
    ).singleOrNull() ?: throw FleetControlNotFoundException("Platform job run was not found")

    private fun lockedJobRun(id: UUID): PlatformJobRunSummary = jdbcTemplate.query(
        "$JOB_RUN_SELECT WHERE run.id = ? FOR UPDATE", { rs, _ -> mapJobRun(rs) }, id,
    ).singleOrNull() ?: throw FleetControlNotFoundException("Platform job run was not found")

    private fun mapJobRun(rs: ResultSet) = PlatformJobRunSummary(
        rs.getObject("id", UUID::class.java), rs.getObject("job_id", UUID::class.java),
        rs.getObject("tenant_id", UUID::class.java), rs.getString("status"),
        rs.getTimestamp("started_at")?.toInstant(), rs.getTimestamp("finished_at")?.toInstant(),
        rs.getObject("duration_ms")?.let { rs.getInt("duration_ms") }, rs.getString("error_message"),
        jsonMap(rs.getString("metadata")), rs.getTimestamp("created_at").toInstant(),
    )

    private fun alert(id: UUID): PlatformAlertSummary = jdbcTemplate.query(
        "$ALERT_SELECT WHERE alert.id = ?", { rs, _ -> mapAlert(rs) }, id,
    ).singleOrNull() ?: throw FleetControlNotFoundException("Platform alert was not found")

    private fun mapAlert(rs: ResultSet) = PlatformAlertSummary(
        rs.getObject("id", UUID::class.java), rs.getObject("service_id", UUID::class.java),
        rs.getObject("tenant_id", UUID::class.java), rs.getString("alert_key"),
        rs.getString("severity"), rs.getString("status"), rs.getString("title"),
        rs.getString("body"), rs.getTimestamp("opened_at").toInstant(),
        rs.getObject("acknowledged_by", UUID::class.java),
        rs.getTimestamp("acknowledged_at")?.toInstant(), rs.getTimestamp("resolved_at")?.toInstant(),
    )

    private fun incident(id: UUID): PlatformIncidentSummary = jdbcTemplate.query(
        "$INCIDENT_SELECT WHERE incident.id = ?", { rs, _ -> mapIncident(rs) }, id,
    ).singleOrNull() ?: throw FleetControlNotFoundException("Platform incident was not found")

    private fun lockedIncident(id: UUID): PlatformIncidentSummary = jdbcTemplate.query(
        "$INCIDENT_SELECT WHERE incident.id = ? FOR UPDATE", { rs, _ -> mapIncident(rs) }, id,
    ).singleOrNull() ?: throw FleetControlNotFoundException("Platform incident was not found")

    private fun mapIncident(rs: ResultSet) = PlatformIncidentSummary(
        rs.getObject("id", UUID::class.java), rs.getString("incident_number"),
        rs.getString("title"), rs.getString("severity"), rs.getString("status"),
        rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("resolved_at")?.toInstant(),
        rs.getString("summary"), rs.getObject("owner_platform_user_id", UUID::class.java),
        rs.getTimestamp("updated_at").toInstant(),
    )

    private fun reconcileHealthAlert(
        service: PlatformServiceSummary,
        health: String,
        details: Map<String, Any?>,
    ) {
        val key = "service.health.${service.serviceKey}"
        if (health in setOf("degraded", "down")) {
            val openId = jdbcTemplate.query(
                """
                SELECT id FROM platform_alerts
                WHERE alert_key = ? AND service_id = ? AND status IN ('open', 'acknowledged')
                ORDER BY opened_at DESC LIMIT 1 FOR UPDATE
                """.trimIndent(),
                { rs, _ -> rs.getObject("id", UUID::class.java) }, key, service.serviceId,
            ).singleOrNull()
            if (openId == null) {
                jdbcTemplate.update(
                    """
                    INSERT INTO platform_alerts (
                        service_id, alert_key, severity, status, title, body, metadata
                    ) VALUES (?, ?, ?, 'open', ?, ?, ?::jsonb)
                    """.trimIndent(),
                    service.serviceId, key, if (health == "down") "critical" else "warning",
                    "${service.name} is $health", "Latest service health check reported $health",
                    objectMapper.writeValueAsString(details),
                )
            }
        } else if (health == "healthy") {
            jdbcTemplate.update(
                """
                UPDATE platform_alerts SET status = 'resolved', resolved_at = now()
                WHERE alert_key = ? AND service_id = ? AND status IN ('open', 'acknowledged')
                """.trimIndent(), key, service.serviceId,
            )
        }
    }

    private fun record(
        action: String,
        type: String,
        id: UUID,
        payload: Map<String, Any?>,
        reservationId: UUID,
        tenantId: UUID? = null,
    ) {
        auditPort.recordPlatformEvent(PlatformAuditEvent(
            action = action, resource = AuditResource(type, id),
            targetTenantId = tenantId, after = payload,
        ))
        outboxPort.enqueue(OutboxEventCommand(
            aggregateType = type, aggregateId = id, tenantId = null,
            eventType = action, destination = OutboxDestination.PLATFORM,
            payload = payload, idempotencyKeyId = reservationId, priority = 2,
        ))
    }

    private fun <T : Any> mutate(
        operation: String, payload: Any, responseType: Class<T>, block: (UUID) -> T,
    ): T = try {
        when (val reservation = idempotencyPort.reserve(
            IdempotencyCommand(operation, payload, "platform_operations"),
        )) {
            is IdempotencyReservation.Started -> block(reservation.recordId).also {
                idempotencyPort.markSucceeded(reservation.recordId, 200, it, entityId(it))
            }
            is IdempotencyReservation.Replay -> objectMapper.readValue(
                requireNotNull(reservation.responseBody) { "Stored fleet response is missing" }, responseType,
            )
            is IdempotencyReservation.InProgress -> throw FleetControlConflictException(
                "Fleet command is already in progress",
            )
            is IdempotencyReservation.Conflict -> throw FleetControlConflictException(
                "Idempotency key was used for another fleet command",
            )
        }
    } catch (ex: DuplicateKeyException) {
        throw FleetControlConflictException("Fleet resource already exists")
    }

    private fun entityId(value: Any): UUID? = when (value) {
        is PlatformServiceSummary -> value.serviceId
        is ServiceHealthSummary -> value.healthCheckId
        is PlatformJobSummary -> value.jobId
        is PlatformJobRunSummary -> value.runId
        is PlatformAlertSummary -> value.alertId
        is PlatformIncidentSummary -> value.incidentId
        else -> null
    }

    private fun requireAccess(permission: String, operation: String) {
        platformAccess.requireAuthorized(PlatformAccessRequest(null, permission, operation))
    }

    private fun currentPlatformUser(): UUID = when (val identity = requestContextHolder.current().identity) {
        is RequestIdentity.Platform -> identity.platformUserId
        else -> throw IllegalStateException("Direct platform identity is required")
    }

    private fun requireActivePlatformUser(id: UUID) {
        val exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM platform_users WHERE id = ? AND status = 'active' AND deleted_at IS NULL)",
            Boolean::class.java, id,
        ) == true
        if (!exists) throw FleetControlNotFoundException("Platform incident owner was not found")
    }

    private fun String.safeKey(label: String) = trim().lowercase().also {
        require(it.matches(SAFE_KEY)) { "Invalid $label" }
    }

    private fun String.normalizedSeverity() = trim().lowercase().also {
        require(it in INCIDENT_SEVERITIES) { "Invalid incident severity" }
    }

    private fun validateIncidentTransition(before: String, after: String) {
        if (before == after) return
        if (after !in INCIDENT_TRANSITIONS[before].orEmpty()) throw FleetControlConflictException(
            "Incident cannot transition from $before to $after",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonMap(raw: String): Map<String, Any?> =
        objectMapper.readValue(raw, Map::class.java) as Map<String, Any?>

    private companion object {
        val SAFE_KEY = Regex("[a-z][a-z0-9_.-]{1,99}")
        val SERVICE_TYPES = setOf("api", "worker", "database", "integration", "frontend", "edge", "other")
        val HEALTH_STATUSES = setOf("healthy", "degraded", "down", "unknown")
        val TERMINAL_JOB_STATUSES = setOf("succeeded", "failed", "cancelled")
        val ALERT_STATUSES = setOf("open", "acknowledged", "resolved", "suppressed")
        val INCIDENT_SEVERITIES = setOf("sev1", "sev2", "sev3", "sev4")
        val INCIDENT_STATUSES = setOf("open", "investigating", "monitoring", "resolved", "closed")
        val INCIDENT_TRANSITIONS = mapOf(
            "open" to setOf("investigating", "monitoring", "resolved", "closed"),
            "investigating" to setOf("monitoring", "resolved", "closed"),
            "monitoring" to setOf("investigating", "resolved", "closed"),
            "resolved" to setOf("investigating", "monitoring", "closed"),
            "closed" to emptySet(),
        )
        val INCIDENT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val SERVICE_SELECT = """
            SELECT service.id, service.service_key, service.name, service.service_type,
                   service.owner_team, service.is_active,
                   COALESCE(latest.status, 'unknown') AS current_health,
                   latest.checked_at AS last_checked_at
            FROM platform_services service
            LEFT JOIN LATERAL (
                SELECT health.status, health.checked_at FROM service_health_checks health
                WHERE health.service_id = service.id
                ORDER BY health.checked_at DESC, health.id DESC LIMIT 1
            ) latest ON true
        """.trimIndent()
        val HEALTH_SELECT = """
            SELECT health.id, health.service_id, health.checked_at, health.status,
                   health.latency_ms, health.details FROM service_health_checks health
        """.trimIndent()
        val JOB_SELECT = """
            SELECT job.id, job.job_key, job.service_id, job.description,
                   job.schedule_cron, job.is_active FROM platform_jobs job
        """.trimIndent()
        val JOB_RUN_SELECT = """
            SELECT run.id, run.job_id, run.tenant_id, run.status, run.started_at,
                   run.finished_at, run.duration_ms, run.error_message,
                   run.metadata, run.created_at FROM platform_job_runs run
        """.trimIndent()
        val ALERT_SELECT = """
            SELECT alert.id, alert.service_id, alert.tenant_id, alert.alert_key,
                   alert.severity, alert.status, alert.title, alert.body, alert.opened_at,
                   alert.acknowledged_by, alert.acknowledged_at, alert.resolved_at
            FROM platform_alerts alert
        """.trimIndent()
        val INCIDENT_SELECT = """
            SELECT incident.id, incident.incident_number, incident.title,
                   incident.severity, incident.status, incident.started_at,
                   incident.resolved_at, incident.summary,
                   incident.owner_platform_user_id, incident.updated_at
            FROM platform_incidents incident
        """.trimIndent()
    }
}
