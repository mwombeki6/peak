package com.mwombeki.peak.nightaudit.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.billing.api.BillingSnapshotPort
import com.mwombeki.peak.fiscal.api.FiscalStatusPort
import com.mwombeki.peak.nightaudit.api.NightAuditConflictException
import com.mwombeki.peak.nightaudit.api.NightAuditInProgressException
import com.mwombeki.peak.nightaudit.api.NightAuditIssueResponse
import com.mwombeki.peak.nightaudit.api.NightAuditNotFoundException
import com.mwombeki.peak.nightaudit.api.NightAuditPort
import com.mwombeki.peak.nightaudit.api.NightAuditRunResponse
import com.mwombeki.peak.nightaudit.api.OverrideNightAuditIssueRequest
import com.mwombeki.peak.nightaudit.api.RunNightAuditRequest
import com.mwombeki.peak.payment.api.PaymentStatusPort
import com.mwombeki.peak.pos.api.PosStatusPort
import com.mwombeki.peak.property.api.PropertyOperationsPort
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.reservations.api.ReservationTransitionPort
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
    private val propertyOperationsPort: PropertyOperationsPort,
    private val reservationTransitionPort: ReservationTransitionPort,
    private val billingSnapshotPort: BillingSnapshotPort,
    private val paymentStatusPort: PaymentStatusPort,
    private val fiscalStatusPort: FiscalStatusPort,
    private val posStatusPort: PosStatusPort,
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
            val issues = try {
                collectIssues(actor.tenantId, propertyId, runId, auditDate)
            } catch (ex: Exception) {
                val failureType = ex::class.simpleName?.take(100)
                    ?: "NightAuditTechnicalFailure"
                jdbcTemplate.update(
                    """
                    UPDATE night_audit_runs
                    SET status = 'failed',
                        error_message = ?,
                        updated_at = now()
                    WHERE tenant_id = ?
                      AND property_id = ?
                      AND id = ?
                    """.trimIndent(),
                    failureType,
                    actor.tenantId,
                    propertyId,
                    runId,
                )
                meterRegistry.counter(
                    "peak.night_audit.run",
                    "result",
                    "failed",
                ).increment()
                recordLifecycleEvent(
                    actor = actor,
                    propertyId = propertyId,
                    runId = runId,
                    eventType = "night_audit.failed",
                    payload = mapOf(
                        "runId" to runId,
                        "failureType" to failureType,
                    ),
                    idempotencyKeyId = idempotencyKeyId,
                )
                return@mutate requireRun(
                    actor.tenantId,
                    propertyId,
                    runId,
                )
            }
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
            val status = if (blockingCount == 0) "ready" else "blocked"
            jdbcTemplate.update(
                """
                UPDATE night_audit_runs
                SET status = ?,
                    summary = ?::jsonb,
                    error_message = NULL,
                    updated_at = now()
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND id = ?
                """.trimIndent(),
                status,
                objectMapper.writeValueAsString(summary),
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
                    eventType = "night_audit.evaluated",
                    destination = OutboxDestination.PLATFORM,
                    payload = summary + mapOf("propertyId" to propertyId, "status" to status),
                    idempotencyKeyId = idempotencyKeyId,
                    priority = 3,
                ),
            )
            requireRun(actor.tenantId, propertyId, runId)
        }
    }

    override fun overrideIssue(
        propertyId: UUID,
        runId: UUID,
        issueId: UUID,
        request: OverrideNightAuditIssueRequest,
    ): NightAuditRunResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "night_audit.issue.override",
            requestPayload = mapOf(
                "runId" to runId,
                "issueId" to issueId,
                "request" to request,
            ),
            resourceType = NIGHT_AUDIT_RUNS,
            replayType = NightAuditRunResponse::class.java,
        ) { actor, idempotencyKeyId ->
            val reason = request.reason.trim().takeIf { it.length in 10..500 }
                ?: throw IllegalArgumentException(
                    "Override reason must contain between 10 and 500 characters",
                )
            val run = requireRunForUpdate(actor.tenantId, propertyId, runId)
            require(run.status in setOf("blocked", "ready")) {
                "Only blocked or ready runs can have issues overridden"
            }
            val issueCode = jdbcTemplate.query(
                """
                SELECT issue_code
                FROM night_audit_issues
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND run_id = ?
                  AND id = ?
                  AND resolved_at IS NULL
                FOR UPDATE
                """.trimIndent(),
                { rs, _ -> rs.getString("issue_code") },
                actor.tenantId,
                propertyId,
                runId,
                issueId,
            ).singleOrNull() ?: throw NightAuditNotFoundException(
                "Night audit issue was not found or was already resolved",
            )
            if (issueCode == OPEN_UNPAID_FOLIOS) {
                throw NightAuditConflictException(
                    "Open unpaid folios must be settled and cannot be overridden",
                )
            }
            val changed = jdbcTemplate.update(
                """
                UPDATE night_audit_issues
                SET resolved_at = now(),
                    resolved_by = ?,
                    resolution_note = ?,
                    updated_at = now()
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND run_id = ?
                  AND id = ?
                  AND resolved_at IS NULL
                """.trimIndent(),
                actor.tenantUserId,
                reason,
                actor.tenantId,
                propertyId,
                runId,
                issueId,
            )
            if (changed != 1) {
                throw NightAuditNotFoundException(
                    "Night audit issue was not found or was already resolved",
                )
            }
            val unresolved = unresolvedBlockingIssueCount(
                actor.tenantId,
                propertyId,
                runId,
            )
            jdbcTemplate.update(
                """
                UPDATE night_audit_runs
                SET status = CASE WHEN ? = 0 THEN 'ready' ELSE 'blocked' END,
                    updated_at = now()
                WHERE tenant_id = ? AND property_id = ? AND id = ?
                """.trimIndent(),
                unresolved,
                actor.tenantId,
                propertyId,
                runId,
            )
            recordLifecycleEvent(
                actor = actor,
                propertyId = propertyId,
                runId = runId,
                eventType = "night_audit.issue.overridden",
                payload = mapOf(
                    "runId" to runId,
                    "issueId" to issueId,
                    "reason" to reason,
                ),
                idempotencyKeyId = idempotencyKeyId,
            )
            requireRun(actor.tenantId, propertyId, runId)
        }
    }

    override fun complete(
        propertyId: UUID,
        runId: UUID,
    ): NightAuditRunResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "night_audit.complete",
            requestPayload = mapOf("runId" to runId),
            resourceType = NIGHT_AUDIT_RUNS,
            replayType = NightAuditRunResponse::class.java,
        ) { actor, idempotencyKeyId ->
            val run = requireRunForUpdate(actor.tenantId, propertyId, runId)
            require(run.status in setOf("ready", "blocked")) {
                "Only evaluated night audit runs can be completed"
            }
            val currentDate = propertyOperationsPort.currentBusinessDate(
                actor.tenantId,
                propertyId,
            )
            require(currentDate == run.auditDate) {
                "Night audit date does not match the current property business date"
            }
            val liveIssues = collectIssues(
                actor.tenantId,
                propertyId,
                runId,
                run.auditDate,
            )
            val overriddenCodes = resolvedIssueCodes(
                actor.tenantId,
                propertyId,
                runId,
            )
            val unapprovedBlockers = liveIssues.filter {
                it.blocking && it.issueCode !in overriddenCodes
            }
            if (unapprovedBlockers.isNotEmpty()) {
                throw NightAuditConflictException(
                    "Live night-audit blockers require resolution before completion",
                )
            }
            propertyOperationsPort.advanceBusinessDate(
                actor.tenantId,
                propertyId,
                run.auditDate,
            )
            val summary = run.summary + mapOf(
                "completedAfterRevalidation" to true,
                "overriddenIssueCodes" to overriddenCodes.sorted(),
                "nextBusinessDate" to run.auditDate.plusDays(1),
            )
            val changed = jdbcTemplate.update(
                """
                UPDATE night_audit_runs
                SET status = 'completed',
                    completed_at = now(),
                    summary = ?::jsonb,
                    error_message = NULL,
                    updated_at = now()
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND id = ?
                  AND status IN ('ready', 'blocked')
                """.trimIndent(),
                objectMapper.writeValueAsString(summary),
                actor.tenantId,
                propertyId,
                runId,
            )
            if (changed != 1) {
                throw NightAuditConflictException(
                    "Night audit was completed concurrently",
                )
            }
            recordLifecycleEvent(
                actor = actor,
                propertyId = propertyId,
                runId = runId,
                eventType = "night_audit.completed",
                payload = summary,
                idempotencyKeyId = idempotencyKeyId,
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
        val billing = billingSnapshotPort.nightAuditSummary(tenantId, propertyId)
        val payments = paymentStatusPort.nightAuditSummary(tenantId, propertyId)
        val fiscal = fiscalStatusPort.nightAuditSummary(tenantId, propertyId)
        val pos = posStatusPort.nightAuditSummary(tenantId, propertyId)
        val reservations = reservationTransitionPort.operationalSummary(
            tenantId,
            propertyId,
            auditDate,
        )
        addCountIssue(
            issues = issues,
            count = billing.openUnpaidFolios,
            runId = runId,
            code = "open_unpaid_folios",
            message = "Open folios have outstanding balances.",
            blocking = true,
        )
        addCountIssue(
            issues = issues,
            count = billing.foliosMissingIssuedInvoice,
            runId = runId,
            code = "folios_missing_issued_invoice",
            message = "Charge-bearing folios are missing issued invoices.",
            blocking = true,
        )
        addCountIssue(
            issues = issues,
            count = fiscal.issuedInvoicesMissingAcceptedReceipt,
            runId = runId,
            code = "invoices_missing_accepted_fiscal_receipt",
            message = "Issued invoices are missing accepted fiscal receipts.",
            blocking = true,
        )
        addCountIssue(
            issues = issues,
            count = payments.nonTerminalTransactions + billing.pendingFolioPayments,
            runId = runId,
            code = "pending_payments",
            message = "Pending payments must settle, fail, or be reversed before close.",
            blocking = true,
        )
        addCountIssue(
            issues = issues,
            count = pos.openOrUnapprovedSessions,
            runId = runId,
            code = "open_pos_sessions",
            message = "Open POS sessions must be closed before night audit.",
            blocking = true,
        )
        addCountIssue(
            issues = issues,
            count = fiscal.pendingCorrections,
            runId = runId,
            code = "pending_fiscal_corrections",
            message = "Fiscal corrections remain pending.",
            blocking = true,
        )
        addCountIssue(
            issues = issues,
            count = reservations.overdueCheckedInStays,
            runId = runId,
            code = "overdue_checked_in_stays",
            message = "Checked-in stays are past scheduled checkout date.",
            blocking = false,
        )
        return issues
    }

    private fun propertyBusinessDate(tenantId: UUID, propertyId: UUID): LocalDate {
        return propertyOperationsPort.currentBusinessDate(tenantId, propertyId)
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

    private fun requireRunForUpdate(
        tenantId: UUID,
        propertyId: UUID,
        runId: UUID,
    ): NightAuditRunState {
        return jdbcTemplate.query(
            """
            SELECT audit_date, status, COALESCE(summary, '{}'::jsonb)::text AS summary
            FROM night_audit_runs
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                NightAuditRunState(
                    auditDate = rs.getObject("audit_date", LocalDate::class.java),
                    status = rs.getString("status"),
                    summary = summary(rs.getString("summary")),
                )
            },
            tenantId,
            propertyId,
            runId,
        ).singleOrNull() ?: throw NightAuditNotFoundException(
            "Night audit run was not found",
        )
    }

    private fun unresolvedBlockingIssueCount(
        tenantId: UUID,
        propertyId: UUID,
        runId: UUID,
    ): Int {
        return jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM night_audit_issues
            WHERE tenant_id = ?
              AND property_id = ?
              AND run_id = ?
              AND blocking = true
              AND resolved_at IS NULL
            """.trimIndent(),
            Int::class.java,
            tenantId,
            propertyId,
            runId,
        ) ?: 0
    }

    private fun resolvedIssueCodes(
        tenantId: UUID,
        propertyId: UUID,
        runId: UUID,
    ): Set<String> {
        return jdbcTemplate.queryForList(
            """
            SELECT issue_code
            FROM night_audit_issues
            WHERE tenant_id = ?
              AND property_id = ?
              AND run_id = ?
              AND resolved_at IS NOT NULL
            """.trimIndent(),
            String::class.java,
            tenantId,
            propertyId,
            runId,
        ).filterNotNull().toSet()
    }

    private fun recordLifecycleEvent(
        actor: TenantActor,
        propertyId: UUID,
        runId: UUID,
        eventType: String,
        payload: Map<String, Any?>,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = actor.tenantId,
                action = eventType,
                resource = AuditResource(NIGHT_AUDIT_RUNS, runId),
                after = payload + mapOf("propertyId" to propertyId),
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = NIGHT_AUDIT_RUNS,
                aggregateId = runId,
                tenantId = actor.tenantId,
                propertyId = propertyId,
                eventType = eventType,
                destination = OutboxDestination.PLATFORM,
                payload = payload + mapOf("propertyId" to propertyId),
                idempotencyKeyId = idempotencyKeyId,
                priority = 3,
            ),
        )
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
            SELECT id, run_id, property_id, severity, issue_code, message, blocking,
                   resolved_at, resolution_note
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
                    resolutionNote = rs.getString("resolution_note"),
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

    private data class NightAuditRunState(
        val auditDate: LocalDate,
        val status: String,
        val summary: Map<String, Any?>,
    )

    private companion object {
        const val OPEN_UNPAID_FOLIOS = "open_unpaid_folios"
        const val NIGHT_AUDIT_RUNS = "night_audit_runs"
    }
}

private fun DataIntegrityViolationException.publicDatabaseMessage(): String {
    return "Night-audit request conflicts with existing operational data"
}
