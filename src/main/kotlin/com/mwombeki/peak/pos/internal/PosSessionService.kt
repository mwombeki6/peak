package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.pos.api.ApprovePosVarianceRequest
import com.mwombeki.peak.pos.api.ClosePosSessionRequest
import com.mwombeki.peak.pos.api.OpenPosSessionRequest
import com.mwombeki.peak.pos.api.PosConflictException
import com.mwombeki.peak.pos.api.PosNotFoundException
import com.mwombeki.peak.pos.api.PosSessionResponse
import com.mwombeki.peak.pos.api.PosSessionSummaryResponse
import com.mwombeki.peak.realtime.api.RealtimeEventRequest
import com.mwombeki.peak.realtime.api.RealtimeEventTypes
import com.mwombeki.peak.realtime.api.RealtimePort
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.util.UUID
import org.springframework.beans.factory.ObjectProvider
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class PosSessionService(
    private val jdbcTemplate: JdbcTemplate,
    private val commandExecutor: PosCommandExecutor,
    private val realtime: ObjectProvider<RealtimePort>,
    private val printJobs: PosPrintJobService,
) {
    fun openSession(
        propertyId: UUID,
        request: OpenPosSessionRequest,
    ): PosSessionResponse {
        return commandExecutor.mutate(
            propertyId = propertyId,
            operationType = "pos.session.open",
            requestPayload = request,
            resourceType = POS_SESSIONS,
            replayType = PosSessionResponse::class.java,
            resourceId = PosSessionResponse::id,
            markReplayed = { it.copy(replayed = true) },
        ) { actor, idempotencyKeyId ->
            val openingFloat = request.openingFloat.nonNegativeMoney("openingFloat")
            requireActiveOutlet(actor.tenantId, propertyId, request.outletId)
            val sessionId = UUID.randomUUID()
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO pos_sessions (
                        id, tenant_id, outlet_id, cashier_id, status,
                        opening_float, expected_cash, notes
                    )
                    VALUES (?, ?, ?, ?, 'open', ?, ?, ?)
                    """.trimIndent(),
                    sessionId,
                    actor.tenantId,
                    request.outletId,
                    actor.tenantUserId,
                    openingFloat,
                    openingFloat,
                    request.notes.normalizedOptional(),
                )
            } catch (ex: DuplicateKeyException) {
                throw PosConflictException(
                    "Cashier already has an open or pending POS session for this outlet",
                )
            }
            jdbcTemplate.update(
                """
                INSERT INTO cash_float_movements (
                    tenant_id, session_id, movement_type, amount, created_by, reason
                )
                VALUES (?, ?, 'opening_float', ?, ?, ?)
                """.trimIndent(),
                actor.tenantId,
                sessionId,
                openingFloat,
                actor.tenantUserId,
                request.notes.normalizedOptional(),
            )
            requireSession(actor.tenantId, propertyId, sessionId, lock = false)
                .also {
                    commandExecutor.recordSideEffects(
                        actor = actor,
                        propertyId = propertyId,
                        action = "pos.session.opened",
                        aggregateType = POS_SESSIONS,
                        aggregateId = sessionId,
                        payload = mapOf(
                            "sessionId" to sessionId,
                            "outletId" to request.outletId,
                            "openingFloat" to openingFloat,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
                .also {
                    realtime.ifAvailable {
                        it.broadcastRealtimeEvent(
                            RealtimeEventRequest(
                                tenantId = actor.tenantId,
                                propertyId = propertyId,
                                outletId = request.outletId,
                                eventType = RealtimeEventTypes.SESSION_OPENED,
                                aggregateType = RealtimeEventTypes.AGGREGATE_POS_SESSION,
                                aggregateId = sessionId,
                                payload = mapOf(
                                    "sessionId" to sessionId,
                                    "outletId" to request.outletId,
                                    "openingFloat" to openingFloat,
                                ),
                            ),
                        )
                    }
                }
        }
    }

    fun closeSession(
        propertyId: UUID,
        sessionId: UUID,
        request: ClosePosSessionRequest,
    ): PosSessionResponse {
        return commandExecutor.mutate(
            propertyId = propertyId,
            operationType = "pos.session.close",
            requestPayload = mapOf("sessionId" to sessionId, "request" to request),
            resourceType = POS_SESSIONS,
            replayType = PosSessionResponse::class.java,
            resourceId = PosSessionResponse::id,
            markReplayed = { it.copy(replayed = true) },
        ) { actor, idempotencyKeyId ->
            val session = requireSession(actor.tenantId, propertyId, sessionId, lock = true)
            require(session.status == "open") {
                "Only an open POS session can be closed"
            }
            require(session.cashierId == actor.tenantUserId) {
                "Cashier can close only their own POS session"
            }
            val unsettledOrders = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM pos_orders
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND session_id = ?
                  AND status = 'open'
                  AND deleted_at IS NULL
                """.trimIndent(),
                Int::class.java,
                actor.tenantId,
                propertyId,
                sessionId,
            ) ?: 0
            require(unsettledOrders == 0) {
                "POS session cannot close while orders remain open or unsettled"
            }
            val actualCash = request.actualCash.nonNegativeMoney("actualCash")
            val variance = actualCash.subtract(session.expectedCash).money()
            val nextStatus = if (variance.compareTo(BigDecimal.ZERO) == 0) {
                "closed"
            } else {
                "pending_variance_approval"
            }
            val updated = jdbcTemplate.update(
                """
                UPDATE pos_sessions
                SET status = ?,
                    closing_cash = ?,
                    variance = ?,
                    closed_by = ?,
                    closed_at = CASE WHEN ? = 'closed' THEN now() ELSE NULL END,
                    notes = COALESCE(?, notes),
                    updated_at = now()
                WHERE tenant_id = ?
                  AND id = ?
                  AND status = 'open'
                """.trimIndent(),
                nextStatus,
                actualCash,
                variance,
                actor.tenantUserId,
                nextStatus,
                request.notes.normalizedOptional(),
                actor.tenantId,
                sessionId,
            )
            check(updated == 1) {
                "POS session state changed concurrently"
            }
            jdbcTemplate.update(
                """
                INSERT INTO cash_float_movements (
                    tenant_id, session_id, movement_type, amount,
                    declared_amount, system_amount, reason, created_by
                )
                VALUES (?, ?, 'end_count', ?, ?, ?, ?, ?)
                """.trimIndent(),
                actor.tenantId,
                sessionId,
                actualCash,
                actualCash,
                session.expectedCash,
                request.notes.normalizedOptional(),
                actor.tenantUserId,
            )
            requireSession(actor.tenantId, propertyId, sessionId, lock = false)
                .also {
                    commandExecutor.recordSideEffects(
                        actor = actor,
                        propertyId = propertyId,
                        action = "pos.session.close_requested",
                        aggregateType = POS_SESSIONS,
                        aggregateId = sessionId,
                        payload = mapOf(
                            "sessionId" to sessionId,
                            "status" to nextStatus,
                            "expectedCash" to session.expectedCash,
                            "actualCash" to actualCash,
                            "variance" to variance,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
                .also {
                    realtime.ifAvailable {
                        val eventType = if (nextStatus == "closed") {
                            RealtimeEventTypes.SESSION_CLOSED
                        } else {
                            RealtimeEventTypes.SESSION_CLOSING
                        }
                        it.broadcastRealtimeEvent(
                            RealtimeEventRequest(
                                tenantId = actor.tenantId,
                                propertyId = propertyId,
                                outletId = session.outletId,
                                eventType = eventType,
                                aggregateType = RealtimeEventTypes.AGGREGATE_POS_SESSION,
                                aggregateId = sessionId,
                                payload = mapOf(
                                    "sessionId" to sessionId,
                                    "status" to nextStatus,
                                    "expectedCash" to session.expectedCash,
                                    "actualCash" to actualCash,
                                    "variance" to variance,
                                ),
                            ),
                        )
                    }
                }
                .also { closed ->
                    printJobs.enqueueShiftReport(actor.tenantId, propertyId, closed)
                }
        }
    }

    fun approveVariance(
        propertyId: UUID,
        sessionId: UUID,
        request: ApprovePosVarianceRequest,
    ): PosSessionResponse {
        return commandExecutor.mutate(
            propertyId = propertyId,
            operationType = "pos.session.variance.approve",
            requestPayload = mapOf("sessionId" to sessionId, "request" to request),
            resourceType = POS_SESSIONS,
            replayType = PosSessionResponse::class.java,
            resourceId = PosSessionResponse::id,
            markReplayed = { it.copy(replayed = true) },
        ) { actor, idempotencyKeyId ->
            val session = requireSession(actor.tenantId, propertyId, sessionId, lock = true)
            require(session.status == "pending_variance_approval") {
                "POS session is not awaiting variance approval"
            }
            require(session.closedBy != actor.tenantUserId) {
                "The user who requested session close cannot approve its variance"
            }
            val reason = request.reason.normalizedRequired("reason")
            require(reason.length in 10..500) {
                "Variance approval reason must be between 10 and 500 characters"
            }
            val updated = jdbcTemplate.update(
                """
                UPDATE pos_sessions
                SET status = 'closed',
                    variance_approved_by = ?,
                    variance_approved_at = now(),
                    variance_approval_reason = ?,
                    closed_at = now(),
                    updated_at = now()
                WHERE tenant_id = ?
                  AND id = ?
                  AND status = 'pending_variance_approval'
                  AND closed_by <> ?
                """.trimIndent(),
                actor.tenantUserId,
                reason,
                actor.tenantId,
                sessionId,
                actor.tenantUserId,
            )
            if (updated != 1) {
                throw PosConflictException("POS variance approval conflicted with another update")
            }
            requireSession(actor.tenantId, propertyId, sessionId, lock = false)
                .also {
                    commandExecutor.recordSideEffects(
                        actor = actor,
                        propertyId = propertyId,
                        action = "pos.session.variance.approved",
                        aggregateType = POS_SESSIONS,
                        aggregateId = sessionId,
                        payload = mapOf(
                            "sessionId" to sessionId,
                            "variance" to session.variance,
                            "approvedBy" to actor.tenantUserId,
                            "reason" to reason,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
                .also {
                    realtime.ifAvailable {
                        it.broadcastRealtimeEvent(
                            RealtimeEventRequest(
                                tenantId = actor.tenantId,
                                propertyId = propertyId,
                                outletId = session.outletId,
                                eventType = RealtimeEventTypes.SESSION_CLOSED,
                                aggregateType = RealtimeEventTypes.AGGREGATE_POS_SESSION,
                                aggregateId = sessionId,
                                payload = mapOf(
                                    "sessionId" to sessionId,
                                    "variance" to session.variance,
                                    "approvedBy" to actor.tenantUserId,
                                ),
                            ),
                        )
                    }
                }
        }
    }

    fun getSessionSummary(
        propertyId: UUID,
        sessionId: UUID,
    ): PosSessionSummaryResponse {
        return commandExecutor.read(propertyId) { actor ->
            val session = requireSession(actor.tenantId, propertyId, sessionId, lock = false)
            jdbcTemplate.query(
                """
                SELECT COUNT(*) AS order_count,
                       COUNT(*) FILTER (WHERE status = 'closed') AS closed_order_count,
                       COALESCE(SUM(total_amount) FILTER (WHERE status = 'closed'), 0) AS gross_sales
                FROM pos_orders
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND session_id = ?
                  AND deleted_at IS NULL
                """.trimIndent(),
                { rs, _ ->
                    PosSessionSummaryResponse(
                        session = session,
                        orderCount = rs.getLong("order_count"),
                        closedOrderCount = rs.getLong("closed_order_count"),
                        grossSales = rs.getBigDecimal("gross_sales").money(),
                    )
                },
                actor.tenantId,
                propertyId,
                sessionId,
            ).single()
        }
    }

    private fun requireActiveOutlet(tenantId: UUID, propertyId: UUID, outletId: UUID) {
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM outlets
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND id = ?
                  AND is_active = true
                  AND deleted_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            propertyId,
            outletId,
        ) == true
        if (!exists) {
            throw PosNotFoundException("Active POS outlet was not found")
        }
    }

    private fun requireSession(
        tenantId: UUID,
        propertyId: UUID,
        sessionId: UUID,
        lock: Boolean,
    ): PosSessionResponse {
        val lockClause = if (lock) "FOR UPDATE OF ps" else ""
        return jdbcTemplate.query(
            """
            SELECT ps.id, o.property_id, ps.outlet_id, ps.cashier_id, ps.status,
                   ps.opening_float, ps.expected_cash, ps.closing_cash, ps.variance,
                   ps.opened_at, ps.closed_at, ps.closed_by, ps.variance_approved_by
            FROM pos_sessions ps
            JOIN outlets o
              ON o.tenant_id = ps.tenant_id
             AND o.id = ps.outlet_id
            WHERE ps.tenant_id = ?
              AND o.property_id = ?
              AND ps.id = ?
            $lockClause
            """.trimIndent(),
            ::mapSession,
            tenantId,
            propertyId,
            sessionId,
        ).singleOrNull() ?: throw PosNotFoundException("POS session was not found")
    }

    private fun mapSession(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ): PosSessionResponse {
        return PosSessionResponse(
            id = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            outletId = rs.getObject("outlet_id", UUID::class.java),
            cashierId = rs.getObject("cashier_id", UUID::class.java),
            status = rs.getString("status"),
            openingFloat = rs.getBigDecimal("opening_float").money(),
            expectedCash = rs.getBigDecimal("expected_cash").money(),
            closingCash = rs.getBigDecimal("closing_cash")?.money(),
            variance = rs.getBigDecimal("variance")?.money(),
            openedAt = rs.getTimestamp("opened_at").toInstant(),
            closedAt = rs.getTimestamp("closed_at")?.toInstant(),
            closedBy = rs.getObject("closed_by", UUID::class.java),
            varianceApprovedBy = rs.getObject("variance_approved_by", UUID::class.java),
        )
    }

    private fun BigDecimal.nonNegativeMoney(field: String): BigDecimal {
        require(this >= BigDecimal.ZERO) {
            "$field cannot be negative"
        }
        return money()
    }

    private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

    private fun String?.normalizedOptional(): String? {
        return this?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun String.normalizedRequired(field: String): String {
        return trim().also { require(it.isNotEmpty()) { "$field is required" } }
    }

    private companion object {
        const val POS_SESSIONS = "pos_sessions"
    }
}
