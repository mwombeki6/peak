package com.mwombeki.peak.nightaudit.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.nightaudit.api.AssignFinancialControlCaseRequest
import com.mwombeki.peak.nightaudit.api.DailyCloseCertification
import com.mwombeki.peak.nightaudit.api.DailyControlBriefResponse
import com.mwombeki.peak.nightaudit.api.DailyFinancialTruth
import com.mwombeki.peak.nightaudit.api.DailyRevenueAssurance
import com.mwombeki.peak.nightaudit.api.FinancialControlCaseEventResponse
import com.mwombeki.peak.nightaudit.api.FinancialControlCaseResponse
import com.mwombeki.peak.nightaudit.api.FinancialControlConflictException
import com.mwombeki.peak.nightaudit.api.FinancialControlEvidenceResponse
import com.mwombeki.peak.nightaudit.api.FinancialControlInProgressException
import com.mwombeki.peak.nightaudit.api.FinancialControlNotFoundException
import com.mwombeki.peak.nightaudit.api.FinancialControlPort
import com.mwombeki.peak.nightaudit.api.NightAuditCloseSnapshotPort
import com.mwombeki.peak.nightaudit.api.ResolveFinancialControlCaseRequest
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.TenantActor
import com.mwombeki.peak.shared.context.TenantRequestContext
import com.mwombeki.peak.usermanagement.api.PropertyStaffDirectoryPort
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Service
class FinancialControlService(
    private val jdbcTemplate: JdbcTemplate,
    private val tenantRequestContext: TenantRequestContext,
    private val transactionTemplate: TransactionTemplate,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val objectMapper: ObjectMapper,
    private val closeSnapshotPort: NightAuditCloseSnapshotPort,
    private val propertyStaffDirectoryPort: PropertyStaffDirectoryPort,
) : FinancialControlPort {
    override fun dailyBrief(
        propertyId: UUID,
        businessDate: LocalDate,
    ): DailyControlBriefResponse = read(propertyId) { actor ->
        val snapshotId = jdbcTemplate.query(
            """
            SELECT id
            FROM night_audit_close_snapshots
            WHERE tenant_id = ?
              AND property_id = ?
              AND business_date = ?
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            actor.tenantId,
            propertyId,
            businessDate,
        ).singleOrNull() ?: throw FinancialControlNotFoundException(
            "A certified close snapshot is not available for this business date",
        )
        val snapshot = closeSnapshotPort.getCloseSnapshotById(
            actor.tenantId,
            propertyId,
            snapshotId,
        ) ?: throw FinancialControlNotFoundException(
            "Certified close snapshot was not found",
        )
        val cases = listCaseRows(
            actor.tenantId,
            propertyId,
            businessDate,
            null,
            MAX_LIST_LIMIT,
        )
        val payments = snapshot.payload.mapSection("payments")
        val byMethod = payments.mapSection("byMethod")
            .mapValues { (_, value) -> value.moneyValue() }
            .toSortedMap()
        val openCases = cases.count { it.status == "open" }
        val assignedCases = cases.count { it.status == "assigned" }
        val acceptedCases = cases.count { it.status == "accepted" }
        DailyControlBriefResponse(
            propertyId = propertyId,
            businessDate = businessDate,
            generatedAt = Instant.now(),
            close = DailyCloseCertification(
                status = "certified",
                runId = snapshot.nightAuditRunId,
                snapshotId = snapshot.id,
                snapshotHash = snapshot.payloadHash,
                capturedAt = snapshot.capturedAt,
                cleanClose = cases.all { it.status == "resolved" },
                closeWithAcceptedExceptions = acceptedCases > 0,
            ),
            financialTruth = DailyFinancialTruth(
                currency = snapshot.currency,
                revenueRecognized = snapshot.netTotal.money(),
                grossSales = snapshot.grossTotal.money(),
                taxTotal = snapshot.taxTotal.money(),
                roomRevenue = snapshot.roomRevenue.money(),
                posRevenue = snapshot.posRevenue.money(),
                cashAndDigitalCollected = byMethod.values
                    .fold(MONEY_ZERO, BigDecimal::add)
                    .money(),
                paymentsByMethod = byMethod,
                cashVariance = payments.money("cashVariance"),
                providerReconciliationVariance =
                    payments.money("providerReconciliation"),
                refunds = payments.money("refunds"),
                reversals = payments.money("reversals"),
                revenueJournalDifference =
                    snapshot.revenueJournalDifference.money(),
                paymentAllocationDifference =
                    snapshot.paymentAllocationDifference.money(),
            ),
            revenueAssurance = DailyRevenueAssurance(
                totalCases = cases.size,
                openCases = openCases,
                assignedCases = assignedCases,
                resolvedCases = cases.count { it.status == "resolved" },
                acceptedCases = acceptedCases,
                quantifiedAmountAtRisk = cases
                    .filter { it.status in ACTIVE_STATUSES }
                    .mapNotNull { it.amountAtRisk }
                    .fold(MONEY_ZERO, BigDecimal::add)
                    .money(),
                recordedValueRecovered = cases
                    .fold(MONEY_ZERO) { total, case ->
                        total.add(case.valueRecovered)
                    }.money(),
                recordedValueProtected = cases
                    .fold(MONEY_ZERO) { total, case ->
                        total.add(case.valueProtected)
                    }.money(),
            ),
            actions = cases.filter { it.status in ACTIVE_STATUSES },
        )
    }

    override fun listCases(
        propertyId: UUID,
        businessDate: LocalDate?,
        status: String?,
        limit: Int,
    ): List<FinancialControlCaseResponse> = read(propertyId) { actor ->
        require(limit in 1..MAX_LIST_LIMIT) {
            "limit must be between 1 and $MAX_LIST_LIMIT"
        }
        val normalizedStatus = status?.trim()?.lowercase()?.also {
            require(it in CASE_STATUSES) { "Unsupported financial-control status" }
        }
        listCaseRows(
            actor.tenantId,
            propertyId,
            businessDate,
            normalizedStatus,
            limit,
        )
    }

    override fun getCase(
        propertyId: UUID,
        caseId: UUID,
    ): FinancialControlCaseResponse? = readNullable(propertyId) { actor ->
        caseRow(actor.tenantId, propertyId, caseId)?.withHistory(actor.tenantId)
    }

    override fun assignCase(
        propertyId: UUID,
        caseId: UUID,
        request: AssignFinancialControlCaseRequest,
    ): FinancialControlCaseResponse = mutate(
        propertyId = propertyId,
        operation = "financial_control.case.assign",
        payload = mapOf("caseId" to caseId, "request" to request),
    ) { actor, idempotencyKeyId ->
        require(
            propertyStaffDirectoryPort.isActivePropertyStaff(
                actor.tenantId,
                propertyId,
                request.assigneeId,
            ),
        ) { "Assignee must be active staff for this property" }
        request.dueAt?.let {
            require(it.isAfter(Instant.now())) { "dueAt must be in the future" }
        }
        requireMutableCase(actor.tenantId, propertyId, caseId)
        jdbcTemplate.update(
            """
            UPDATE financial_control_cases
            SET status = 'assigned',
                assigned_to = ?,
                assigned_by = ?,
                assigned_at = now(),
                due_at = ?,
                version = version + 1
            WHERE tenant_id = ? AND property_id = ? AND id = ?
            """.trimIndent(),
            request.assigneeId,
            actor.tenantUserId,
            request.dueAt,
            actor.tenantId,
            propertyId,
            caseId,
        )
        val eventPayload = mapOf(
            "caseId" to caseId,
            "assigneeId" to request.assigneeId,
            "dueAt" to request.dueAt,
        )
        appendEvent(actor, propertyId, caseId, "case.assigned", eventPayload)
        effects(
            actor,
            propertyId,
            caseId,
            "financial_control.case.assigned",
            eventPayload,
            idempotencyKeyId,
        )
        requireNotNull(caseRow(actor.tenantId, propertyId, caseId))
    }

    override fun resolveCase(
        propertyId: UUID,
        caseId: UUID,
        request: ResolveFinancialControlCaseRequest,
    ): FinancialControlCaseResponse = mutate(
        propertyId = propertyId,
        operation = "financial_control.case.resolve",
        payload = mapOf("caseId" to caseId, "request" to request),
    ) { actor, idempotencyKeyId ->
        val resolutionType = request.resolutionType.trim().lowercase()
        require(resolutionType in RESOLUTION_TYPES) {
            "Unsupported financial-control resolution type"
        }
        val note = request.note.trim()
        require(note.length in 10..1000) {
            "Resolution note must contain between 10 and 1000 characters"
        }
        val recovered = request.valueRecovered.validMoney("valueRecovered")
        val protected = request.valueProtected.validMoney("valueProtected")
        if (resolutionType == "recovered") {
            require(recovered.signum() > 0) {
                "Recovered resolution requires a positive valueRecovered"
            }
        }
        if (resolutionType == "protected") {
            require(protected.signum() > 0) {
                "Protected resolution requires a positive valueProtected"
            }
        }
        val existing = requireMutableCase(actor.tenantId, propertyId, caseId)
        existing.amountAtRisk?.let { risk ->
            require(recovered.add(protected) <= risk) {
                "Recorded value cannot exceed the quantified amount at risk"
            }
        }
        jdbcTemplate.update(
            """
            UPDATE financial_control_cases
            SET status = 'resolved',
                resolution_type = ?,
                resolution_note = ?,
                value_recovered = ?,
                value_protected = ?,
                resolved_by = ?,
                resolved_at = now(),
                version = version + 1
            WHERE tenant_id = ? AND property_id = ? AND id = ?
            """.trimIndent(),
            resolutionType,
            note,
            recovered,
            protected,
            actor.tenantUserId,
            actor.tenantId,
            propertyId,
            caseId,
        )
        val eventPayload = mapOf(
            "caseId" to caseId,
            "resolutionType" to resolutionType,
            "valueRecovered" to recovered,
            "valueProtected" to protected,
        )
        appendEvent(actor, propertyId, caseId, "case.resolved", eventPayload)
        effects(
            actor,
            propertyId,
            caseId,
            "financial_control.case.resolved",
            eventPayload,
            idempotencyKeyId,
        )
        requireNotNull(caseRow(actor.tenantId, propertyId, caseId))
    }

    private fun requireMutableCase(
        tenantId: UUID,
        propertyId: UUID,
        caseId: UUID,
    ): FinancialControlCaseResponse {
        val response = jdbcTemplate.query(
            """
            SELECT *
            FROM financial_control_cases
            WHERE tenant_id = ? AND property_id = ? AND id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> mapCase(rs) },
            tenantId,
            propertyId,
            caseId,
        ).singleOrNull() ?: throw FinancialControlNotFoundException(
            "Financial-control case was not found",
        )
        if (response.status !in ACTIVE_STATUSES) {
            throw FinancialControlConflictException(
                "Only open or assigned financial-control cases can be changed",
            )
        }
        return response
    }

    private fun listCaseRows(
        tenantId: UUID,
        propertyId: UUID,
        businessDate: LocalDate?,
        status: String?,
        limit: Int,
    ): List<FinancialControlCaseResponse> {
        val parameters = mutableListOf<Any>(tenantId, propertyId)
        val dateClause = if (businessDate == null) "" else {
            parameters += businessDate
            " AND business_date = ?"
        }
        val statusClause = if (status == null) "" else {
            parameters += status
            " AND status = ?"
        }
        parameters += limit
        return jdbcTemplate.query(
            """
            SELECT *
            FROM financial_control_cases
            WHERE tenant_id = ? AND property_id = ?
            $dateClause$statusClause
            ORDER BY
                CASE status WHEN 'open' THEN 0 WHEN 'assigned' THEN 1 ELSE 2 END,
                due_at NULLS LAST,
                last_detected_at DESC,
                id
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> mapCase(rs) },
            *parameters.toTypedArray(),
        )
    }

    private fun caseRow(
        tenantId: UUID,
        propertyId: UUID,
        caseId: UUID,
    ): FinancialControlCaseResponse? = jdbcTemplate.query(
        """
        SELECT *
        FROM financial_control_cases
        WHERE tenant_id = ? AND property_id = ? AND id = ?
        """.trimIndent(),
        { rs, _ -> mapCase(rs) },
        tenantId,
        propertyId,
        caseId,
    ).singleOrNull()

    private fun FinancialControlCaseResponse.withHistory(
        tenantId: UUID,
    ): FinancialControlCaseResponse = copy(
        evidence = jdbcTemplate.query(
            """
            SELECT id, evidence_type, resource_type, resource_id, amount,
                   payload, recorded_at
            FROM financial_control_evidence
            WHERE tenant_id = ? AND case_id = ?
            ORDER BY recorded_at, id
            """.trimIndent(),
            { rs, _ ->
                FinancialControlEvidenceResponse(
                    id = rs.getObject("id", UUID::class.java),
                    evidenceType = rs.getString("evidence_type"),
                    resourceType = rs.getString("resource_type"),
                    resourceId = rs.getObject("resource_id", UUID::class.java),
                    amount = rs.getBigDecimal("amount")?.money(),
                    payload = jsonMap(rs.getString("payload")),
                    recordedAt = rs.getTimestamp("recorded_at").toInstant(),
                )
            },
            tenantId,
            id,
        ),
        events = jdbcTemplate.query(
            """
            SELECT id, event_type, actor_id, payload, occurred_at
            FROM financial_control_case_events
            WHERE tenant_id = ? AND case_id = ?
            ORDER BY occurred_at, id
            """.trimIndent(),
            { rs, _ ->
                FinancialControlCaseEventResponse(
                    id = rs.getObject("id", UUID::class.java),
                    eventType = rs.getString("event_type"),
                    actorId = rs.getObject("actor_id", UUID::class.java),
                    payload = jsonMap(rs.getString("payload")),
                    occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                )
            },
            tenantId,
            id,
        ),
    )

    private fun mapCase(rs: ResultSet): FinancialControlCaseResponse =
        FinancialControlCaseResponse(
            id = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            businessDate = rs.getObject("business_date", LocalDate::class.java),
            sourceRunId = rs.getObject("source_run_id", UUID::class.java),
            sourceIssueId = rs.getObject("source_issue_id", UUID::class.java),
            issueCode = rs.getString("issue_code"),
            category = rs.getString("category"),
            severity = rs.getString("severity"),
            title = rs.getString("title"),
            description = rs.getString("description"),
            status = rs.getString("status"),
            currency = rs.getString("currency").trim(),
            quantity = rs.getInt("quantity"),
            amountAtRisk = rs.getBigDecimal("amount_at_risk")?.money(),
            assignedTo = rs.getObject("assigned_to", UUID::class.java),
            assignedBy = rs.getObject("assigned_by", UUID::class.java),
            assignedAt = rs.getTimestamp("assigned_at")?.toInstant(),
            dueAt = rs.getTimestamp("due_at")?.toInstant(),
            resolutionType = rs.getString("resolution_type"),
            resolutionNote = rs.getString("resolution_note"),
            valueRecovered = rs.getBigDecimal("value_recovered").money(),
            valueProtected = rs.getBigDecimal("value_protected").money(),
            resolvedBy = rs.getObject("resolved_by", UUID::class.java),
            resolvedAt = rs.getTimestamp("resolved_at")?.toInstant(),
            firstDetectedAt = rs.getTimestamp("first_detected_at").toInstant(),
            lastDetectedAt = rs.getTimestamp("last_detected_at").toInstant(),
            occurrenceCount = rs.getInt("occurrence_count"),
            version = rs.getInt("version"),
        )

    private fun appendEvent(
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

    private fun effects(
        actor: TenantActor,
        propertyId: UUID,
        caseId: UUID,
        action: String,
        payload: Map<String, Any?>,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                actor.tenantId,
                action,
                AuditResource(CASE_RESOURCE, caseId),
                after = payload,
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = CASE_RESOURCE,
                aggregateId = caseId,
                tenantId = actor.tenantId,
                propertyId = propertyId,
                eventType = action,
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                idempotencyKeyId = idempotencyKeyId,
            ),
        )
    }

    private fun <T> read(propertyId: UUID, block: (TenantActor) -> T): T =
        requireNotNull(transactionTemplate.execute { block(bind(propertyId)) })

    private fun <T> readNullable(
        propertyId: UUID,
        block: (TenantActor) -> T?,
    ): T? = transactionTemplate.execute { block(bind(propertyId)) }

    private fun mutate(
        propertyId: UUID,
        operation: String,
        payload: Any,
        block: (TenantActor, UUID) -> FinancialControlCaseResponse,
    ): FinancialControlCaseResponse = requireNotNull(transactionTemplate.execute {
        val actor = bind(propertyId)
        when (
            val reservation = idempotencyPort.reserve(
                IdempotencyCommand(operation, payload, CASE_RESOURCE),
            )
        ) {
            is IdempotencyReservation.Started -> try {
                block(actor, reservation.recordId).also { response ->
                    idempotencyPort.markSucceeded(
                        reservation.recordId,
                        200,
                        response,
                        response.id,
                    )
                }
            } catch (_: DataIntegrityViolationException) {
                throw FinancialControlConflictException(
                    "Financial-control command conflicts with current data",
                )
            }
            is IdempotencyReservation.Replay -> objectMapper.readValue(
                reservation.responseBody
                    ?: throw FinancialControlConflictException(
                        "Stored financial-control replay is missing",
                    ),
                FinancialControlCaseResponse::class.java,
            ).copy(replayed = true)
            is IdempotencyReservation.InProgress ->
                throw FinancialControlInProgressException(
                    "Financial-control command is already in progress",
                )
            is IdempotencyReservation.Conflict ->
                throw FinancialControlConflictException(
                    "Idempotency key was used for another command",
                )
        }
    })

    private fun bind(propertyId: UUID): TenantActor =
        tenantRequestContext.bind().also {
            tenantRequestContext.requirePropertyUsable(it.tenantId, propertyId)
        }

    @Suppress("UNCHECKED_CAST")
    private fun jsonMap(value: String): Map<String, Any?> =
        objectMapper.readValue(value, Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.mapSection(key: String): Map<String, Any?> =
        this[key] as? Map<String, Any?> ?: emptyMap()

    private fun Map<String, Any?>.money(key: String): BigDecimal =
        this[key].moneyValue()

    private fun Any?.moneyValue(): BigDecimal = when (this) {
        is BigDecimal -> this.money()
        is Number -> BigDecimal(toString()).money()
        is String -> toBigDecimalOrNull()?.money() ?: MONEY_ZERO
        else -> MONEY_ZERO
    }

    private fun BigDecimal.validMoney(field: String): BigDecimal {
        require(signum() >= 0 && scale() <= 2) {
            "$field must be non-negative with no more than two decimal places"
        }
        return money()
    }

    private fun BigDecimal.money(): BigDecimal =
        setScale(2, RoundingMode.HALF_UP)

    private companion object {
        const val CASE_RESOURCE = "financial_control_cases"
        const val MAX_LIST_LIMIT = 200
        val MONEY_ZERO: BigDecimal = BigDecimal.ZERO.setScale(2)
        val CASE_STATUSES = setOf("open", "assigned", "resolved", "accepted")
        val ACTIVE_STATUSES = setOf("open", "assigned")
        val RESOLUTION_TYPES = setOf(
            "recovered",
            "protected",
            "source_corrected",
            "false_positive",
        )
    }
}
