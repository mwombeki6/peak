package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.pos.api.*
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

@Service
class PosSessionService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
) {
    private fun resolveTenantAndUser(): Pair<UUID, String> {
        val context = requestContextHolder.current()
        return when (val identity = context.identity) {
            is RequestIdentity.Tenant -> Pair(identity.tenantId, identity.tenantUserId.toString())
            else -> throw IllegalStateException("Security Violation: Action requires an active Tenant identity.")
        }
    }

    @Transactional
    fun openSession(request: OpenSessionRequest):UUID{
        val (tenantId, userId) = resolveTenantAndUser()
        val sessionId = UUID.randomUUID()

        if (request.startingFloat < BigDecimal.ZERO) {
            throw IllegalArgumentException("Starting float amount cannot be negative.")
        }

        // Enforce positive starting float
        jdbcTemplate.update(
            """
            INSERT INTO pos_sessions (id, tenant_id, property_id, opened_by, starting_float, status, opened_at)
            VALUES (?, ?, ?, ?, ?, 'OPEN', NOW())
            """.trimIndent(),
            sessionId, tenantId, request.propertyId, userId, request.startingFloat
        )
        return sessionId
    }

    @Transactional
    fun closeSession(sessionId: UUID, request: CloseSessionRequest) {
        val (tenantId, userId) = resolveTenantAndUser()
        val variance = request.actualAmount.subtract(request.expectedAmount)

        // If there's a cash variance, it requires dual-authorization via another user later
        val status = if (variance.compareTo(BigDecimal.ZERO) == 0) "CLOSED" else "PENDING_VARIANCE_APPROVAL"

        val updated = jdbcTemplate.update(
            """
            UPDATE pos_sessions 
            SET status = ?, closed_by = ?, expected_amount = ?, actual_amount = ?, variance_amount = ?, closed_at = NOW()
            WHERE id = ? AND tenant_id = ? AND status = 'OPEN'
            """.trimIndent(),
            status, userId, request.expectedAmount, request.actualAmount, variance, sessionId, tenantId
        )

        if (updated == 0) {
            throw IllegalStateException("Session not found, already closed, or cross-tenant violation.")
        }
    }

    @Transactional
    fun approveSessionVariance(sessionId: UUID, request: ApproveVarianceRequest) {
        val (tenantId, supervisorId) = resolveTenantAndUser()

        // Update status from PENDING_VARIANCE_APPROVAL to CLOSED
        val updated = jdbcTemplate.update(
            """
            UPDATE pos_sessions 
            SET status = 'CLOSED', closed_by = ?
            WHERE id = ? AND tenant_id = ? AND status = 'PENDING_VARIANCE_APPROVAL'
            """.trimIndent(),
            supervisorId, sessionId, tenantId
        )

        if (updated == 0) {
            throw IllegalStateException("Session not found, not pending variance approval, or cross-tenant violation.")
        }
        println(" [POS Governance] Variance for session $sessionId approved by Supervisor: $supervisorId")
    }

    @Transactional
    fun transferToFolio(sessionId: UUID, request: FolioTransferRequest) {
        val (tenantId, userId) = resolveTenantAndUser()

        // 1. Verify the POS session is currently active and open
        val isOpen = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM pos_sessions WHERE id = ? AND tenant_id = ? AND status = 'OPEN'",
            Int::class.java, sessionId, tenantId
        ) == 1

        if (!isOpen) throw IllegalStateException("Cannot transfer charge: POS session is not open.")

        // 2. Implementation logic (Phase 3: manual reference ledger)
        jdbcTemplate.update(
            """
            INSERT INTO pos_orders (id, tenant_id, property_id, session_id, status, total_amount, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'TRANSFERRED_TO_FOLIO', ?, NOW(), NOW())
            """.trimIndent(),
            UUID.randomUUID(), tenantId, UUID.randomUUID(), sessionId, request.amount
        )

        // 3. TODO: Call Engineer A's billing API boundary contract here
        // billingApi.postManualCharge(request.folioId, request.amount, request.description)
        println(" [POS Transfer] Charge of ${request.amount} TZS routed to Folio ${request.folioId} by $userId")
    }

    @Transactional(readOnly = true)
    fun getSessionSummary(sessionId: UUID): Map<String, Any> {
        val (tenantId, _) = resolveTenantAndUser()

        val session = jdbcTemplate.queryForMap(
            "SELECT * FROM pos_sessions WHERE id = ? AND tenant_id = ?",
            sessionId, tenantId
        )

        val ordersSummary = jdbcTemplate.queryForMap(
            """
            SELECT 
                COUNT(id) as total_orders, 
                SUM(total_amount) as total_revenue 
            FROM pos_orders 
            WHERE session_id = ? AND status = 'PAID'
            """.trimIndent(),
            sessionId
        )

        return mapOf(
            "session" to session,
            "summary" to ordersSummary
        )
    }
}