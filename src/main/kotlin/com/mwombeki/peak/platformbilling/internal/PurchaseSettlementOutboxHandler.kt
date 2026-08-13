package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import java.sql.Timestamp
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

/**
 * Turns a paid purchase into what the customer actually bought.
 *
 * Runs in the worker rather than in the webhook, so a slow grant cannot look to the
 * provider like a failed callback and be retried. Idempotent by the grants it would write:
 * a purchase that already has grants is a replay, not a second sale.
 */
@Component
class PurchaseSettlementOutboxHandler(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val entitlementReconciler: EntitlementReconciler,
    private val objectMapper: ObjectMapper,
) : OutboxEventHandler {

    override val destination = OutboxDestination.PLATFORM_BILLING

    override fun supports(event: ClaimedOutboxEvent): Boolean {
        return event.destination == destination && event.eventType == PURCHASE_PAID
    }

    override suspend fun handle(event: ClaimedOutboxEvent) {
        withContext(Dispatchers.IO) { handleBlocking(event) }
    }

    private fun handleBlocking(event: ClaimedOutboxEvent) {
        val tenantId = requireNotNull(event.tenantId) {
            "Platform billing settlement events must be tenant scoped"
        }
        val purchaseId = requireNotNull(event.aggregateId) {
            "Platform billing settlement event aggregate id is required"
        }

        transactionTemplate.execute {
            databaseSessionContext.bind(
                RequestIdentity.Public(
                    tenantId = tenantId,
                    correlationId = event.correlationId.toString(),
                ),
            )
            settle(tenantId, purchaseId)
        }

        // Converge immediately rather than waiting for the reconcile loop, so capability
        // arrives seconds after payment instead of up to a minute later. The loop still
        // runs; this is the fast path, not the only path.
        entitlementReconciler.reconcileTenant(tenantId, event.correlationId.toString())
    }

    private fun settle(tenantId: UUID, purchaseId: UUID) {
        val purchase = jdbcTemplate.query(
            """
            SELECT status, period_starts_at, period_ends_at
            FROM peak_purchases
            WHERE id = ? AND tenant_id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                SettlingPurchase(
                    status = rs.getString("status"),
                    periodStartsAt = rs.getTimestamp("period_starts_at"),
                    periodEndsAt = rs.getTimestamp("period_ends_at"),
                )
            },
            purchaseId,
            tenantId,
        ).firstOrNull() ?: return

        // Only a paid purchase grants. A replayed event for one that was later refunded or
        // cancelled must not resurrect it.
        if (purchase.status != "paid") {
            return
        }

        val alreadyGranted = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM peak_product_grants WHERE source_purchase_id = ?",
            Int::class.java,
            purchaseId,
        ) ?: 0
        if (alreadyGranted > 0) {
            return
        }

        val lines = jdbcTemplate.query(
            """
            SELECT product_code, covered_property_ids::text AS covered_property_ids,
                   entitlement_snapshot::text AS entitlement_snapshot
            FROM peak_purchase_lines
            WHERE purchase_id = ?
            """.trimIndent(),
            { rs, _ ->
                SettlingLine(
                    productCode = rs.getString("product_code"),
                    coveredPropertyIds = objectMapper
                        .readValue(rs.getString("covered_property_ids"), List::class.java)
                        .filterIsInstance<String>()
                        .map(UUID::fromString),
                    entitlementSnapshot = rs.getString("entitlement_snapshot"),
                )
            },
            purchaseId,
        )

        lines.forEach { line ->
            // The snapshot, not the current catalog. What was sold is what is granted, even
            // if the product has since come to mean something else.
            val propertyScopes: List<UUID?> = line.coveredPropertyIds.ifEmpty { listOf(null) }
            propertyScopes.forEach { propertyId ->
                jdbcTemplate.update(
                    """
                    INSERT INTO peak_product_grants (
                        tenant_id, property_id, product_code, source, source_purchase_id,
                        status, starts_at, ends_at, granted_entitlements
                    ) VALUES (?, ?, ?, 'purchase', ?, 'active', ?, ?, ?::jsonb)
                    """.trimIndent(),
                    tenantId,
                    propertyId,
                    line.productCode,
                    purchaseId,
                    purchase.periodStartsAt,
                    purchase.periodEndsAt,
                    line.entitlementSnapshot,
                )
            }
        }

        recordLifecycleEvent(tenantId, purchaseId, purchase.periodEndsAt)
    }

    private fun recordLifecycleEvent(
        tenantId: UUID,
        purchaseId: UUID,
        periodEndsAt: Timestamp,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO peak_billing_lifecycle_events (
                tenant_id, purchase_id, from_state, to_state, reason, actor
            ) VALUES (?, ?, 'awaiting_payment', 'active', ?, 'system')
            """.trimIndent(),
            tenantId,
            purchaseId,
            "Purchase settled; paid through ${periodEndsAt.toInstant()}",
        )
    }

    private data class SettlingPurchase(
        val status: String,
        val periodStartsAt: Timestamp,
        val periodEndsAt: Timestamp,
    )

    private data class SettlingLine(
        val productCode: String,
        val coveredPropertyIds: List<UUID>,
        val entitlementSnapshot: String,
    )

    private companion object {
        const val PURCHASE_PAID = "platform.purchase.paid"
    }
}
