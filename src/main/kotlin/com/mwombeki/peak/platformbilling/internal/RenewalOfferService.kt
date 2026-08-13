package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.platformbilling.api.PlatformBillingConflictException
import com.mwombeki.peak.platformbilling.api.PlatformBillingNotFoundException
import com.mwombeki.peak.platformbilling.api.PurchaseResponse
import com.mwombeki.peak.platformbilling.api.QuoteLineRequest
import com.mwombeki.peak.platformbilling.api.QuoteRequest
import com.mwombeki.peak.platformbilling.api.RenewalOffer
import com.mwombeki.peak.platformbilling.api.RenewalOfferStatus
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.context.TenantRequestContext
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Tells a customer their cover is running out, and turns a yes into a purchase.
 *
 * ## Why an offer is not a purchase
 *
 * `peak_purchases` allows one open order per tenant, so two concurrent PIN prompts cannot
 * fight over one handset. If the T-14 reminder were itself a quoted purchase it would hold
 * that slot for the whole fortnight, and the owner who tried to add POS to another property
 * the next morning would simply be refused. The reminder would have locked the tenant out of
 * buying anything else — a background job monopolising the checkout a real customer needs.
 *
 * So an offer holds no price and no slot. It is a notification with an expiry. Nothing is
 * priced until the customer accepts, at which point an ordinary purchase is created at the
 * ordinary price and contends for the slot exactly like any other — because by then it is
 * one.
 *
 * ## Why it holds no price
 *
 * Re-presenting a stored amount every year would grandfather customers by accident: a 2026
 * price quietly renewing in 2028 at a figure that no longer exists. Acceptance re-prices
 * through the same path as any purchase. Grandfathering is a commercial decision and should
 * have to be made on purpose.
 */
@Service
class RenewalOfferService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val tenantRequestContext: TenantRequestContext,
    private val purchaseService: PurchaseService,
) {
    private val log = LoggerFactory.getLogger(RenewalOfferService::class.java)

    /**
     * Creates offers for tenants whose cover runs out within the notice period.
     *
     * Runs on an unbound sweep, so it reaches across tenants through a definer function.
     * Repetition is a no-op: the partial unique index refuses a second live offer for the
     * same expiring cover, so a loop running every fifteen minutes does not accumulate
     * reminders.
     */
    fun offerDueRenewals(noticeDays: Int, limit: Int): Int {
        val due = jdbcTemplate.query(
            """
            SELECT tenant_id, source_purchase_id, cover_ends_at, term_months
            FROM platform_billing_renewals_due(?, ?)
            """.trimIndent(),
            { rs, _ ->
                DueRenewal(
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    sourcePurchaseId = rs.getObject("source_purchase_id", UUID::class.java),
                    coverEndsAt = rs.getTimestamp("cover_ends_at"),
                    termMonths = rs.getInt("term_months"),
                )
            },
            noticeDays,
            limit,
        )

        return due.count { renewal -> createOffer(renewal) }
    }

    private fun createOffer(renewal: DueRenewal): Boolean {
        return try {
            requireNotNull(
                transactionTemplate.execute {
                    databaseSessionContext.bind(
                        RequestIdentity.Public(
                            tenantId = renewal.tenantId,
                            correlationId = "renewal-offer-${renewal.tenantId}",
                        ),
                    )
                    jdbcTemplate.update(
                        """
                        INSERT INTO peak_renewal_offers (
                            tenant_id, source_purchase_id, cover_ends_at, term_months,
                            status, notified_at
                        ) VALUES (?, ?, ?, ?, 'offered', now())
                        """.trimIndent(),
                        renewal.tenantId,
                        renewal.sourcePurchaseId,
                        renewal.coverEndsAt,
                        renewal.termMonths,
                    ) > 0
                },
            )
        } catch (ex: DuplicateKeyException) {
            // Already told them. The index is what makes the sweep idempotent rather than
            // the worker having to remember.
            false
        } catch (ex: Exception) {
            log.warn("Could not create renewal offer for tenant {}", renewal.tenantId, ex)
            false
        }
    }

    fun offers(tenantId: UUID): List<RenewalOffer> {
        return requireNotNull(
            transactionTemplate.execute {
                val actor = tenantRequestContext.bind()
                jdbcTemplate.query(
                    """
                    SELECT id, cover_ends_at, term_months, status, source_purchase_id
                    FROM peak_renewal_offers
                    WHERE tenant_id = ?
                    ORDER BY cover_ends_at DESC
                    LIMIT 50
                    """.trimIndent(),
                    { rs, _ ->
                        RenewalOffer(
                            id = rs.getObject("id", UUID::class.java),
                            coverEndsAt = rs.getTimestamp("cover_ends_at").toInstant(),
                            termMonths = rs.getInt("term_months"),
                            status = RenewalOfferStatus.fromDatabase(rs.getString("status")),
                            renewsPurchaseId = rs.getObject("source_purchase_id", UUID::class.java),
                        )
                    },
                    actor.tenantId,
                )
            },
        )
    }

    /**
     * Turns a customer's yes into a purchase, priced now.
     *
     * The selection is reconstructed from the expiring purchase, but every amount is
     * resolved afresh — the customer is buying today's product at today's price, not
     * replaying last year's invoice.
     */
    fun accept(offerId: UUID): PurchaseResponse {
        val selection = requireNotNull(
            transactionTemplate.execute {
                val actor = tenantRequestContext.bind()
                val offer = loadOffer(actor.tenantId, offerId)

                if (offer.status != "offered") {
                    throw PlatformBillingConflictException(
                        "This renewal offer is ${offer.status} and can no longer be accepted",
                    )
                }
                val sourcePurchaseId = offer.sourcePurchaseId
                    ?: throw PlatformBillingConflictException(
                        "This renewal offer does not name a purchase to renew; " +
                            "choose what to buy from the catalog instead",
                    )
                reconstructSelection(sourcePurchaseId, offer.termMonths)
            },
        )

        // Outside the transaction that read the offer, because creating the purchase takes
        // the tenant's open-order slot and may legitimately fail if they already have a
        // checkout in flight. That refusal should reach the customer as a conflict, not
        // roll back a read.
        val purchase = purchaseService.createPurchase(UUID(0L, 0L), selection)

        transactionTemplate.execute {
            val actor = tenantRequestContext.bind()
            jdbcTemplate.update(
                """
                UPDATE peak_renewal_offers
                SET status = 'accepted', accepted_purchase_id = ?, accepted_at = now(),
                    updated_at = now()
                WHERE id = ? AND tenant_id = ? AND status = 'offered'
                """.trimIndent(),
                purchase.id,
                offerId,
                actor.tenantId,
            )
        }

        return purchase
    }

    fun decline(offerId: UUID) {
        transactionTemplate.execute {
            val actor = tenantRequestContext.bind()
            jdbcTemplate.update(
                """
                UPDATE peak_renewal_offers
                SET status = 'declined', declined_at = now(), updated_at = now()
                WHERE id = ? AND tenant_id = ? AND status = 'offered'
                """.trimIndent(),
                offerId,
                actor.tenantId,
            )
        }
    }

    private fun loadOffer(tenantId: UUID, offerId: UUID): StoredOffer {
        return jdbcTemplate.query(
            """
            SELECT status, term_months, source_purchase_id
            FROM peak_renewal_offers
            WHERE id = ? AND tenant_id = ?
            """.trimIndent(),
            { rs, _ ->
                StoredOffer(
                    status = rs.getString("status"),
                    termMonths = rs.getInt("term_months"),
                    sourcePurchaseId = rs.getObject("source_purchase_id", UUID::class.java),
                )
            },
            offerId,
            tenantId,
        ).firstOrNull() ?: throw PlatformBillingNotFoundException("Renewal offer was not found")
    }

    /**
     * What to buy, taken from what is expiring — products, and for a per-property add-on the
     * properties it covered. Amounts are deliberately not carried across.
     */
    private fun reconstructSelection(sourcePurchaseId: UUID, termMonths: Int): QuoteRequest {
        val lines = jdbcTemplate.query(
            """
            SELECT line.product_code,
                   line.covered_property_ids::text AS covered_property_ids
            FROM peak_purchase_lines line
            JOIN peak_products product ON product.code = line.product_code
            WHERE line.purchase_id = ?
              AND product.is_sellable = true
            ORDER BY product.display_order, line.product_code
            """.trimIndent(),
            { rs, _ ->
                QuoteLineRequest(
                    productCode = rs.getString("product_code"),
                    propertyIds = parsePropertyIds(rs.getString("covered_property_ids")),
                )
            },
            sourcePurchaseId,
        )

        if (lines.isEmpty()) {
            throw PlatformBillingConflictException(
                "Nothing on the expiring purchase is still sold; choose from the catalog instead",
            )
        }
        return QuoteRequest(lines = lines, termMonths = termMonths)
    }

    private fun parsePropertyIds(json: String?): List<UUID> {
        if (json.isNullOrBlank() || json == "[]") {
            return emptyList()
        }
        return Regex("[0-9a-fA-F-]{36}").findAll(json).map { UUID.fromString(it.value) }.toList()
    }

    private data class DueRenewal(
        val tenantId: UUID,
        val sourcePurchaseId: UUID?,
        val coverEndsAt: java.sql.Timestamp,
        val termMonths: Int,
    )

    private data class StoredOffer(
        val status: String,
        val termMonths: Int,
        val sourcePurchaseId: UUID?,
    )
}
