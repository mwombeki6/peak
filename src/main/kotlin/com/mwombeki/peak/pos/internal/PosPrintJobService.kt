package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.pos.api.KitchenTicketResponse
import com.mwombeki.peak.pos.api.PosConflictException
import com.mwombeki.peak.pos.api.PosNotFoundException
import com.mwombeki.peak.pos.api.PosOrderItemResponse
import com.mwombeki.peak.pos.api.PosOrderResponse
import com.mwombeki.peak.pos.api.PosPrintJobFailureRequest
import com.mwombeki.peak.pos.api.PosPrintJobReclaimRequest
import com.mwombeki.peak.pos.api.PosPrintJobResponse
import com.mwombeki.peak.pos.api.PosSessionResponse
import com.mwombeki.peak.realtime.api.RealtimeEventRequest
import com.mwombeki.peak.realtime.api.RealtimeEventTypes
import com.mwombeki.peak.realtime.api.RealtimePort
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.TenantActor
import java.math.BigDecimal
import java.sql.ResultSet
import java.util.UUID
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class PosPrintJobService(
    private val jdbc: JdbcTemplate,
    private val commands: PosCommandExecutor,
    private val requestContextHolder: RequestContextHolder,
    private val realtime: ObjectProvider<RealtimePort>,
    private val mapper: ObjectMapper,
) {
    /**
     * Called from inside an already-open POS mutate (kitchen send). Does not
     * start a nested command — the ticket and the job must commit together.
     */
    fun enqueueKitchenTicket(
        actor: TenantActor,
        propertyId: UUID,
        outletId: UUID,
        ticket: KitchenTicketResponse,
        tableNumber: String?,
        orderNumber: String?,
    ) {
        val payload = mapOf(
            "ticketNumber" to ticket.ticketNumber,
            "orderNumber" to orderNumber,
            "tableNumber" to tableNumber,
            "sentAt" to ticket.sentAt.toString(),
            "lines" to ticket.items.map { item ->
                mapOf(
                    "name" to item.itemName,
                    "quantity" to item.quantity.toPlainString(),
                    "note" to item.specialRequest,
                    "modifiers" to item.modifiers,
                )
            },
        )
        insertJobs(
            tenantId = actor.tenantId,
            propertyId = propertyId,
            outletId = outletId,
            jobType = "kitchen_ticket",
            sourceType = "kitchen_ticket",
            sourceId = ticket.id,
            category = "kitchen",
            document = stationDocument("kitchen_ticket", payload),
            unroutedFallback = true,
        )
        insertJobs(
            tenantId = actor.tenantId,
            propertyId = propertyId,
            outletId = outletId,
            jobType = "bar_ticket",
            sourceType = "kitchen_ticket",
            sourceId = ticket.id,
            category = "bar",
            document = stationDocument("bar_ticket", payload),
            unroutedFallback = false,
        )
        insertJobs(
            tenantId = actor.tenantId,
            propertyId = propertyId,
            outletId = outletId,
            jobType = "expo_ticket",
            sourceType = "kitchen_ticket",
            sourceId = ticket.id,
            category = "pass",
            document = stationDocument("expo_ticket", payload),
            unroutedFallback = false,
        )
    }

    fun enqueueVoidTicket(
        tenantId: UUID,
        propertyId: UUID,
        outletId: UUID,
        orderId: UUID,
        orderNumber: String?,
        itemId: UUID,
        itemName: String,
        quantity: String,
        disposition: String,
        reason: String,
    ) {
        val document = mapper.writeValueAsString(
            mapOf(
                "kind" to "void_ticket",
                "orderId" to orderId.toString(),
                "orderNumber" to orderNumber,
                "itemName" to itemName,
                "quantity" to quantity,
                "disposition" to disposition,
                "reason" to reason,
            ),
        )
        insertJobs(
            tenantId = tenantId,
            propertyId = propertyId,
            outletId = outletId,
            jobType = "void_ticket",
            sourceType = "pos_order",
            sourceId = itemId,
            category = "kitchen",
            document = document,
            unroutedFallback = true,
        )
    }

    fun enqueueShiftReport(
        tenantId: UUID,
        propertyId: UUID,
        session: PosSessionResponse,
    ) {
        val document = mapper.writeValueAsString(
            mapOf(
                "kind" to "shift_report",
                "sessionId" to session.id.toString(),
                "status" to session.status,
                "openingFloat" to session.openingFloat.toPlainString(),
                "expectedCash" to session.expectedCash.toPlainString(),
                "closingCash" to session.closingCash?.toPlainString(),
                "variance" to session.variance?.toPlainString(),
            ),
        )
        insertJobs(
            tenantId = tenantId,
            propertyId = propertyId,
            outletId = session.outletId,
            jobType = "shift_report",
            sourceType = "pos_session",
            sourceId = session.id,
            category = "receipt",
            document = document,
            unroutedFallback = true,
        )
    }

    private fun stationDocument(kind: String, payload: Map<String, Any?>): String =
        mapper.writeValueAsString(
            linkedMapOf<String, Any?>("kind" to kind).apply { putAll(payload) },
        )

    private fun insertJobs(
        tenantId: UUID,
        propertyId: UUID,
        outletId: UUID,
        jobType: String,
        sourceType: String,
        sourceId: UUID,
        category: String,
        document: String,
        unroutedFallback: Boolean,
    ) {
        val routeIds = activeRoutes(tenantId, propertyId, outletId, category)
            .let { routes -> if (routes.isEmpty() && unroutedFallback) listOf(null) else routes }
        routeIds.forEach { routeId ->
            val jobId = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO pos_print_jobs (
                    id, tenant_id, property_id, outlet_id, printer_route_id,
                    job_type, source_type, source_id, source_version, is_reprint,
                    status, document
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false, 'pending', ?::jsonb)
                """.trimIndent(),
                jobId, tenantId, propertyId, outletId, routeId,
                jobType, sourceType, sourceId, 0L, document,
            )
            publish(tenantId, propertyId, outletId, jobId, RealtimeEventTypes.PRINT_JOB_CREATED)
        }
    }

    /**
     * Guest receipt for a settled POS order. Called from inside the settle
     * mutate or the mobile-money confirm TX so the job and the payment commit
     * together. A later printer failure cannot reopen the order.
     */
    fun enqueueReceipt(
        tenantId: UUID,
        propertyId: UUID,
        order: PosOrderResponse,
    ) {
        enqueueReceiptDocument(tenantId, propertyId, order)
    }

    fun enqueueReceipt(
        tenantId: UUID,
        propertyId: UUID,
        orderId: UUID,
    ) {
        enqueueReceiptDocument(tenantId, propertyId, loadOrderForReceipt(tenantId, propertyId, orderId))
    }

    private fun enqueueReceiptDocument(
        tenantId: UUID,
        propertyId: UUID,
        order: PosOrderResponse,
    ) {
        val document = mapper.writeValueAsString(
            mapOf(
                "kind" to "receipt",
                "orderNumber" to order.orderNumber,
                "tableNumber" to order.tableNumber,
                "settledAt" to order.settledAt?.toString(),
                "settlementMethod" to order.settlementMethod,
                "subtotal" to order.subtotal.toPlainString(),
                "taxAmount" to order.taxAmount.toPlainString(),
                "totalAmount" to order.totalAmount.toPlainString(),
                "lines" to order.items.map { item ->
                    mapOf(
                        "name" to item.name,
                        "quantity" to item.quantity.toPlainString(),
                        "unitPrice" to item.unitPrice.toPlainString(),
                        "total" to item.totalPrice.toPlainString(),
                    )
                },
            ),
        )
        insertJobs(
            tenantId = tenantId,
            propertyId = propertyId,
            outletId = order.outletId,
            jobType = "receipt",
            sourceType = "pos_order",
            sourceId = order.id,
            category = "receipt",
            document = document,
            unroutedFallback = true,
        )
    }

    private fun loadOrderForReceipt(
        tenantId: UUID,
        propertyId: UUID,
        orderId: UUID,
    ): PosOrderResponse {
        val items = jdbc.query(
            """
            SELECT item_name, quantity, unit_price, total_price
            FROM pos_order_items
            WHERE tenant_id = ? AND order_id = ? AND voided = false
            ORDER BY created_at, id
            """.trimIndent(),
            { rs, _ ->
                PosOrderItemResponse(
                    id = UUID.randomUUID(),
                    menuItemId = UUID.randomUUID(),
                    name = rs.getString("item_name"),
                    quantity = rs.getBigDecimal("quantity"),
                    unitPrice = rs.getBigDecimal("unit_price"),
                    subtotal = rs.getBigDecimal("unit_price"),
                    taxAmount = BigDecimal.ZERO,
                    totalPrice = rs.getBigDecimal("total_price"),
                    modifiers = emptyList(),
                    specialRequest = null,
                )
            },
            tenantId,
            orderId,
        )
        return jdbc.query(
            """
            SELECT id, outlet_id, session_id, order_number, order_type, table_number,
                   status, settlement_status, settlement_method, folio_id,
                   payment_transaction_id, subtotal, tax_amount, total_amount,
                   created_at, settled_at
            FROM pos_orders
            WHERE tenant_id = ? AND property_id = ? AND id = ? AND deleted_at IS NULL
            """.trimIndent(),
            { rs, _ ->
                PosOrderResponse(
                    id = rs.getObject("id", UUID::class.java),
                    propertyId = propertyId,
                    outletId = rs.getObject("outlet_id", UUID::class.java),
                    sessionId = rs.getObject("session_id", UUID::class.java),
                    orderNumber = rs.getString("order_number"),
                    orderType = rs.getString("order_type"),
                    tableNumber = rs.getString("table_number"),
                    status = rs.getString("status"),
                    settlementStatus = rs.getString("settlement_status"),
                    settlementMethod = rs.getString("settlement_method"),
                    folioId = rs.getObject("folio_id", UUID::class.java),
                    paymentTransactionId = rs.getObject("payment_transaction_id", UUID::class.java),
                    subtotal = rs.getBigDecimal("subtotal"),
                    taxAmount = rs.getBigDecimal("tax_amount"),
                    totalAmount = rs.getBigDecimal("total_amount"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    settledAt = rs.getTimestamp("settled_at")?.toInstant(),
                    items = items,
                )
            },
            tenantId,
            propertyId,
            orderId,
        ).singleOrNull() ?: error("POS order was not found for receipt")
    }

    fun listJobs(propertyId: UUID, status: String?): List<PosPrintJobResponse> =
        commands.read(propertyId) { actor ->
            val outletId = requestContextHolder.current().boundOutletId
            val statuses = parseStatuses(status)
            val inList = statuses.joinToString(",") { "?" }
            val sql = """
                SELECT $JOB_COLUMNS
                FROM pos_print_jobs
                WHERE tenant_id = ? AND property_id = ?
                  AND (CAST(? AS uuid) IS NULL OR outlet_id = CAST(? AS uuid))
                  AND status IN ($inList)
                ORDER BY created_at, id
            """.trimIndent()
            jdbc.query({ connection ->
                val statement = connection.prepareStatement(sql)
                var index = 1
                statement.setObject(index++, actor.tenantId)
                statement.setObject(index++, propertyId)
                statement.setObject(index++, outletId)
                statement.setObject(index++, outletId)
                statuses.forEach { statement.setString(index++, it) }
                statement
            }, ::mapJob)
        }

    fun claim(propertyId: UUID, jobId: UUID): PosPrintJobResponse =
        mutate(propertyId, "pos.print.claim", jobId) { actor, deviceId ->
            val claimed = jdbc.query(
                """
                UPDATE pos_print_jobs AS job
                SET status = 'claimed',
                    claimed_by_device_id = ?,
                    claimed_at = now(),
                    attempts = attempts + 1
                WHERE job.id = (
                    SELECT candidate.id
                    FROM pos_print_jobs candidate
                    WHERE candidate.tenant_id = ?
                      AND candidate.property_id = ?
                      AND candidate.id = ?
                      AND candidate.status = 'pending'
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING $JOB_COLUMNS
                """.trimIndent(),
                ::mapJob,
                deviceId, actor.tenantId, propertyId, jobId,
            ).singleOrNull() ?: throw claimConflict(actor.tenantId, propertyId, jobId)
            publish(
                actor.tenantId, propertyId, claimed.outletId, jobId,
                RealtimeEventTypes.PRINT_JOB_CLAIMED,
            )
            claimed
        }

    fun printed(propertyId: UUID, jobId: UUID): PosPrintJobResponse =
        mutate(propertyId, "pos.print.printed", jobId) { actor, deviceId ->
            ack(
                actor, propertyId, jobId, deviceId,
                """
                UPDATE pos_print_jobs
                SET status = 'printed', printed_at = now(), last_error = NULL
                WHERE tenant_id = ? AND property_id = ? AND id = ?
                  AND status = 'claimed' AND claimed_by_device_id = ?
                RETURNING $JOB_COLUMNS
                """.trimIndent(),
                RealtimeEventTypes.PRINT_JOB_PRINTED,
                actor.tenantId, propertyId, jobId, deviceId,
            )
        }

    fun failed(
        propertyId: UUID,
        jobId: UUID,
        request: PosPrintJobFailureRequest,
    ): PosPrintJobResponse =
        mutate(propertyId, "pos.print.failed", mapOf("jobId" to jobId, "error" to request.error)) { actor, deviceId ->
            ack(
                actor, propertyId, jobId, deviceId,
                """
                UPDATE pos_print_jobs
                SET status = 'failed', failed_at = now(), last_error = ?
                WHERE tenant_id = ? AND property_id = ? AND id = ?
                  AND status = 'claimed' AND claimed_by_device_id = ?
                RETURNING $JOB_COLUMNS
                """.trimIndent(),
                RealtimeEventTypes.PRINT_JOB_FAILED,
                request.error.trim(), actor.tenantId, propertyId, jobId, deviceId,
            )
        }

    fun reclaim(
        propertyId: UUID,
        jobId: UUID,
        request: PosPrintJobReclaimRequest,
    ): PosPrintJobResponse =
        mutate(propertyId, "pos.print.reclaim", mapOf("jobId" to jobId, "reason" to request.reason)) { actor, _ ->
            val reason = request.reason.trim()
            if (reason.length < 3) {
                throw IllegalArgumentException("Reclaim reason is required")
            }
            val reclaimed = jdbc.query(
                """
                UPDATE pos_print_jobs
                SET status = 'pending',
                    claimed_by_device_id = NULL,
                    claimed_at = NULL,
                    reclaim_reason = ?
                WHERE tenant_id = ? AND property_id = ? AND id = ?
                  AND status = 'claimed'
                RETURNING $JOB_COLUMNS
                """.trimIndent(),
                ::mapJob,
                reason, actor.tenantId, propertyId, jobId,
            ).singleOrNull() ?: throw PosConflictException(
                "Only a claimed print job can be reclaimed",
            )
            publish(
                actor.tenantId, propertyId, reclaimed.outletId, jobId,
                RealtimeEventTypes.PRINT_JOB_RECLAIMED,
            )
            reclaimed
        }

    fun reprint(propertyId: UUID, jobId: UUID): PosPrintJobResponse =
        mutate(propertyId, "pos.print.reprint", jobId) { actor, _ ->
            val original = requireJob(actor.tenantId, propertyId, jobId)
            if (original.status != "printed" && original.status != "failed") {
                throw PosConflictException("Only a printed or failed job can be reprinted")
            }
            val reprintId = UUID.randomUUID()
            val created = jdbc.query(
                """
                INSERT INTO pos_print_jobs (
                    id, tenant_id, property_id, outlet_id, printer_route_id,
                    job_type, source_type, source_id, source_version,
                    is_reprint, reprinted_from_job_id, status, document
                )
                SELECT ?, tenant_id, property_id, outlet_id, printer_route_id,
                       job_type, source_type, source_id, source_version,
                       true, id, 'pending', document
                FROM pos_print_jobs
                WHERE tenant_id = ? AND property_id = ? AND id = ?
                RETURNING $JOB_COLUMNS
                """.trimIndent(),
                ::mapJob,
                reprintId, actor.tenantId, propertyId, jobId,
            ).single()
            publish(
                actor.tenantId, propertyId, created.outletId, reprintId,
                RealtimeEventTypes.PRINT_JOB_CREATED,
            )
            created
        }

    private fun mutate(
        propertyId: UUID,
        operation: String,
        payload: Any,
        block: (TenantActor, UUID) -> PosPrintJobResponse,
    ): PosPrintJobResponse = commands.mutate(
        propertyId, operation, payload,
        JOBS, PosPrintJobResponse::class.java, PosPrintJobResponse::id,
        { it.copy(replayed = true) },
    ) { actor, _ ->
        block(actor, requireCallingDevice(actor))
    }

    private fun ack(
        actor: TenantActor,
        propertyId: UUID,
        jobId: UUID,
        deviceId: UUID,
        sql: String,
        eventType: String,
        vararg args: Any,
    ): PosPrintJobResponse {
        val updated = jdbc.query(sql, ::mapJob, *args).singleOrNull()
            ?: throw PosConflictException("Print job is not claimed by this till")
        publish(actor.tenantId, propertyId, updated.outletId, jobId, eventType)
        return updated
    }

    private fun claimConflict(tenantId: UUID, propertyId: UUID, jobId: UUID): RuntimeException {
        val existing = jdbc.query(
            "SELECT status FROM pos_print_jobs WHERE tenant_id = ? AND property_id = ? AND id = ?",
            { rs, _ -> rs.getString("status") },
            tenantId, propertyId, jobId,
        ).singleOrNull()
        return if (existing == null) {
            PosNotFoundException("Print job was not found")
        } else {
            PosConflictException("Print job is already $existing")
        }
    }

    private fun requireJob(tenantId: UUID, propertyId: UUID, jobId: UUID) =
        jdbc.query(
            "SELECT $JOB_COLUMNS FROM pos_print_jobs WHERE tenant_id = ? AND property_id = ? AND id = ?",
            ::mapJob,
            tenantId, propertyId, jobId,
        ).singleOrNull() ?: throw PosNotFoundException("Print job was not found")

    private fun requireCallingDevice(actor: TenantActor): UUID {
        val sessionId = requestContextHolder.current().boundSessionId
            ?: throw PosConflictException("Only a paired till can claim or ack a print job")
        return jdbc.query(
            """
            SELECT device_id FROM operational_sessions
            WHERE tenant_id = ? AND id = ? AND revoked_at IS NULL
            """.trimIndent(),
            { rs, _ -> rs.getObject("device_id", UUID::class.java) },
            actor.tenantId,
            sessionId,
        ).singleOrNull() ?: throw PosConflictException(
            "Only a paired till can claim or ack a print job",
        )
    }

    private fun activeRoutes(
        tenantId: UUID,
        propertyId: UUID,
        outletId: UUID,
        category: String,
    ): List<UUID> = jdbc.query(
        """
        SELECT id FROM pos_printer_routes
        WHERE tenant_id = ? AND property_id = ? AND outlet_id = ?
          AND printer_category = ? AND is_active = true
        ORDER BY printer_name, id
        """.trimIndent(),
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        tenantId, propertyId, outletId, category,
    )

    private fun parseStatuses(status: String?): List<String> {
        val requested = status?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
            .orEmpty()
        if (requested.isEmpty()) return listOf("pending", "claimed")
        val allowed = setOf("pending", "claimed", "printed", "failed", "cancelled")
        val unknown = requested.firstOrNull { it !in allowed }
        if (unknown != null) {
            throw IllegalArgumentException("Unknown print-job status")
        }
        return requested
    }

    private fun publish(
        tenantId: UUID,
        propertyId: UUID,
        outletId: UUID,
        jobId: UUID,
        eventType: String,
    ) {
        realtime.ifAvailable {
            it.broadcastRealtimeEvent(
                RealtimeEventRequest(
                    tenantId = tenantId,
                    propertyId = propertyId,
                    outletId = outletId,
                    eventType = eventType,
                    aggregateType = RealtimeEventTypes.AGGREGATE_PRINT_JOB,
                    aggregateId = jobId,
                    aggregateVersion = 0,
                    payload = mapOf("jobId" to jobId),
                ),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapJob(rs: ResultSet, ignored: Int) = PosPrintJobResponse(
        id = rs.getObject("id", UUID::class.java),
        propertyId = rs.getObject("property_id", UUID::class.java),
        outletId = rs.getObject("outlet_id", UUID::class.java),
        printerRouteId = rs.getObject("printer_route_id", UUID::class.java),
        jobType = rs.getString("job_type"),
        sourceType = rs.getString("source_type"),
        sourceId = rs.getObject("source_id", UUID::class.java),
        sourceVersion = rs.getLong("source_version"),
        reprint = rs.getBoolean("is_reprint"),
        reprintedFromJobId = rs.getObject("reprinted_from_job_id", UUID::class.java),
        status = rs.getString("status"),
        document = mapper.readValue(rs.getString("document"), Map::class.java) as Map<String, Any?>,
        claimedByDeviceId = rs.getObject("claimed_by_device_id", UUID::class.java),
        claimedAt = rs.getTimestamp("claimed_at")?.toInstant(),
        printedAt = rs.getTimestamp("printed_at")?.toInstant(),
        failedAt = rs.getTimestamp("failed_at")?.toInstant(),
        attempts = rs.getInt("attempts"),
        lastError = rs.getString("last_error"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )

    private companion object {
        const val JOBS = "pos_print_jobs"
        const val JOB_COLUMNS = """
            id, property_id, outlet_id, printer_route_id, job_type, source_type,
            source_id, source_version, is_reprint, reprinted_from_job_id, status,
            document::text, claimed_by_device_id, claimed_at, printed_at, failed_at,
            attempts, last_error, created_at
        """
    }
}
