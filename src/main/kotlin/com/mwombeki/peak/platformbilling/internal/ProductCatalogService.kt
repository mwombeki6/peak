package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.platformbilling.api.PlatformBillingNotFoundException
import com.mwombeki.peak.platformbilling.api.PlatformBillingUncollectableException
import com.mwombeki.peak.platformbilling.api.ProductKind
import com.mwombeki.peak.platformbilling.api.ProductPrice
import com.mwombeki.peak.platformbilling.api.ProductSummary
import com.mwombeki.peak.platformbilling.api.Quote
import com.mwombeki.peak.platformbilling.api.QuoteLine
import com.mwombeki.peak.platformbilling.api.QuoteRequest
import com.mwombeki.peak.shared.context.TenantRequestContext
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * Reads the catalog and prices a selection.
 *
 * Quoting is deliberately separate from purchasing. A quote fixes price, currency, term
 * and the entitlements a purchase would grant, so what the owner approves on their phone
 * is what they were shown; collapsing the two would let the catalog move between them.
 */
@Component
class ProductCatalogService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val tenantRequestContext: TenantRequestContext,
    private val properties: PlatformBillingProperties,
    private val clock: Clock,
) {
    fun catalog(): List<ProductSummary> {
        return requireNotNull(
            transactionTemplate.execute {
                tenantRequestContext.bind()
                val entitlements = entitlementsByProduct()
                val prices = pricesByProduct()
                jdbcTemplate.query(
                    """
                    SELECT code, name, description, kind, is_per_property, requires_product_code
                    FROM peak_products
                    WHERE is_sellable = true
                    ORDER BY display_order, code
                    """.trimIndent(),
                ) { rs, _ ->
                    val code = rs.getString("code")
                    ProductSummary(
                        code = code,
                        name = rs.getString("name"),
                        description = rs.getString("description"),
                        kind = ProductKind.fromDatabase(rs.getString("kind")),
                        isPerProperty = rs.getBoolean("is_per_property"),
                        requiresProductCode = rs.getString("requires_product_code"),
                        entitlements = entitlements[code].orEmpty(),
                        prices = prices[code].orEmpty(),
                    )
                }
            },
        )
    }

    fun quote(request: QuoteRequest): Quote {
        return requireNotNull(
            transactionTemplate.execute {
                val actor = tenantRequestContext.bind()
                priceSelection(actor.tenantId, request)
            },
        )
    }

    /**
     * Shared by quoting and purchasing so the two can never disagree. Callers are
     * expected to have bound the tenant context already.
     */
    internal fun priceSelection(tenantId: UUID, request: QuoteRequest): Quote {
        require(request.lines.isNotEmpty()) { "A purchase needs at least one product" }
        require(request.termMonths in ALLOWED_TERMS) {
            "Term must be one of ${ALLOWED_TERMS.joinToString(", ")} months"
        }
        require(request.lines.map { it.productCode }.toSet().size == request.lines.size) {
            "A product may appear only once in a selection"
        }

        val selected = request.lines.map { it.productCode }.toSet()
        val lines = request.lines.map { line -> priceLine(tenantId, line.productCode, line.propertyIds, request.termMonths, selected) }

        val total = lines.fold(BigDecimal.ZERO) { running, line -> running.add(line.amount) }
        val currency = "TZS"

        // Refuse here rather than at initiation. A purchase that cannot be collected is
        // worse than one that was never created: the customer has committed, and the
        // failure arrives as a provider error nobody can act on.
        if (total > properties.maxCollectableAmount) {
            throw PlatformBillingUncollectableException(
                "This selection totals ${total.toPlainString()} $currency, above the " +
                    "${properties.maxCollectableAmount.toPlainString()} $currency mobile money " +
                    "limit for a single payment. Choose a shorter term, buy fewer add-ons at " +
                    "once, or contact us to pay by bank transfer.",
            )
        }

        val now = clock.instant()
        return Quote(
            lines = lines,
            termMonths = request.termMonths,
            currency = currency,
            totalAmount = total,
            periodStartsAt = now,
            periodEndsAt = now.atZone(ZoneOffset.UTC).plusMonths(request.termMonths.toLong()).toInstant(),
            expiresAt = now.plus(properties.quoteValidity),
        )
    }

    private fun priceLine(
        tenantId: UUID,
        productCode: String,
        propertyIds: List<UUID>,
        termMonths: Int,
        selectedProducts: Set<String>,
    ): QuoteLine {
        val product = jdbcTemplate.query(
            """
            SELECT code, name, is_per_property, is_sellable, requires_product_code
            FROM peak_products
            WHERE code = ?
            """.trimIndent(),
            { rs, _ ->
                CatalogProduct(
                    code = rs.getString("code"),
                    name = rs.getString("name"),
                    isPerProperty = rs.getBoolean("is_per_property"),
                    isSellable = rs.getBoolean("is_sellable"),
                    requiresProductCode = rs.getString("requires_product_code"),
                )
            },
            productCode,
        ).firstOrNull() ?: throw PlatformBillingNotFoundException("Unknown product: $productCode")

        require(product.isSellable) {
            "${product.name} is not sold self-service; contact us for a quote"
        }

        product.requiresProductCode?.let { prerequisite ->
            // Checked against the selection rather than against what the tenant already
            // owns, so a single order is internally coherent. Owning the prerequisite
            // already is handled by the caller including it or not needing to.
            require(prerequisite in selectedProducts || tenantAlreadyHolds(tenantId, prerequisite)) {
                "${product.name} requires $prerequisite"
            }
        }

        val coveredProperties = if (product.isPerProperty) {
            require(propertyIds.isNotEmpty()) {
                "${product.name} is priced per property, so at least one property must be chosen"
            }
            val distinct = propertyIds.distinct()
            requireOwnedProperties(tenantId, distinct)
            distinct
        } else {
            emptyList()
        }

        val unitAmount = jdbcTemplate.query(
            """
            SELECT amount
            FROM peak_product_prices
            WHERE product_code = ?
              AND term_months = ?
              AND currency = 'TZS'
              AND effective_from <= now()
              AND (effective_to IS NULL OR effective_to > now())
            """.trimIndent(),
            { rs, _ -> rs.getBigDecimal("amount") },
            productCode,
            termMonths,
        ).firstOrNull()
            ?: throw PlatformBillingNotFoundException(
                "${product.name} is not offered on a $termMonths month term",
            )

        val quantity = if (product.isPerProperty) coveredProperties.size else 1
        return QuoteLine(
            productCode = product.code,
            productName = product.name,
            quantity = quantity,
            coveredPropertyIds = coveredProperties,
            unitAmount = unitAmount,
            amount = unitAmount.multiply(BigDecimal(quantity)),
        )
    }

    private fun tenantAlreadyHolds(tenantId: UUID, productCode: String): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM peak_product_grants
                WHERE tenant_id = ?
                  AND product_code = ?
                  AND status = 'active'
                  AND revoked_at IS NULL
                  AND starts_at <= now()
                  AND (ends_at IS NULL OR ends_at > now())
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            productCode,
        ) == true
    }

    private fun requireOwnedProperties(tenantId: UUID, propertyIds: List<UUID>) {
        val owned = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM properties
            WHERE tenant_id = ?
              AND id = ANY(?)
              AND deleted_at IS NULL
            """.trimIndent(),
            Int::class.java,
            tenantId,
            propertyIds.toTypedArray(),
        ) ?: 0
        require(owned == propertyIds.size) {
            "One or more chosen properties do not belong to this tenant"
        }
    }

    private fun entitlementsByProduct(): Map<String, List<String>> {
        val rows = jdbcTemplate.query(
            """
            SELECT product_code, entitlement_code
            FROM peak_product_entitlements
            WHERE is_enabled = true
            ORDER BY product_code, entitlement_code
            """.trimIndent(),
        ) { rs, _ -> rs.getString("product_code") to rs.getString("entitlement_code") }
        return rows.groupBy({ it.first }, { it.second })
    }

    private fun pricesByProduct(): Map<String, List<ProductPrice>> {
        val rows = jdbcTemplate.query(
            """
            SELECT product_code, term_months, currency, amount
            FROM peak_product_prices
            WHERE effective_from <= now()
              AND (effective_to IS NULL OR effective_to > now())
            ORDER BY product_code, term_months
            """.trimIndent(),
        ) { rs, _ ->
            rs.getString("product_code") to ProductPrice(
                termMonths = rs.getInt("term_months"),
                currency = rs.getString("currency").trim(),
                amount = rs.getBigDecimal("amount"),
            )
        }
        return rows.groupBy({ it.first }, { it.second })
    }

    private data class CatalogProduct(
        val code: String,
        val name: String,
        val isPerProperty: Boolean,
        val isSellable: Boolean,
        val requiresProductCode: String?,
    )

    internal companion object {
        val ALLOWED_TERMS = setOf(1, 3, 6, 12)

        /** Exposed so the purchase path can reuse the same period arithmetic. */
        fun periodEnd(start: Instant, termMonths: Int): Instant =
            start.atZone(ZoneOffset.UTC).plusMonths(termMonths.toLong()).toInstant()
    }
}
