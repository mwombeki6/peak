package com.mwombeki.peak.nightaudit.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.nightaudit.api.NightAuditConflictException
import com.mwombeki.peak.nightaudit.api.NightAuditInProgressException
import com.mwombeki.peak.nightaudit.api.NightAuditIssueResponse
import com.mwombeki.peak.nightaudit.api.NightAuditNotFoundException
import com.mwombeki.peak.nightaudit.api.NightAuditPort
import com.mwombeki.peak.nightaudit.api.NightAuditRunResponse
import com.mwombeki.peak.nightaudit.api.RunNightAuditRequest
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.TenantActor
import com.mwombeki.peak.shared.context.TenantRequestContext
import io.micrometer.core.instrument.MeterRegistry
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Service
class NightAuditService(
    private val jdbcTemplate: JdbcTemplate,
    private val tenantRequestContext: TenantRequestContext,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : NightAuditPort {

    override fun runNightAudit(
        propertyId: UUID,
        request: RunNightAuditRequest,
    ): NightAuditRunResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "night_audit.run",
            requestPayload = request,
            resourceType = NIGHT_AUDIT_RUNS,
            replayType = NightAuditRunResponse::class.java,
        ) { actor, idempotencyKeyId ->
            val auditDate = request.auditDate
                ?: propertyBusinessDate(actor.tenantId, propertyId)
            val runId = createRun(actor, propertyId, auditDate)
            val issues = collectIssues(actor.tenantId, propertyId, runId, auditDate)
            issues.forEach { issue ->
                jdbcTemplate.update(
                    """
                    INSERT INTO night_audit_issues (
                        id, tenant_id, property_id, run_id, severity, issue_code,
                        message, blocking, payload
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """.trimIndent(),
                    issue.id,
                    actor.tenantId,
                    propertyId,
                    runId,
                    issue.severity,
                    issue.issueCode,
                    issue.message,
                    issue.blocking,
                    objectMapper.writeValueAsString(issue.payload),
                )
            }
            val blockingCount = issues.count { it.blocking }
            val summary = mapOf(
                "auditDate" to auditDate,
                "blockingIssues" to blockingCount,
                "warningIssues" to issues.count { !it.blocking },
                "issueCodes" to issues.map { it.issueCode }.distinct().sorted(),
            )
            val status = if (blockingCount == 0) "completed" else "failed"
            jdbcTemplate.update(
                """
                UPDATE night_audit_runs
                SET status = ?,
                    completed_at = now(),
                    summary = ?::jsonb,
                    error_message = CASE WHEN ? > 0 THEN 'Blocking night-audit issues require resolution' ELSE NULL END,
                    updated_at = now()
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND id = ?
                """.trimIndent(),
                status,
                objectMapper.writeValueAsString(summary),
                blockingCount,
                actor.tenantId,
                propertyId,
                runId,
            )
            auditPort.recordTenantEvent(
                TenantAuditEvent(
                    tenantId = actor.tenantId,
                    action = "night_audit.run",
                    resource = AuditResource(NIGHT_AUDIT_RUNS, runId),
                    after = summary + mapOf("propertyId" to propertyId, "status" to status),
                ),
            )
            outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = NIGHT_AUDIT_RUNS,
                    aggregateId = runId,
                    tenantId = actor.tenantId,
                    propertyId = propertyId,
                    eventType = "night_audit.completed",
                    destination = OutboxDestination.PLATFORM,
                    payload = summary + mapOf("propertyId" to propertyId, "status" to status),
                    idempotencyKeyId = idempotencyKeyId,
                    priority = 3,
                ),
            )
            requireRun(actor.tenantId, propertyId, runId)
        }
    }

    override fun listRuns(propertyId: UUID): List<NightAuditRunResponse> {
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                """
                SELECT id, tenant_id, property_id, audit_date, attempt_no, status, started_at,
                       completed_at, run_by, COALESCE(summary, '{}'::jsonb)::text AS summary
                FROM night_audit_runs
                WHERE tenant_id = ?
                  AND property_id = ?
                ORDER BY audit_date DESC, started_at DESC NULLS LAST
                LIMIT 120
                """.trimIndent(),
                { rs, _ -> mapRun(rs, includeIssues = false) },
                actor.tenantId,
                propertyId,
            )
        }
    }

    override fun getRun(propertyId: UUID, runId: UUID): NightAuditRunResponse? {
        return read(propertyId) { actor ->
            run(actor.tenantId, propertyId, runId, includeIssues = true)
        }
    }

    private fun createRun(
        actor: TenantActor,
        propertyId: UUID,
        auditDate: LocalDate,
    ): UUID {
        jdbcTemplate.queryForList(
            "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))",
            "$propertyId:$auditDate",
        )
        val latest = jdbcTemplate.query(
            """
            SELECT status, attempt_no
            FROM night_audit_runs
            WHERE tenant_id = ?
              AND property_id = ?
              AND audit_date = ?
            ORDER BY attempt_no DESC
            LIMIT 1
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getString("status") to rs.getInt("attempt_no") },
            actor.tenantId,
            propertyId,
            auditDate,
        ).singleOrNull()
        require(latest?.first != "completed") {
            "Night audit is already completed for $auditDate"
        }
        require(latest?.first != "running") {
            "Night audit is already running for $auditDate"
        }

        val runId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO night_audit_runs (
                id, tenant_id, property_id, audit_date, attempt_no,
                status, started_at, run_by
            )
            VALUES (?, ?, ?, ?, ?, 'running', now(), ?)
            """.trimIndent(),
            runId,
            actor.tenantId,
            propertyId,
            auditDate,
            (latest?.second ?: 0) + 1,
            actor.tenantUserId,
        )
        return runId
    }

    private fun collectIssues(
        tenantId: UUID,
        propertyId: UUID,
        runId: UUID,
        auditDate: LocalDate,
    ): List<NightAuditIssueDraft> {
        val issues = mutableListOf<NightAuditIssueDraft>()
        addCountIssue(
            issues = issues,
            count = count(
                """
                SELECT COUNT(*)
                FROM folios
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND status = 'open'
                  AND total_amount > total_paid
                  AND deleted_at IS NULL
                """.trimIndent(),
                tenantId,
                propertyId,
            ),
            runId = runId,
            code = "open_unpaid_folios",
            message = "Open folios have outstanding balances.",
            blocking = true,
        )
        addCountIssue(
            issues = issues,
            count = count(
                """
                SELECT COUNT(*)
                FROM folios f
                WHERE f.tenant_id = ?
                  AND f.property_id = ?
                  AND f.status = 'open'
                  AND f.total_amount > 0
                  AND f.deleted_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1
                      FROM invoices i
                      WHERE i.tenant_id = f.tenant_id
                        AND i.property_id = f.property_id
                        AND i.folio_id = f.id
                        AND i.status IN ('issued', 'sent', 'paid')
                        AND i.deleted_at IS NULL
                  )
                """.trimIndent(),
                tenantId,
                propertyId,
            ),
            runId = runId,
            code = "folios_missing_issued_invoice",
            message = "Charge-bearing folios are missing issued invoices.",
            blocking = true,
        )
        addCountIssue(
            issues = issues,
            count = count(
                """
                SELECT COUNT(*)
                FROM invoices i
                WHERE i.tenant_id = ?
                  AND i.property_id = ?
                  AND i.status IN ('issued', 'sent', 'paid')
                  AND i.deleted_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1
                      FROM fiscal_receipts fr
                      WHERE fr.tenant_id = i.tenant_id
                        AND fr.invoice_id = i.id
                        AND fr.status = 'accepted'
                  )
                """.trimIndent(),
                tenantId,
                propertyId,
            ),
            runId = runId,
            code = "invoices_missing_accepted_fiscal_receipt",
            message = "Issued invoices are missing accepted fiscal receipts.",
            blocking = true,
        )
        addCountIssue(
            issues = issues,
            count = count(
                """
                SELECT COUNT(*)
                FROM payment_transactions
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND status IN ('initiated', 'pending')
                """.trimIndent(),
                tenantId,
                propertyId,
            ) + count(
                """
                SELECT COUNT(*)
                FROM folio_payments
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND status = 'PENDING'
                  AND deleted_at IS NULL
                """.trimIndent(),
                tenantId,
                propertyId,
            ),
            runId = runId,
            code = "pending_payments",
            message = "Pending payments must settle, fail, or be reversed before close.",
            blocking = true,
        )
        addCountIssue(
            issues = issues,
            count = count(
                """
                SELECT COUNT(*)
                FROM pos_sessions ps
                JOIN outlets o ON o.tenant_id = ps.tenant_id AND o.id = ps.outlet_id
                WHERE ps.tenant_id = ?
                  AND o.property_id = ?
                  AND ps.closed_at IS NULL
                """.trimIndent(),
                tenantId,
                propertyId,
            ),
            runId = runId,
            code = "open_pos_sessions",
            message = "Open POS sessions must be closed before night audit.",
            blocking = true,
        )
        addCountIssue(
            issues = issues,
            count = count(
                """
                SELECT COUNT(*)
                FROM stays s
                JOIN reservations r ON r.tenant_id = s.tenant_id AND r.id = s.reservation_id
                WHERE s.tenant_id = ?
                  AND r.property_id = ?
                  AND s.status = 'checked_in'
                  AND r.check_out_date < ?
                """.trimIndent(),
                tenantId,
                propertyId,
                auditDate,
            ),
            runId = runId,
            code = "overdue_checked_in_stays",
            message = "Checked-in stays are past scheduled checkout date.",
            blocking = false,
        )
        return issues
    }

    private fun propertyBusinessDate(tenantId: UUID, propertyId: UUID): LocalDate {
        return jdbcTemplate.queryForObject(
            """
            SELECT ((now() AT TIME ZONE timezone)::date + business_date_offset)
            FROM properties
            WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
            """.trimIndent(),
            LocalDate::class.java,
            tenantId,
            propertyId,
        ) ?: throw NightAuditNotFoundException("Property was not found")
    }

    private fun addCountIssue(
        issues: MutableList<NightAuditIssueDraft>,
        count: Int,
        runId: UUID,
        code: String,
        message: String,
        blocking: Boolean,
    ) {
        if (count <= 0) {
            return
        }
        issues += NightAuditIssueDraft(
            id = UUID.randomUUID(),
            runId = runId,
            severity = if (blocking) "blocking" else "warning",
            issueCode = code,
            message = "$message Count: $count",
            blocking = blocking,
            payload = mapOf("count" to count),
        )
    }

    private fun requireRun(
        tenantId: UUID,
        propertyId: UUID,
        runId: UUID,
    ): NightAuditRunResponse {
        return run(tenantId, propertyId, runId, includeIssues = true)
            ?: throw NightAuditNotFoundException("Night audit run was not found")
    }

    private fun run(
        tenantId: UUID,
        propertyId: UUID,
        runId: UUID,
        includeIssues: Boolean,
    ): NightAuditRunResponse? {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, property_id, audit_date, attempt_no, status, started_at,
                   completed_at, run_by, COALESCE(summary, '{}'::jsonb)::text AS summary
            FROM night_audit_runs
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
            """.trimIndent(),
            { rs, _ -> mapRun(rs, includeIssues) },
            tenantId,
            propertyId,
            runId,
        ).singleOrNull()
    }

    private fun mapRun(rs: ResultSet, includeIssues: Boolean): NightAuditRunResponse {
        val runId = rs.getObject("id", UUID::class.java)
        val tenantId = rs.getObject("tenant_id", UUID::class.java)
        val propertyId = rs.getObject("property_id", UUID::class.java)
        return NightAuditRunResponse(
            id = runId,
            tenantId = tenantId,
            propertyId = propertyId,
            auditDate = rs.getObject("audit_date", LocalDate::class.java),
            attemptNo = rs.getInt("attempt_no"),
            status = rs.getString("status"),
            startedAt = rs.getTimestamp("started_at")?.toInstant(),
            completedAt = rs.getTimestamp("completed_at")?.toInstant(),
            runBy = rs.getObject("run_by", UUID::class.java),
            summary = summary(rs.getString("summary")),
            issues = if (includeIssues) issues(tenantId, propertyId, runId) else emptyList(),
        )
    }

    private fun issues(
        tenantId: UUID,
        propertyId: UUID,
        runId: UUID,
    ): List<NightAuditIssueResponse> {
        return jdbcTemplate.query(
            """
            SELECT id, run_id, property_id, severity, issue_code, message, blocking, resolved_at
            FROM night_audit_issues
            WHERE tenant_id = ?
              AND property_id = ?
              AND run_id = ?
            ORDER BY blocking DESC, severity, created_at
            """.trimIndent(),
            { rs, _ ->
                NightAuditIssueResponse(
                    id = rs.getObject("id", UUID::class.java),
                    runId = rs.getObject("run_id", UUID::class.java),
                    propertyId = rs.getObject("property_id", UUID::class.java),
                    severity = rs.getString("severity"),
                    issueCode = rs.getString("issue_code"),
                    message = rs.getString("message"),
                    blocking = rs.getBoolean("blocking"),
                    resolvedAt = rs.getTimestamp("resolved_at")?.toInstant(),
                )
            },
            tenantId,
            propertyId,
            runId,
        )
    }

    private fun <T : Any> mutate(
        propertyId: UUID,
        operationType: String,
        requestPayload: Any,
        resourceType: String,
        replayType: Class<T>,
        block: (TenantActor, UUID) -> T,
    ): T {
        return requireNotNull(
            transactionTemplate.execute {
                val actor = bindActor(propertyId)
                val reservation = idempotencyPort.reserve(
                    IdempotencyCommand(operationType = operationType, requestPayload = requestPayload, resourceType = resourceType),
                )
                when (reservation) {
                    is IdempotencyReservation.Started -> {
                        try {
                            val response = block(actor, reservation.recordId)
                            idempotencyPort.markSucceeded(reservation.recordId, 200, response, resourceId(response))
                            meterRegistry.counter("peak.night_audit.command", "operation", operationType, "result", "succeeded").increment()
                            response
                        } catch (ex: DataIntegrityViolationException) {
                            throw NightAuditConflictException(ex.publicDatabaseMessage())
                        }
                    }

                    is IdempotencyReservation.Replay -> {
                        if (reservation.responseBody.isNullOrBlank()) {
                            throw NightAuditConflictException("Night audit command replay does not contain a stored response body")
                        }
                        objectMapper.readValue(reservation.responseBody, replayType)
                    }

                    is IdempotencyReservation.InProgress -> {
                        meterRegistry.counter("peak.night_audit.command", "operation", operationType, "result", "in_progress").increment()
                        throw NightAuditInProgressException("Night audit command is already being processed for this idempotency key")
                    }

                    is IdempotencyReservation.Conflict -> {
                        meterRegistry.counter("peak.night_audit.command", "operation", operationType, "result", "conflict").increment()
                        throw NightAuditConflictException("Idempotency key was already used for a different night audit request")
                    }
                }
            },
        )
    }

    private fun <T> read(propertyId: UUID, block: (TenantActor) -> T): T {
        return requireNotNull(transactionTemplate.execute { block(bindActor(propertyId)) })
    }

    private fun bindActor(propertyId: UUID): TenantActor {
        val actor = tenantRequestContext.bind()
        tenantRequestContext.requirePropertyUsable(actor.tenantId, propertyId)
        return actor
    }

    private fun count(sql: String, vararg args: Any?): Int {
        return jdbcTemplate.queryForObject(sql, Int::class.java, *args) ?: 0
    }

    @Suppress("UNCHECKED_CAST")
    private fun summary(json: String?): Map<String, Any?> {
        if (json.isNullOrBlank()) {
            return emptyMap()
        }
        return objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
    }

    private fun resourceId(response: Any): UUID? {
        return when (response) {
            is NightAuditRunResponse -> response.id
            else -> null
        }
    }

    private data class NightAuditIssueDraft(
        val id: UUID,
        val runId: UUID,
        val severity: String,
        val issueCode: String,
        val message: String,
        val blocking: Boolean,
        val payload: Map<String, Any?>,
    )

    private companion object {
        const val NIGHT_AUDIT_RUNS = "night_audit_runs"
    }
}

private fun DataIntegrityViolationException.publicDatabaseMessage(): String {
    return "Night-audit request conflicts with existing operational data"
}
