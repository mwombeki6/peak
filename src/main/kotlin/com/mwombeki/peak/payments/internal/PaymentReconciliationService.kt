package com.mwombeki.peak.payments.internal

import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class PaymentReconciliationService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
) {
    private fun resolveTenant(): UUID {
        val context = requestContextHolder.current()
        return when (val identity = context.identity) {
            is RequestIdentity.Tenant -> identity.tenantId
            else -> throw IllegalStateException("Security Violation: Action requires an active Tenant identity.")
        }
    }

    /**
     *  Reconcile a transaction by matching it against a provider statement.
     */
    @Transactional
    fun reconcileTransaction(transactionId: UUID, providerReference: String) {
        val tenantId = resolveTenant()

        // 1. Verify transaction exists and is POSTED
        val tx = jdbcTemplate.queryForMap(
            "SELECT status FROM payment_transactions WHERE id = ? AND tenant_id = ?",
            transactionId, tenantId
        )

        if (tx["status"] != "POSTED") {
            throw IllegalStateException("Only POSTED transactions can be reconciled.")
        }

        // 2. Update status to RECONCILED
        jdbcTemplate.update(
            "UPDATE payment_transactions SET status = 'RECONCILED' WHERE id = ?",
            transactionId
        )
        
        println(" [Reconciliation] Transaction $transactionId marked as RECONCILED with provider ref $providerReference")
    }
}
