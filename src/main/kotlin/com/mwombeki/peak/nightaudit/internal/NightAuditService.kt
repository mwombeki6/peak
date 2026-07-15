package com.mwombeki.peak.nightaudit.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.billing.api.BillingSnapshotPort
import com.mwombeki.peak.billing.api.BillingCloseSnapshotSummary
import com.mwombeki.peak.fiscal.api.FiscalStatusPort
import com.mwombeki.peak.fiscal.api.FiscalCloseSnapshotSummary
import com.mwombeki.peak.housekeeping.api.HousekeepingCloseSnapshotPort
import com.mwombeki.peak.housekeeping.api.HousekeepingCloseSnapshotSummary
import com.mwombeki.peak.inventory.api.InventoryCloseSnapshotPort
import com.mwombeki.peak.inventory.api.InventoryCloseSnapshotSummary
import com.mwombeki.peak.maintenance.api.MaintenanceCloseSnapshotPort
import com.mwombeki.peak.maintenance.api.MaintenanceCloseSnapshotSummary
import com.mwombeki.peak.nightaudit.api.NightAuditCloseSnapshotResponse
import com.mwombeki.peak.nightaudit.api.NightAuditCloseSnapshotPort
import com.mwombeki.peak.nightaudit.api.NightAuditConflictException
import com.mwombeki.peak.nightaudit.api.NightAuditInProgressException
import com.mwombeki.peak.nightaudit.api.NightAuditIssueResponse
import com.mwombeki.peak.nightaudit.api.NightAuditNotFoundException
import com.mwombeki.peak.nightaudit.api.NightAuditPort
import com.mwombeki.peak.nightaudit.api.NightAuditRunResponse
import com.mwombeki.peak.nightaudit.api.OverrideNightAuditIssueRequest
import com.mwombeki.peak.nightaudit.api.RunNightAuditRequest
import com.mwombeki.peak.payment.api.PaymentStatusPort
import com.mwombeki.peak.payment.api.PaymentCloseSnapshotSummary
import com.mwombeki.peak.pos.api.PosStatusPort
import com.mwombeki.peak.pos.api.PosCloseSnapshotSummary
import com.mwombeki.peak.property.api.PropertyOperationsPort
import com.mwombeki.peak.property.api.PropertyCloseSnapshotSummary
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.reservations.api.ReservationTransitionPort
import com.mwombeki.peak.reservations.api.ReservationCloseSnapshotPort
import com.mwombeki.peak.reservations.api.ReservationCloseSnapshotSummary
import com.mwombeki.peak.shared.context.TenantActor
import com.mwombeki.peak.shared.context.TenantRequestContext
import io.micrometer.core.instrument.MeterRegistry
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.LocalDate
import java.time.Instant
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.slf4j.LoggerFactory
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
    private val reservationCloseSnapshotPort: ReservationCloseSnapshotPort,
    private val housekeepingCloseSnapshotPort: HousekeepingCloseSnapshotPort,
    private val maintenanceCloseSnapshotPort: MaintenanceCloseSnapshotPort,
    private val inventoryCloseSnapshotPort: InventoryCloseSnapshotPort,
) : NightAuditPort, NightAuditCloseSnapshotPort {

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
                logger.warn(
                    "Night audit evaluation failed propertyId={} runId={} failureType={}",
                    propertyId,
                    runId,
                    ex::class.simpleName,
                    ex,
                )
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
                        message, blocking, override_allowed, payload
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """.trimIndent(),
                    issue.id,
                    actor.tenantId,
                    propertyId,
                    runId,
                    issue.severity,
                    issue.issueCode,
                    issue.message,
                    issue.blocking,
                    issue.overrideAllowed,
                    objectMapper.writeValueAsString(issue.payload),
                )
            }
            synchronizeFinancialControlCases(
                actor = actor,
                propertyId = propertyId,
                businessDate = auditDate,
                currency = propertyOperationsPort.closeSnapshotSummary(
                    actor.tenantId,
                    propertyId,
                ).currency,
                issues = issues,
            )
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
            val issue = jdbcTemplate.query(
                """
                SELECT issue_code, override_allowed
                FROM night_audit_issues
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND run_id = ?
                  AND id = ?
                  AND resolved_at IS NULL
                FOR UPDATE
                """.trimIndent(),
                { rs, _ ->
                    rs.getString("issue_code") to
                        rs.getBoolean("override_allowed")
                },
                actor.tenantId,
                propertyId,
                runId,
                issueId,
            ).singleOrNull() ?: throw NightAuditNotFoundException(
                "Night audit issue was not found or was already resolved",
            )
            if (!issue.second) {
                throw NightAuditConflictException(
                    "Issue ${issue.first} must be resolved at source and cannot be overridden",
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
            acceptFinancialControlCase(
                actor = actor,
                propertyId = propertyId,
                runId = runId,
                issueId = issueId,
                reason = reason,
            )
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
            jdbcTemplate.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))",
                "$propertyId:complete",
            )
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
            val closeData = collectCloseData(
                actor.tenantId,
                propertyId,
                run.auditDate,
            )
            val liveIssues = collectIssues(
                actor.tenantId,
                propertyId,
                runId,
                run.auditDate,
                closeData,
            )
            val overriddenCodes = resolvedIssueCodes(
                actor.tenantId,
                propertyId,
                runId,
            )
            val unapprovedBlockers = liveIssues.filter {
                it.blocking &&
                    (!it.overrideAllowed || it.issueCode !in overriddenCodes)
            }
            if (unapprovedBlockers.isNotEmpty()) {
                throw NightAuditConflictException(
                    "Live night-audit blockers require resolution before completion",
                )
            }
            val snapshot = captureCloseSnapshot(
                actor = actor,
                propertyId = propertyId,
                runId = runId,
                businessDate = run.auditDate,
                closeData = closeData,
                liveIssues = liveIssues,
                overriddenCodes = overriddenCodes,
            )
            propertyOperationsPort.advanceBusinessDate(
                actor.tenantId,
                propertyId,
                run.auditDate,
            )
            val summary = run.summary + mapOf(
                "completedAfterRevalidation" to true,
                "overriddenIssueCodes" to overriddenCodes.sorted(),
                "nextBusinessDate" to run.auditDate.plusDays(1),
                "closeSnapshotId" to snapshot.id,
                "closeSnapshotHash" to snapshot.payloadHash,
                "reportGenerationQueued" to true,
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
            queueCoreReports(
                actor = actor,
                propertyId = propertyId,
                runId = runId,
                snapshotId = snapshot.id,
                businessDate = run.auditDate,
                idempotencyKeyId = idempotencyKeyId,
            )
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

    override fun getCloseSnapshot(
        propertyId: UUID,
        runId: UUID,
    ): NightAuditCloseSnapshotResponse? {
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                """
                SELECT id, tenant_id, property_id, night_audit_run_id,
                       business_date, schema_version, currency, payload::text,
                       payload_hash, available_rooms, rooms_sold,
                       occupied_rooms, occupancy, adr, revpar, room_revenue,
                       pos_revenue, tax_total, gross_total, net_total,
                       revenue_journal_difference,
                       payment_allocation_difference, captured_at
                FROM night_audit_close_snapshots
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND night_audit_run_id = ?
                """.trimIndent(),
                { rs, _ -> mapCloseSnapshot(rs) },
                actor.tenantId,
                propertyId,
                runId,
            ).singleOrNull()
        }
    }

    override fun getCloseSnapshot(
        tenantId: UUID,
        propertyId: UUID,
        runId: UUID,
    ): NightAuditCloseSnapshotResponse? {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, property_id, night_audit_run_id,
                   business_date, schema_version, currency, payload::text,
                   payload_hash, available_rooms, rooms_sold,
                   occupied_rooms, occupancy, adr, revpar, room_revenue,
                   pos_revenue, tax_total, gross_total, net_total,
                   revenue_journal_difference,
                   payment_allocation_difference, captured_at
            FROM night_audit_close_snapshots
            WHERE tenant_id = ?
              AND property_id = ?
              AND night_audit_run_id = ?
            """.trimIndent(),
            { rs, _ -> mapCloseSnapshot(rs) },
            tenantId,
            propertyId,
            runId,
        ).singleOrNull()
    }

    override fun getCloseSnapshotById(
        tenantId: UUID,
        propertyId: UUID,
        snapshotId: UUID,
    ): NightAuditCloseSnapshotResponse? {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, property_id, night_audit_run_id,
                   business_date, schema_version, currency, payload::text,
                   payload_hash, available_rooms, rooms_sold,
                   occupied_rooms, occupancy, adr, revpar, room_revenue,
                   pos_revenue, tax_total, gross_total, net_total,
                   revenue_journal_difference,
                   payment_allocation_difference, captured_at
            FROM night_audit_close_snapshots
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
            """.trimIndent(),
            { rs, _ -> mapCloseSnapshot(rs) },
            tenantId,
            propertyId,
            snapshotId,
        ).singleOrNull()
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
        closeData: CloseData = collectCloseData(
            tenantId,
            propertyId,
            auditDate,
        ),
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
            amountAtRisk = billing.openUnpaidBalance,
            resourceType = "folios",
            resourceIds = billing.openUnpaidFolioIds,
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
            amountAtRisk = closeData.fiscal.pendingTotal
                .add(closeData.fiscal.failedTotal)
                .abs(),
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
        addDifferenceIssue(
            issues = issues,
            difference = closeData.billing.revenueJournalDifference,
            runId = runId,
            code = REVENUE_JOURNAL_MISMATCH,
            message = "Revenue-center charges do not reconcile to posted journal credits.",
        )
        addDifferenceIssue(
            issues = issues,
            difference = closeData.payment.allocationDifference,
            runId = runId,
            code = PAYMENT_ALLOCATION_MISMATCH,
            message = "Posted payment allocations do not reconcile to confirmed transactions.",
        )
        addCountIssue(
            issues = issues,
            count = closeData.pos.closedUnsettledOrders,
            runId = runId,
            code = CLOSED_POS_ORDERS_UNSETTLED,
            message = "Closed POS orders remain unposted or unsettled.",
            blocking = true,
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
        amountAtRisk: BigDecimal? = null,
        resourceType: String? = null,
        resourceIds: List<UUID> = emptyList(),
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
            overrideAllowed = code !in NON_OVERRIDABLE_ISSUE_CODES,
            payload = buildMap {
                put("count", count)
                amountAtRisk?.money()?.takeIf { it.signum() > 0 }?.let {
                    put("amountAtRisk", it)
                }
                if (resourceType != null && resourceIds.isNotEmpty()) {
                    put("resourceType", resourceType)
                    put("resourceIds", resourceIds.sortedBy(UUID::toString))
                }
            },
        )
    }

    private fun addDifferenceIssue(
        issues: MutableList<NightAuditIssueDraft>,
        difference: BigDecimal,
        runId: UUID,
        code: String,
        message: String,
    ) {
        val value = difference.money()
        if (value.compareTo(MONEY_ZERO) == 0) {
            return
        }
        issues += NightAuditIssueDraft(
            id = UUID.randomUUID(),
            runId = runId,
            severity = "blocking",
            issueCode = code,
            message = "$message Difference: ${value.toPlainString()}",
            blocking = true,
            overrideAllowed = false,
            payload = mapOf(
                "difference" to value,
                "amountAtRisk" to value.abs(),
            ),
        )
    }

    private fun synchronizeFinancialControlCases(
        actor: TenantActor,
        propertyId: UUID,
        businessDate: LocalDate,
        currency: String,
        issues: List<NightAuditIssueDraft>,
    ) {
        val currentCodes = issues.mapTo(mutableSetOf()) { it.issueCode }
        issues.forEach { issue ->
            val amountAtRisk = issue.payload["amountAtRisk"] as? BigDecimal
            val quantity = (issue.payload["count"] as? Number)?.toInt() ?: 1
            val category = financialControlCategory(issue.issueCode)
            val title = issue.issueCode
                .split('_')
                .joinToString(" ") { word ->
                    word.replaceFirstChar(Char::uppercase)
                }
            val caseId = requireNotNull(
                jdbcTemplate.queryForObject(
                    """
                    INSERT INTO financial_control_cases (
                        tenant_id, property_id, business_date,
                        source_run_id, source_issue_id, issue_code,
                        category, severity, title, description, status,
                        currency, quantity, amount_at_risk
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, ?, ?)
                    ON CONFLICT (
                        tenant_id, property_id, business_date, issue_code
                    ) DO UPDATE SET
                        source_run_id = EXCLUDED.source_run_id,
                        source_issue_id = EXCLUDED.source_issue_id,
                        category = EXCLUDED.category,
                        severity = EXCLUDED.severity,
                        title = EXCLUDED.title,
                        description = EXCLUDED.description,
                        status = CASE
                            WHEN financial_control_cases.status IN (
                                'resolved', 'accepted'
                            ) THEN 'open'
                            ELSE financial_control_cases.status
                        END,
                        currency = EXCLUDED.currency,
                        quantity = EXCLUDED.quantity,
                        amount_at_risk = EXCLUDED.amount_at_risk,
                        assigned_to = CASE
                            WHEN financial_control_cases.status IN (
                                'resolved', 'accepted'
                            ) THEN NULL
                            ELSE financial_control_cases.assigned_to
                        END,
                        assigned_by = CASE
                            WHEN financial_control_cases.status IN (
                                'resolved', 'accepted'
                            ) THEN NULL
                            ELSE financial_control_cases.assigned_by
                        END,
                        assigned_at = CASE
                            WHEN financial_control_cases.status IN (
                                'resolved', 'accepted'
                            ) THEN NULL
                            ELSE financial_control_cases.assigned_at
                        END,
                        due_at = CASE
                            WHEN financial_control_cases.status IN (
                                'resolved', 'accepted'
                            ) THEN NULL
                            ELSE financial_control_cases.due_at
                        END,
                        resolution_type = CASE
                            WHEN financial_control_cases.status IN (
                                'resolved', 'accepted'
                            ) THEN NULL
                            ELSE financial_control_cases.resolution_type
                        END,
                        resolution_note = CASE
                            WHEN financial_control_cases.status IN (
                                'resolved', 'accepted'
                            ) THEN NULL
                            ELSE financial_control_cases.resolution_note
                        END,
                        resolved_by = CASE
                            WHEN financial_control_cases.status IN (
                                'resolved', 'accepted'
                            ) THEN NULL
                            ELSE financial_control_cases.resolved_by
                        END,
                        resolved_at = CASE
                            WHEN financial_control_cases.status IN (
                                'resolved', 'accepted'
                            ) THEN NULL
                            ELSE financial_control_cases.resolved_at
                        END,
                        value_recovered = CASE
                            WHEN financial_control_cases.status IN (
                                'resolved', 'accepted'
                            ) THEN 0
                            ELSE financial_control_cases.value_recovered
                        END,
                        value_protected = CASE
                            WHEN financial_control_cases.status IN (
                                'resolved', 'accepted'
                            ) THEN 0
                            ELSE financial_control_cases.value_protected
                        END,
                        last_detected_at = now(),
                        occurrence_count = financial_control_cases.occurrence_count + 1,
                        version = financial_control_cases.version + 1
                    RETURNING id
                    """.trimIndent(),
                    UUID::class.java,
                    actor.tenantId,
                    propertyId,
                    businessDate,
                    issue.runId,
                    issue.id,
                    issue.issueCode,
                    category,
                    issue.severity,
                    title,
                    issue.message,
                    currency,
                    quantity,
                    amountAtRisk?.abs()?.money(),
                ),
            )
            insertFinancialControlEvidence(
                actor = actor,
                propertyId = propertyId,
                caseId = caseId,
                issue = issue,
                amountAtRisk = amountAtRisk,
            )
            insertFinancialControlEvent(
                actor = actor,
                propertyId = propertyId,
                caseId = caseId,
                eventType = "case.detected",
                payload = mapOf(
                    "sourceRunId" to issue.runId,
                    "sourceIssueId" to issue.id,
                    "issueCode" to issue.issueCode,
                    "quantity" to quantity,
                    "amountAtRisk" to amountAtRisk,
                ),
            )
        }

        jdbcTemplate.query(
            """
            SELECT id, issue_code
            FROM financial_control_cases
            WHERE tenant_id = ?
              AND property_id = ?
              AND business_date = ?
              AND status IN ('open', 'assigned')
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                rs.getObject("id", UUID::class.java) to
                    rs.getString("issue_code")
            },
            actor.tenantId,
            propertyId,
            businessDate,
        ).filter { (_, code) -> code !in currentCodes }
            .forEach { (caseId, issueCode) ->
                jdbcTemplate.update(
                    """
                    UPDATE financial_control_cases
                    SET status = 'resolved',
                        resolution_type = 'source_corrected',
                        resolution_note = ?,
                        value_recovered = 0,
                        value_protected = 0,
                        resolved_by = NULL,
                        resolved_at = now(),
                        version = version + 1
                    WHERE tenant_id = ?
                      AND property_id = ?
                      AND id = ?
                      AND status IN ('open', 'assigned')
                    """.trimIndent(),
                    "Source control passed on a later night-audit evaluation",
                    actor.tenantId,
                    propertyId,
                    caseId,
                )
                insertFinancialControlEvent(
                    actor = actor,
                    propertyId = propertyId,
                    caseId = caseId,
                    eventType = "case.source_corrected",
                    payload = mapOf("issueCode" to issueCode),
                )
            }
    }

    private fun insertFinancialControlEvidence(
        actor: TenantActor,
        propertyId: UUID,
        caseId: UUID,
        issue: NightAuditIssueDraft,
        amountAtRisk: BigDecimal?,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO financial_control_evidence (
                tenant_id, property_id, case_id, source_run_id,
                source_issue_id, evidence_type, amount, payload
            ) VALUES (?, ?, ?, ?, ?, 'control_evaluation', ?, ?::jsonb)
            """.trimIndent(),
            actor.tenantId,
            propertyId,
            caseId,
            issue.runId,
            issue.id,
            amountAtRisk?.abs()?.money(),
            objectMapper.writeValueAsString(issue.payload),
        )
        val resourceType = issue.payload["resourceType"] as? String
        val resourceIds = issue.payload["resourceIds"] as? List<*>
        if (resourceType == null || resourceIds == null) {
            return
        }
        resourceIds.filterIsInstance<UUID>().forEach { resourceId ->
            jdbcTemplate.update(
                """
                INSERT INTO financial_control_evidence (
                    tenant_id, property_id, case_id, source_run_id,
                    source_issue_id, evidence_type, resource_type,
                    resource_id, payload
                ) VALUES (?, ?, ?, ?, ?, 'affected_resource', ?, ?, '{}'::jsonb)
                """.trimIndent(),
                actor.tenantId,
                propertyId,
                caseId,
                issue.runId,
                issue.id,
                resourceType,
                resourceId,
            )
        }
    }

    private fun acceptFinancialControlCase(
        actor: TenantActor,
        propertyId: UUID,
        runId: UUID,
        issueId: UUID,
        reason: String,
    ) {
        val caseId = jdbcTemplate.query(
            """
            SELECT id
            FROM financial_control_cases
            WHERE tenant_id = ?
              AND property_id = ?
              AND source_run_id = ?
              AND source_issue_id = ?
              AND status IN ('open', 'assigned')
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            actor.tenantId,
            propertyId,
            runId,
            issueId,
        ).singleOrNull() ?: return
        jdbcTemplate.update(
            """
            UPDATE financial_control_cases
            SET status = 'accepted',
                resolution_type = 'supervisor_override',
                resolution_note = ?,
                resolved_by = ?,
                resolved_at = now(),
                version = version + 1
            WHERE tenant_id = ? AND property_id = ? AND id = ?
            """.trimIndent(),
            reason,
            actor.tenantUserId,
            actor.tenantId,
            propertyId,
            caseId,
        )
        insertFinancialControlEvent(
            actor = actor,
            propertyId = propertyId,
            caseId = caseId,
            eventType = "case.accepted_by_override",
            payload = mapOf("reason" to reason, "issueId" to issueId),
        )
    }

    private fun insertFinancialControlEvent(
        actor: TenantActor,
        propertyId: UUID,
        caseId: UUID,
        eventType: String,
        payload: Map<String, Any?>,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO financial_control_case_events (
                tenant_id, property_id, case_id, event_type, actor_id, payload
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb)
            """.trimIndent(),
            actor.tenantId,
            propertyId,
            caseId,
            eventType,
            actor.tenantUserId,
            objectMapper.writeValueAsString(payload),
        )
    }

    private fun financialControlCategory(issueCode: String): String = when {
        issueCode.contains("payment") || issueCode.contains("unpaid") ->
            "payment"
        issueCode.contains("fiscal") || issueCode.contains("invoice") ->
            "fiscal"
        issueCode.contains("pos") -> "pos"
        issueCode.contains("revenue") || issueCode.contains("folio") ->
            "revenue"
        else -> "operations"
    }

    private fun collectCloseData(
        tenantId: UUID,
        propertyId: UUID,
        businessDate: LocalDate,
    ): CloseData {
        return CloseData(
            property = propertyOperationsPort.closeSnapshotSummary(
                tenantId,
                propertyId,
            ),
            reservations = reservationCloseSnapshotPort.closeSnapshotSummary(
                tenantId,
                propertyId,
                businessDate,
            ),
            billing = billingSnapshotPort.closeSnapshotSummary(
                tenantId,
                propertyId,
                businessDate,
            ),
            payment = paymentStatusPort.closeSnapshotSummary(
                tenantId,
                propertyId,
                businessDate,
            ),
            fiscal = fiscalStatusPort.closeSnapshotSummary(
                tenantId,
                propertyId,
                businessDate,
            ),
            pos = posStatusPort.closeSnapshotSummary(
                tenantId,
                propertyId,
                businessDate,
            ),
            housekeeping = housekeepingCloseSnapshotPort.closeSnapshotSummary(
                tenantId,
                propertyId,
                businessDate,
            ),
            maintenance = maintenanceCloseSnapshotPort.closeSnapshotSummary(
                tenantId,
                propertyId,
            ),
            inventory = inventoryCloseSnapshotPort.closeSnapshotSummary(
                tenantId,
                propertyId,
                businessDate,
            ),
        )
    }

    private fun captureCloseSnapshot(
        actor: TenantActor,
        propertyId: UUID,
        runId: UUID,
        businessDate: LocalDate,
        closeData: CloseData,
        liveIssues: List<NightAuditIssueDraft>,
        overriddenCodes: Set<String>,
    ): NightAuditCloseSnapshotResponse {
        val availableRooms = (
            closeData.property.totalRooms -
                closeData.maintenance.outOfOrderRooms
            ).coerceAtLeast(0)
        val roomsSold = closeData.reservations.roomsSold
        val roomRevenue = closeData.billing.roomRevenue.money()
        val occupancy = ratio(roomsSold.toBigDecimal(), availableRooms)
        val adr = ratio(roomRevenue, roomsSold)
        val revpar = ratio(roomRevenue, availableRooms)
        val payload = linkedMapOf<String, Any?>(
            "schemaVersion" to CLOSE_SNAPSHOT_SCHEMA_VERSION,
            "businessDate" to businessDate,
            "currency" to closeData.property.currency,
            "rooms" to linkedMapOf(
                "available" to availableRooms,
                "sold" to roomsSold,
                "occupied" to closeData.reservations.occupiedRooms,
                "arrivals" to closeData.reservations.arrivals,
                "departures" to closeData.reservations.departures,
                "noShows" to closeData.reservations.noShows,
                "overdueStays" to closeData.reservations.overdueStays,
                "occupancy" to occupancy,
                "adr" to adr,
                "revpar" to revpar,
            ),
            "revenue" to linkedMapOf(
                "byRevenueCenter" to closeData.billing.revenueByCenter.map {
                    linkedMapOf(
                        "revenueCenterId" to it.revenueCenterId,
                        "amount" to it.amount.money(),
                    )
                },
                "room" to roomRevenue,
                "pos" to closeData.pos.revenue.money(),
                "tax" to closeData.billing.taxTotal.money(),
                "gross" to closeData.billing.grossTotal.money(),
                "net" to closeData.billing.netTotal.money(),
            ),
            "payments" to linkedMapOf(
                "byMethod" to closeData.payment.paymentsByMethod
                    .toSortedMap()
                    .mapValues { it.value.money() },
                "cashVariance" to closeData.payment.cashVariance.money(),
                "providerReconciliation" to
                    closeData.payment.providerReconciliation.money(),
                "refunds" to closeData.payment.refunds.money(),
                "reversals" to closeData.payment.reversals.money(),
            ),
            "fiscal" to linkedMapOf(
                "accepted" to closeData.fiscal.acceptedTotal.money(),
                "pending" to closeData.fiscal.pendingTotal.money(),
                "failed" to closeData.fiscal.failedTotal.money(),
                "corrections" to closeData.fiscal.correctionTotal.money(),
            ),
            "operations" to linkedMapOf(
                "openExceptions" to (
                    closeData.housekeeping.openTasks +
                        closeData.maintenance.openExceptions
                    ),
                "housekeeping" to closeData.housekeeping.states.toSortedMap(),
                "maintenanceBlocks" to linkedMapOf(
                    "outOfOrder" to closeData.maintenance.outOfOrderRooms,
                    "outOfService" to closeData.maintenance.outOfServiceRooms,
                ),
                "lowStock" to closeData.inventory.lowStockItems,
                "waste" to closeData.inventory.wasteTotal.money(),
                "inventoryValue" to closeData.inventory.inventoryValue.money(),
            ),
            "audit" to linkedMapOf(
                "issues" to liveIssues.sortedBy { it.issueCode }.map {
                    linkedMapOf(
                        "code" to it.issueCode,
                        "blocking" to it.blocking,
                        "overrideAllowed" to it.overrideAllowed,
                        "payload" to it.payload.toSortedMap(),
                    )
                },
                "approvedOverrides" to overriddenCodes.sorted(),
                "reconciliationDifferences" to linkedMapOf(
                    "revenueJournal" to
                        closeData.billing.revenueJournalDifference.money(),
                    "paymentAllocation" to
                        closeData.payment.allocationDifference.money(),
                ),
            ),
        )
        val payloadBytes = objectMapper.writeValueAsBytes(payload)
        val payloadHash = sha256Hex(payloadBytes)
        val snapshotId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO night_audit_close_snapshots (
                id, tenant_id, property_id, night_audit_run_id,
                business_date, schema_version, currency, payload,
                payload_hash, available_rooms, rooms_sold, occupied_rooms,
                occupancy, adr, revpar, room_revenue, pos_revenue,
                tax_total, gross_total, net_total,
                revenue_journal_difference, payment_allocation_difference,
                captured_by
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?
            )
            """.trimIndent(),
            snapshotId,
            actor.tenantId,
            propertyId,
            runId,
            businessDate,
            CLOSE_SNAPSHOT_SCHEMA_VERSION,
            closeData.property.currency,
            payloadBytes.toString(Charsets.UTF_8),
            payloadHash,
            availableRooms,
            roomsSold,
            closeData.reservations.occupiedRooms,
            occupancy,
            adr,
            revpar,
            roomRevenue,
            closeData.pos.revenue.money(),
            closeData.billing.taxTotal.money(),
            closeData.billing.grossTotal.money(),
            closeData.billing.netTotal.money(),
            closeData.billing.revenueJournalDifference.money(),
            closeData.payment.allocationDifference.money(),
            actor.tenantUserId,
        )
        return NightAuditCloseSnapshotResponse(
            id = snapshotId,
            tenantId = actor.tenantId,
            propertyId = propertyId,
            nightAuditRunId = runId,
            businessDate = businessDate,
            schemaVersion = CLOSE_SNAPSHOT_SCHEMA_VERSION,
            currency = closeData.property.currency,
            payloadHash = payloadHash,
            availableRooms = availableRooms,
            roomsSold = roomsSold,
            occupiedRooms = closeData.reservations.occupiedRooms,
            occupancy = occupancy,
            adr = adr,
            revpar = revpar,
            roomRevenue = roomRevenue,
            posRevenue = closeData.pos.revenue.money(),
            taxTotal = closeData.billing.taxTotal.money(),
            grossTotal = closeData.billing.grossTotal.money(),
            netTotal = closeData.billing.netTotal.money(),
            revenueJournalDifference =
                closeData.billing.revenueJournalDifference.money(),
            paymentAllocationDifference =
                closeData.payment.allocationDifference.money(),
            payload = payload,
            capturedAt = Instant.now(),
        )
    }

    private fun queueCoreReports(
        actor: TenantActor,
        propertyId: UUID,
        runId: UUID,
        snapshotId: UUID,
        businessDate: LocalDate,
        idempotencyKeyId: UUID,
    ) {
        CORE_REPORT_CODES.forEach { reportCode ->
            outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = "report_runs",
                    aggregateId = snapshotId,
                    tenantId = actor.tenantId,
                    propertyId = propertyId,
                    eventType = "report.generation.requested",
                    destination = OutboxDestination.REPORTS,
                    payload = mapOf(
                        "nightAuditRunId" to runId,
                        "closeSnapshotId" to snapshotId,
                        "reportCode" to reportCode,
                        "businessDate" to businessDate,
                        "generationKey" to
                            "$propertyId:$businessDate:$reportCode",
                    ),
                    idempotencyKeyId = idempotencyKeyId,
                    priority = 2,
                ),
            )
        }
    }

    private fun mapCloseSnapshot(rs: ResultSet): NightAuditCloseSnapshotResponse {
        return NightAuditCloseSnapshotResponse(
            id = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            nightAuditRunId = rs.getObject(
                "night_audit_run_id",
                UUID::class.java,
            ),
            businessDate = rs.getObject(
                "business_date",
                LocalDate::class.java,
            ),
            schemaVersion = rs.getInt("schema_version"),
            currency = rs.getString("currency").trim(),
            payloadHash = rs.getString("payload_hash"),
            availableRooms = rs.getInt("available_rooms"),
            roomsSold = rs.getInt("rooms_sold"),
            occupiedRooms = rs.getInt("occupied_rooms"),
            occupancy = rs.getBigDecimal("occupancy").ratio(),
            adr = rs.getBigDecimal("adr").money(),
            revpar = rs.getBigDecimal("revpar").money(),
            roomRevenue = rs.getBigDecimal("room_revenue").money(),
            posRevenue = rs.getBigDecimal("pos_revenue").money(),
            taxTotal = rs.getBigDecimal("tax_total").money(),
            grossTotal = rs.getBigDecimal("gross_total").money(),
            netTotal = rs.getBigDecimal("net_total").money(),
            revenueJournalDifference = rs.getBigDecimal(
                "revenue_journal_difference",
            ).money(),
            paymentAllocationDifference = rs.getBigDecimal(
                "payment_allocation_difference",
            ).money(),
            payload = summary(rs.getString("payload")),
            capturedAt = rs.getTimestamp("captured_at").toInstant(),
        )
    }

    private fun ratio(numerator: BigDecimal, denominator: Int): BigDecimal {
        if (denominator == 0) {
            return RATIO_ZERO
        }
        return numerator.divide(
            denominator.toBigDecimal(),
            2,
            RoundingMode.HALF_UP,
        )
    }

    private fun BigDecimal.money(): BigDecimal =
        setScale(2, RoundingMode.HALF_UP)

    private fun BigDecimal.ratio(): BigDecimal =
        setScale(2, RoundingMode.HALF_UP)

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
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
            reportGenerationQueued =
                summary(rs.getString("summary"))["reportGenerationQueued"] == true,
        )
    }

    private fun issues(
        tenantId: UUID,
        propertyId: UUID,
        runId: UUID,
    ): List<NightAuditIssueResponse> {
        return jdbcTemplate.query(
            """
            SELECT id, run_id, property_id, severity, issue_code, message,
                   blocking, override_allowed, resolved_at, resolution_note
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
                    overrideAllowed = rs.getBoolean("override_allowed"),
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
        val overrideAllowed: Boolean,
        val payload: Map<String, Any?>,
    )

    private data class NightAuditRunState(
        val auditDate: LocalDate,
        val status: String,
        val summary: Map<String, Any?>,
    )

    private data class CloseData(
        val property: PropertyCloseSnapshotSummary,
        val reservations: ReservationCloseSnapshotSummary,
        val billing: BillingCloseSnapshotSummary,
        val payment: PaymentCloseSnapshotSummary,
        val fiscal: FiscalCloseSnapshotSummary,
        val pos: PosCloseSnapshotSummary,
        val housekeeping: HousekeepingCloseSnapshotSummary,
        val maintenance: MaintenanceCloseSnapshotSummary,
        val inventory: InventoryCloseSnapshotSummary,
    )

    private companion object {
        const val REVENUE_JOURNAL_MISMATCH = "revenue_journal_mismatch"
        const val PAYMENT_ALLOCATION_MISMATCH = "payment_allocation_mismatch"
        const val CLOSED_POS_ORDERS_UNSETTLED = "closed_pos_orders_unsettled"
        const val NIGHT_AUDIT_RUNS = "night_audit_runs"
        const val CLOSE_SNAPSHOT_SCHEMA_VERSION = 1
        val MONEY_ZERO: BigDecimal = BigDecimal.ZERO.setScale(2)
        val RATIO_ZERO: BigDecimal = BigDecimal.ZERO.setScale(2)
        val NON_OVERRIDABLE_ISSUE_CODES = setOf(
            "open_unpaid_folios",
            REVENUE_JOURNAL_MISMATCH,
            PAYMENT_ALLOCATION_MISMATCH,
            CLOSED_POS_ORDERS_UNSETTLED,
        )
        val CORE_REPORT_CODES = listOf(
            "daily_management_summary",
            "night_audit_close",
        )
        val logger = LoggerFactory.getLogger(NightAuditService::class.java)
    }
}

private fun DataIntegrityViolationException.publicDatabaseMessage(): String {
    return "Night-audit request conflicts with existing operational data"
}
