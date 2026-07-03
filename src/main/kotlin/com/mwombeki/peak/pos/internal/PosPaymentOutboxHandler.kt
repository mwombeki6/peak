package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import io.micrometer.core.instrument.MeterRegistry
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class PosPaymentOutboxHandler(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val requestContextHolder: RequestContextHolder,
    private val transactionTemplate: TransactionTemplate,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val meterRegistry: MeterRegistry,
) : OutboxEventHandler {
    override val destination = OutboxDestination.POS

    override fun supports(event: ClaimedOutboxEvent): Boolean {
        return event.destination == destination && event.eventType in SUPPORTED_EVENTS
    }

    override suspend fun handle(event: ClaimedOutboxEvent) {
        val tenantId = requireNotNull(event.tenantId) {
            "POS payment events must be tenant scoped"
        }
        val propertyId = requireNotNull(event.propertyId) {
            "POS payment events must be property scoped"
        }
        val transactionId = requireNotNull(event.aggregateId) {
            "POS payment transaction id is required"
        }
        val originalContext = requestContextHolder.current()
        try {
            transactionTemplate.executeWithoutResult {
                val identity = RequestIdentity.Public(
                    tenantId = tenantId,
                    propertyId = propertyId,
                    correlationId = event.correlationId.toString(),
                )
                requestContextHolder.set(
                    originalContext.copy(
                        identity = identity,
                        correlationId = event.correlationId.toString(),
                    ),
                )
                databaseSessionContext.bind(identity)
                val settlement = requireSettlement(
                    tenantId,
                    propertyId,
                    transactionId,
                )
                val changed = when (event.eventType) {
                    PAYMENT_POSTED -> confirm(settlement)
                    PAYMENT_FAILED -> fail(settlement)
                    else -> false
                }
                if (changed) {
                    val status = if (event.eventType == PAYMENT_POSTED) {
                        "posted"
                    } else {
                        "failed"
                    }
                    val payload = mapOf(
                        "orderId" to settlement.orderId,
                        "paymentTransactionId" to settlement.transactionId,
                        "settlementStatus" to status,
                        "amount" to settlement.paymentAmount,
                    )
                    auditPort.recordTenantEvent(
                        TenantAuditEvent(
                            tenantId = tenantId,
                            action = "pos.order.payment_$status",
                            resource = AuditResource(POS_ORDERS, settlement.orderId),
                            after = payload,
                        ),
                    )
                    outboxPort.enqueue(
                        OutboxEventCommand(
                            aggregateType = POS_ORDERS,
                            aggregateId = settlement.orderId,
                            tenantId = tenantId,
                            propertyId = propertyId,
                            eventType = "pos.order.payment_$status",
                            destination = OutboxDestination.PLATFORM,
                            payload = payload,
                            priority = 2,
                        ),
                    )
                    meterRegistry.counter(
                        "peak.pos.payment.settlement",
                        "result",
                        status,
                    ).increment()
                }
            }
        } finally {
            requestContextHolder.set(originalContext)
        }
    }

    private fun confirm(settlement: PosPaymentSettlement): Boolean {
        if (
            settlement.orderStatus == "closed" &&
            settlement.settlementStatus == "confirmed"
        ) {
            return false
        }
        require(settlement.paymentStatus == "posted") {
            "POS payment transaction is not posted"
        }
        require(settlement.orderStatus == "open" && settlement.settlementStatus == "pending") {
            "POS order is not awaiting mobile-money confirmation"
        }
        require(settlement.paymentAmount.money() == settlement.orderTotal.money()) {
            "POS payment amount does not match order total"
        }
        val updated = jdbcTemplate.update(
            """
            UPDATE pos_orders
            SET status = 'closed',
                settlement_status = 'confirmed',
                settled_at = now(),
                updated_at = now()
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
              AND payment_transaction_id = ?
              AND status = 'open'
              AND settlement_status = 'pending'
            """.trimIndent(),
            settlement.tenantId,
            settlement.propertyId,
            settlement.orderId,
            settlement.transactionId,
        )
        check(updated == 1) {
            "POS order changed concurrently during payment confirmation"
        }
        return true
    }

    private fun fail(settlement: PosPaymentSettlement): Boolean {
        if (settlement.settlementStatus == "failed") {
            return false
        }
        require(settlement.paymentStatus == "failed") {
            "POS payment transaction is not failed"
        }
        val updated = jdbcTemplate.update(
            """
            UPDATE pos_orders
            SET settlement_status = 'failed',
                updated_at = now()
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
              AND payment_transaction_id = ?
              AND status = 'open'
              AND settlement_status = 'pending'
            """.trimIndent(),
            settlement.tenantId,
            settlement.propertyId,
            settlement.orderId,
            settlement.transactionId,
        )
        check(updated == 1) {
            "POS order changed concurrently during payment failure handling"
        }
        return true
    }

    private fun requireSettlement(
        tenantId: UUID,
        propertyId: UUID,
        transactionId: UUID,
    ): PosPaymentSettlement {
        return jdbcTemplate.query(
            """
            SELECT po.id AS order_id, po.status AS order_status,
                   po.settlement_status, po.total_amount,
                   pt.id AS transaction_id, pt.status AS payment_status,
                   pt.amount
            FROM payment_transactions pt
            JOIN pos_orders po
              ON po.tenant_id = pt.tenant_id
             AND po.id = pt.pos_order_id
             AND po.payment_transaction_id = pt.id
            WHERE pt.tenant_id = ?
              AND pt.property_id = ?
              AND pt.id = ?
              AND po.property_id = ?
              AND po.deleted_at IS NULL
            FOR UPDATE OF pt, po
            """.trimIndent(),
            { rs, _ ->
                PosPaymentSettlement(
                    tenantId = tenantId,
                    propertyId = propertyId,
                    orderId = rs.getObject("order_id", UUID::class.java),
                    orderStatus = rs.getString("order_status"),
                    settlementStatus = rs.getString("settlement_status"),
                    orderTotal = rs.getBigDecimal("total_amount"),
                    transactionId = rs.getObject("transaction_id", UUID::class.java),
                    paymentStatus = rs.getString("payment_status"),
                    paymentAmount = rs.getBigDecimal("amount"),
                )
            },
            tenantId,
            propertyId,
            transactionId,
            propertyId,
        ).singleOrNull() ?: error("POS payment settlement target was not found")
    }

    private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

    private data class PosPaymentSettlement(
        val tenantId: UUID,
        val propertyId: UUID,
        val orderId: UUID,
        val orderStatus: String,
        val settlementStatus: String,
        val orderTotal: BigDecimal,
        val transactionId: UUID,
        val paymentStatus: String,
        val paymentAmount: BigDecimal,
    )

    private companion object {
        const val POS_ORDERS = "pos_orders"
        const val PAYMENT_POSTED = "payment.transaction.posted"
        const val PAYMENT_FAILED = "payment.transaction.failed"
        val SUPPORTED_EVENTS = setOf(PAYMENT_POSTED, PAYMENT_FAILED)
    }
}
