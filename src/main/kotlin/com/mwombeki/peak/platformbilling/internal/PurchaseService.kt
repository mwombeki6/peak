package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.platformbilling.api.PaymentAttemptResponse
import com.mwombeki.peak.platformbilling.api.PayPurchaseRequest
import com.mwombeki.peak.platformbilling.api.PlatformBillingConflictException
import com.mwombeki.peak.platformbilling.api.PlatformBillingPort
import com.mwombeki.peak.platformbilling.api.ProductSummary
import com.mwombeki.peak.platformbilling.api.PurchaseResponse
import com.mwombeki.peak.platformbilling.api.PurchaseStatus
import com.mwombeki.peak.platformbilling.api.Quote
import com.mwombeki.peak.platformbilling.api.QuoteLine
import com.mwombeki.peak.platformbilling.api.QuoteRequest
import com.mwombeki.peak.platformbilling.api.Receipt
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.shared.context.TenantRequestContext
import java.sql.Timestamp
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

/**
 * Records the immutable order a payment will refer to.
 *
 * A purchase freezes price, currency, period and — through `entitlement_snapshot` — the
 * exact set of entitlements it will grant. If Peak later adds a module to Peak Pro, an
 * order already placed grants what was sold rather than what the product has since come
 * to mean. Without that, a customer's bill and their access could disagree and neither
 * would be wrong.
 */
@Service
class PurchaseService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val tenantRequestContext: TenantRequestContext,
    private val catalogService: ProductCatalogService,
    private val collectionService: PlatformCollectionService,
    private val idempotencyPort: IdempotencyPort,
    private val objectMapper: ObjectMapper,
    private val receiptService: ReceiptService,
) : PlatformBillingPort {

    override fun catalog(): List<ProductSummary> = catalogService.catalog()

    override fun quote(tenantId: UUID, request: QuoteRequest): Quote = catalogService.quote(request)

    override fun createPurchase(tenantId: UUID, request: QuoteRequest): PurchaseResponse {
        return requireNotNull(
            transactionTemplate.execute {
                val actor = tenantRequestContext.bind()
                when (
                    val reservation = idempotencyPort.reserve(
                        IdempotencyCommand(
                            operationType = "platformbilling.purchase.create",
                            requestPayload = request,
                            resourceType = PEAK_PURCHASES,
                        ),
                    )
                ) {
                    is IdempotencyReservation.Started -> {
                        val response = insertPurchase(actor.tenantId, actor.tenantUserId, request)
                        idempotencyPort.markSucceeded(reservation.recordId, 201, response, response.id)
                        response
                    }

                    is IdempotencyReservation.Replay -> {
                        val stored = reservation.responseBody
                        if (stored.isNullOrBlank()) {
                            throw PlatformBillingConflictException(
                                "Purchase replay does not contain a stored response",
                            )
                        }
                        objectMapper.readValue(stored, PurchaseResponse::class.java).copy(replayed = true)
                    }

                    is IdempotencyReservation.InProgress -> throw PlatformBillingConflictException(
                        "This purchase is already being created",
                    )

                    is IdempotencyReservation.Conflict -> throw PlatformBillingConflictException(
                        "That idempotency key was used for a different purchase",
                    )
                }
            },
        )
    }

    override fun pay(
        tenantId: UUID,
        purchaseId: UUID,
        request: PayPurchaseRequest,
    ): PaymentAttemptResponse = collectionService.pay(purchaseId, request)

    override fun purchase(tenantId: UUID, purchaseId: UUID): PurchaseResponse? {
        return transactionTemplate.execute {
            val actor = tenantRequestContext.bind()
            readPurchase(actor.tenantId, purchaseId)
        }
    }

    override fun purchases(tenantId: UUID, limit: Int): List<PurchaseResponse> {
        return requireNotNull(
            transactionTemplate.execute {
                val actor = tenantRequestContext.bind()
                val ids = jdbcTemplate.query(
                    """
                    SELECT id FROM peak_purchases
                    WHERE tenant_id = ?
                    ORDER BY created_at DESC
                    LIMIT ?
                    """.trimIndent(),
                    { rs, _ -> rs.getObject("id", UUID::class.java) },
                    actor.tenantId,
                    limit.coerceIn(1, 200),
                )
                ids.mapNotNull { id -> readPurchase(actor.tenantId, id) }
            },
        )
    }

    /**
     * Bound and read inside a transaction like every other tenant read here, so RLS scopes it
     * to the caller's own tenant rather than the query being trusted to say so.
     */
    override fun receipts(tenantId: UUID, limit: Int): List<Receipt> {
        return requireNotNull(
            transactionTemplate.execute {
                val actor = tenantRequestContext.bind()
                receiptService.forTenant(actor.tenantId, limit)
            },
        )
    }

    private fun insertPurchase(
        tenantId: UUID,
        actorId: UUID,
        request: QuoteRequest,
    ): PurchaseResponse {
        val quote = catalogService.priceSelection(tenantId, request)
        val purchaseId = UUID.randomUUID()

        try {
            jdbcTemplate.update(
                """
                INSERT INTO peak_purchases (
                    id, tenant_id, status, currency, term_months, total_amount,
                    period_starts_at, period_ends_at, quote_expires_at, created_by_user_id
                )
                VALUES (?, ?, 'quoted', ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                purchaseId,
                tenantId,
                quote.currency,
                quote.termMonths,
                quote.totalAmount,
                Timestamp.from(quote.periodStartsAt),
                Timestamp.from(quote.periodEndsAt),
                Timestamp.from(quote.expiresAt),
                actorId,
            )
        } catch (ex: DuplicateKeyException) {
            // The partial unique index permits one open order per tenant. Two carts in
            // flight would mean two PIN prompts and a customer paying for the one they
            // did not choose.
            throw PlatformBillingConflictException(
                "This tenant already has an open purchase. Complete or cancel it first.",
            )
        }

        quote.lines.forEach { line ->
            jdbcTemplate.update(
                """
                INSERT INTO peak_purchase_lines (
                    purchase_id, tenant_id, product_code, term_months, quantity,
                    covered_property_ids, unit_amount, amount, price_source_id,
                    entitlement_snapshot
                )
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb)
                """.trimIndent(),
                purchaseId,
                tenantId,
                line.productCode,
                quote.termMonths,
                line.quantity,
                objectMapper.writeValueAsString(line.coveredPropertyIds.map(UUID::toString)),
                line.unitAmount,
                line.amount,
                currentPriceId(line.productCode, quote.termMonths),
                objectMapper.writeValueAsString(entitlementSnapshot(line.productCode)),
            )
        }

        return PurchaseResponse(
            id = purchaseId,
            status = PurchaseStatus.QUOTED,
            currency = quote.currency,
            termMonths = quote.termMonths,
            totalAmount = quote.totalAmount,
            periodStartsAt = quote.periodStartsAt,
            periodEndsAt = quote.periodEndsAt,
            quoteExpiresAt = quote.expiresAt,
            lines = quote.lines,
        )
    }

    /**
     * The shape `effective_tenant_entitlement` reads: an object keyed by entitlement
     * code. Captured now so the grant a payment creates is built from what was sold.
     */
    private fun entitlementSnapshot(productCode: String): Map<String, Map<String, Any>> {
        val rows = jdbcTemplate.query(
            """
            SELECT entitlement_code, entitlement_value::text AS entitlement_value,
                   is_enabled, auto_activate
            FROM peak_product_entitlements
            WHERE product_code = ?
            """.trimIndent(),
            { rs, _ ->
                rs.getString("entitlement_code") to mapOf(
                    "is_enabled" to rs.getBoolean("is_enabled"),
                    "auto_activate" to rs.getBoolean("auto_activate"),
                    "value" to objectMapper.readValue(
                        rs.getString("entitlement_value"),
                        Map::class.java,
                    ),
                )
            },
            productCode,
        )
        return rows.toMap()
    }

    private fun currentPriceId(productCode: String, termMonths: Int): UUID? {
        return jdbcTemplate.query(
            """
            SELECT id FROM peak_product_prices
            WHERE product_code = ? AND term_months = ? AND currency = 'TZS'
              AND effective_from <= now()
              AND (effective_to IS NULL OR effective_to > now())
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            productCode,
            termMonths,
        ).firstOrNull()
    }

    /**
     * Filters on `tenant_id` even though row-level security already confines the read.
     * Belt and braces: the RLS policy is the guarantee, but a future caller reaching this
     * on an unbound session would otherwise read across tenants silently.
     */
    private fun readPurchase(tenantId: UUID, purchaseId: UUID): PurchaseResponse? {
        val purchase = jdbcTemplate.query(
            """
            SELECT id, status, currency, term_months, total_amount,
                   period_starts_at, period_ends_at, quote_expires_at
            FROM peak_purchases
            WHERE id = ? AND tenant_id = ?
            """.trimIndent(),
            { rs, _ ->
                PurchaseResponse(
                    id = rs.getObject("id", UUID::class.java),
                    status = PurchaseStatus.fromDatabase(rs.getString("status")),
                    currency = rs.getString("currency").trim(),
                    termMonths = rs.getInt("term_months"),
                    totalAmount = rs.getBigDecimal("total_amount"),
                    periodStartsAt = rs.getTimestamp("period_starts_at").toInstant(),
                    periodEndsAt = rs.getTimestamp("period_ends_at").toInstant(),
                    quoteExpiresAt = rs.getTimestamp("quote_expires_at").toInstant(),
                    lines = emptyList(),
                )
            },
            purchaseId,
            tenantId,
        ).firstOrNull() ?: return null

        val lines = jdbcTemplate.query(
            """
            SELECT line.product_code, product.name AS product_name, line.quantity,
                   line.covered_property_ids::text AS covered_property_ids,
                   line.unit_amount, line.amount
            FROM peak_purchase_lines line
            JOIN peak_products product ON product.code = line.product_code
            WHERE line.purchase_id = ?
            ORDER BY product.display_order, line.product_code
            """.trimIndent(),
            { rs, _ ->
                QuoteLine(
                    productCode = rs.getString("product_code"),
                    productName = rs.getString("product_name"),
                    quantity = rs.getInt("quantity"),
                    coveredPropertyIds = objectMapper
                        .readValue(rs.getString("covered_property_ids"), List::class.java)
                        .filterIsInstance<String>()
                        .map(UUID::fromString),
                    unitAmount = rs.getBigDecimal("unit_amount"),
                    amount = rs.getBigDecimal("amount"),
                )
            },
            purchaseId,
        )

        return purchase.copy(lines = lines)
    }

    private companion object {
        const val PEAK_PURCHASES = "peak_purchases"
    }
}
