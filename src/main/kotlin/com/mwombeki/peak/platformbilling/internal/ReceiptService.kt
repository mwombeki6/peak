package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.platformbilling.api.Receipt
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Issues the receipt for a settled purchase.
 *
 * Exactly one per purchase, enforced by `uq_peak_receipts_purchase` rather than by the
 * caller remembering. Settlement is redelivered by the outbox whenever a worker dies
 * mid-flight, so "issue a receipt" has to mean "issue the receipt if there is not one" or a
 * crash would send the customer a second document for money they paid once.
 *
 * The tenant's name and address are snapshotted. A receipt is a record of a transaction as
 * it stood, and a hotel that renames itself next year has not changed what it was invoiced
 * as.
 *
 * These are commercial receipts for a SaaS subscription, not fiscal receipts. A guest-facing
 * fiscal receipt is the fiscal module's business and answers to TRA rules; conflating the
 * two would put Peak's own revenue into a property's fiscal records.
 */
@Service
class ReceiptService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ReceiptService::class.java)

    /**
     * Issues a receipt if the purchase does not already have one. Runs inside the caller's
     * settlement transaction so a receipt cannot outlive the grant it attests to.
     */
    fun issueFor(tenantId: UUID, purchaseId: UUID): Receipt? {
        val existing = findByPurchase(tenantId, purchaseId)
        if (existing != null) {
            return existing
        }

        val purchase = jdbcTemplate.query(
            """
            SELECT purchase.total_amount, purchase.currency, purchase.term_months,
                   purchase.period_starts_at, purchase.period_ends_at,
                   tenant.name AS tenant_name, tenant.slug AS tenant_slug
            FROM peak_purchases purchase
            JOIN tenants tenant ON tenant.id = purchase.tenant_id
            WHERE purchase.id = ? AND purchase.tenant_id = ? AND purchase.status = 'paid'
            """.trimIndent(),
            { rs, _ ->
                ReceiptSubject(
                    totalAmount = rs.getBigDecimal("total_amount"),
                    currency = rs.getString("currency").trim(),
                    termMonths = rs.getInt("term_months"),
                    periodStartsAt = rs.getTimestamp("period_starts_at").toInstant().toString(),
                    periodEndsAt = rs.getTimestamp("period_ends_at").toInstant().toString(),
                    tenantName = rs.getString("tenant_name"),
                    tenantSlug = rs.getString("tenant_slug"),
                )
            },
            purchaseId,
            tenantId,
        ).firstOrNull() ?: return null

        val receiptNumber = jdbcTemplate.queryForObject(
            "SELECT allocate_peak_receipt_number()",
            String::class.java,
        ) ?: error("Peak receipt number allocation returned nothing")

        return try {
            jdbcTemplate.update(
                """
                INSERT INTO peak_receipts (
                    tenant_id, purchase_id, receipt_number, total_amount, currency,
                    tenant_snapshot
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """.trimIndent(),
                tenantId,
                purchaseId,
                receiptNumber,
                purchase.totalAmount,
                purchase.currency,
                objectMapper.writeValueAsString(
                    mapOf(
                        "tenantName" to purchase.tenantName,
                        "tenantSlug" to purchase.tenantSlug,
                        "termMonths" to purchase.termMonths,
                        "periodStartsAt" to purchase.periodStartsAt,
                        "periodEndsAt" to purchase.periodEndsAt,
                    ),
                ),
            )
            log.info("Issued Peak receipt {} for purchase {}", receiptNumber, purchaseId)
            requireNotNull(findByPurchase(tenantId, purchaseId))
        } catch (ex: DuplicateKeyException) {
            // Another delivery of the same settlement event won the race. The unique index
            // is the guarantee; this is just how the loser finds out.
            findByPurchase(tenantId, purchaseId)
        }
    }

    fun findByPurchase(tenantId: UUID, purchaseId: UUID): Receipt? {
        return jdbcTemplate.query(
            """
            SELECT id, purchase_id, receipt_number, issued_at, total_amount, currency
            FROM peak_receipts
            WHERE purchase_id = ? AND tenant_id = ?
            """.trimIndent(),
            { rs, _ ->
                Receipt(
                    id = rs.getObject("id", UUID::class.java),
                    purchaseId = rs.getObject("purchase_id", UUID::class.java),
                    receiptNumber = rs.getString("receipt_number"),
                    issuedAt = rs.getTimestamp("issued_at").toInstant(),
                    totalAmount = rs.getBigDecimal("total_amount"),
                    currency = rs.getString("currency").trim(),
                )
            },
            purchaseId,
            tenantId,
        ).firstOrNull()
    }

    /**
     * A tenant's own receipts, newest first.
     *
     * These existed and only Peak could read them: the sole route was
     * `/api/platform/billing/receipts` behind `platform.billing.view`. Peak took a hotel's
     * money, allocated a sequential receipt number for it, and filed the receipt where the
     * hotel could not reach it — which for a Tanzanian business is the document their
     * bookkeeping and their own tax filing depend on.
     */
    fun forTenant(tenantId: UUID, limit: Int = 50): List<Receipt> {
        return jdbcTemplate.query(
            """
            SELECT id, purchase_id, receipt_number, issued_at, total_amount, currency
            FROM peak_receipts
            WHERE tenant_id = ?
            ORDER BY issued_at DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                Receipt(
                    id = rs.getObject("id", UUID::class.java),
                    purchaseId = rs.getObject("purchase_id", UUID::class.java),
                    receiptNumber = rs.getString("receipt_number"),
                    issuedAt = rs.getTimestamp("issued_at").toInstant(),
                    totalAmount = rs.getBigDecimal("total_amount"),
                    currency = rs.getString("currency").trim(),
                )
            },
            tenantId,
            limit.coerceIn(1, 200),
        )
    }

    private data class ReceiptSubject(
        val totalAmount: java.math.BigDecimal,
        val currency: String,
        val termMonths: Int,
        val periodStartsAt: String,
        val periodEndsAt: String,
        val tenantName: String,
        val tenantSlug: String,
    )
}
