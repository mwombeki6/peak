package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.platformbilling.api.PlatformBillingPort
import com.mwombeki.peak.platformbilling.api.QuoteLineRequest
import com.mwombeki.peak.platformbilling.api.QuoteRequest
import com.mwombeki.peak.platformbilling.api.RenewalOfferStatus
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The reason renewal offers exist as their own thing.
 *
 * The obvious design — create a quoted purchase at T-14 — would have held the tenant's one
 * open-order slot for a fortnight, so the owner who tried to add POS to another property the
 * next morning would simply have been refused. That is the test in the middle of this file,
 * and it is the whole justification for the extra table.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RenewalOfferIntegrationTests {

    @Autowired
    private lateinit var renewalOfferService: RenewalOfferService

    @Autowired
    private lateinit var platformBillingPort: PlatformBillingPort

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun coverExpiringInsideTheNoticePeriodProducesAnOffer() {
        val fixture = tenantWithExpiringCover(daysLeft = 10)

        val offered = renewalOfferService.offerDueRenewals(noticeDays = 14, limit = 100)

        assertTrue(offered >= 1)
        requestContextHolder.set(fixture.context())
        val offers = renewalOfferService.offers(fixture.tenantId)
        assertEquals(1, offers.size)
        assertEquals(RenewalOfferStatus.OFFERED, offers.single().status)
    }

    @Test
    fun coverWithMoreThanTheNoticePeriodLeftIsNotOffered() {
        val fixture = tenantWithExpiringCover(daysLeft = 40)

        renewalOfferService.offerDueRenewals(noticeDays = 14, limit = 100)

        requestContextHolder.set(fixture.context())
        assertTrue(
            renewalOfferService.offers(fixture.tenantId).isEmpty(),
            "nobody wants a renewal reminder six weeks early",
        )
    }

    @Test
    fun sweepingRepeatedlyDoesNotAccumulateReminders() {
        val fixture = tenantWithExpiringCover(daysLeft = 5)

        repeat(4) { renewalOfferService.offerDueRenewals(noticeDays = 14, limit = 100) }

        requestContextHolder.set(fixture.context())
        assertEquals(
            1,
            renewalOfferService.offers(fixture.tenantId).size,
            "a loop running every fifteen minutes must not send a reminder every fifteen minutes",
        )
    }

    /**
     * The bug this whole design exists to avoid.
     *
     * With the renewal modelled as a quoted purchase, this is what a customer would have
     * hit: a reminder generated overnight occupies the single open-order slot, and buying
     * anything else is refused for the next fortnight.
     */
    @Test
    fun anOutstandingRenewalOfferDoesNotBlockBuyingSomethingElse() {
        val fixture = tenantWithExpiringCover(daysLeft = 7)
        renewalOfferService.offerDueRenewals(noticeDays = 14, limit = 100)

        requestContextHolder.set(fixture.context("idem-addon-while-offered"))
        val addOn = platformBillingPort.createPurchase(
            fixture.tenantId,
            QuoteRequest(
                lines = listOf(QuoteLineRequest("peak_pos", propertyIds = listOf(fixture.propertyId))),
                termMonths = 1,
            ),
        )

        assertTrue(
            addOn.totalAmount > BigDecimal.ZERO,
            "an outstanding renewal reminder must not lock the tenant out of buying add-ons",
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM peak_purchases WHERE tenant_id = ? AND status = 'quoted'",
                Int::class.java,
                fixture.tenantId,
            ),
            "the offer must not itself be occupying an open-order slot",
        )
    }

    @Test
    fun acceptingAnOfferPricesAtTodaysCatalogRatherThanTheOldAmount() {
        val fixture = tenantWithExpiringCover(daysLeft = 7, previousAmount = BigDecimal("1.00"))
        renewalOfferService.offerDueRenewals(noticeDays = 14, limit = 100)

        requestContextHolder.set(fixture.context("idem-accept-renewal"))
        val offerId = renewalOfferService.offers(fixture.tenantId).single().id
        val renewal = renewalOfferService.accept(offerId)

        val catalogPrice = jdbcTemplate.queryForObject(
            """
            SELECT amount FROM peak_product_prices
            WHERE product_code = 'peak_core' AND term_months = 1 AND currency = 'TZS'
              AND effective_from <= now() AND (effective_to IS NULL OR effective_to > now())
            """.trimIndent(),
            BigDecimal::class.java,
        )

        assertEquals(
            0,
            renewal.totalAmount.compareTo(catalogPrice),
            "the expiring purchase was recorded at 1.00; renewing must charge today's price, " +
                "or a stale column would grandfather customers by accident",
        )
    }

    @Test
    fun anAcceptedOfferIsRecordedAgainstThePurchaseItProduced() {
        val fixture = tenantWithExpiringCover(daysLeft = 7)
        renewalOfferService.offerDueRenewals(noticeDays = 14, limit = 100)

        requestContextHolder.set(fixture.context("idem-accept-record"))
        val offerId = renewalOfferService.offers(fixture.tenantId).single().id
        val renewal = renewalOfferService.accept(offerId)

        assertEquals(
            renewal.id,
            jdbcTemplate.queryForObject(
                "SELECT accepted_purchase_id FROM peak_renewal_offers WHERE id = ?",
                UUID::class.java,
                offerId,
            ),
        )
        assertEquals(
            RenewalOfferStatus.ACCEPTED,
            renewalOfferService.offers(fixture.tenantId).single { it.id == offerId }.status,
        )
    }

    @Test
    fun aDeclinedOfferIsNotReissuedOnTheNextSweep() {
        val fixture = tenantWithExpiringCover(daysLeft = 7)
        renewalOfferService.offerDueRenewals(noticeDays = 14, limit = 100)

        requestContextHolder.set(fixture.context())
        val offerId = renewalOfferService.offers(fixture.tenantId).single().id
        renewalOfferService.decline(offerId)

        renewalOfferService.offerDueRenewals(noticeDays = 14, limit = 100)

        val offers = renewalOfferService.offers(fixture.tenantId)
        assertEquals(
            1,
            offers.count { it.status == RenewalOfferStatus.OFFERED } +
                offers.count { it.status == RenewalOfferStatus.DECLINED },
            "declining then re-offering the same cover would be nagging",
        )
    }

    private fun tenantWithExpiringCover(
        daysLeft: Int,
        previousAmount: BigDecimal = BigDecimal("30000.00"),
    ): RenewalFixture {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val propertyId = UUID.randomUUID()
        val purchaseId = UUID.randomUUID()

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId,
            "Plan $planId",
            "plan-$planId",
        )
        jdbcTemplate.update(
            "INSERT INTO tenants (id, name, slug, schema_name, plan_id) VALUES (?, ?, ?, ?, ?)",
            tenantId,
            "Tenant $tenantId",
            "tenant-$tenantId",
            "tenant_$tenantId".replace("-", "_"),
            planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            userId,
            tenantId,
            "Owner $userId",
            "owner-$userId@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, code, type, status, is_active)
            VALUES (?, ?, 'Renewal Property', ?, 'HOTEL', 'active', true)
            """.trimIndent(),
            propertyId,
            tenantId,
            "RN-${propertyId.toString().take(6)}",
        )

        // The purchase that is expiring, recorded at a price deliberately unlike today's.
        jdbcTemplate.update(
            """
            INSERT INTO peak_purchases (
                id, tenant_id, status, currency, term_months, total_amount,
                period_starts_at, period_ends_at, quote_expires_at
            ) VALUES (?, ?, 'paid', 'TZS', 1, ?,
                      now() - interval '30 days', now() + make_interval(days => ?),
                      now() - interval '29 days')
            """.trimIndent(),
            purchaseId,
            tenantId,
            previousAmount,
            daysLeft,
        )
        jdbcTemplate.update(
            """
            INSERT INTO peak_purchase_lines (
                purchase_id, tenant_id, product_code, term_months, quantity,
                covered_property_ids, unit_amount, amount, entitlement_snapshot
            ) VALUES (?, ?, 'peak_core', 1, 1, '[]'::jsonb, ?, ?,
                      '{"module.frontdesk": {"is_enabled": true, "value": {}}}'::jsonb)
            """.trimIndent(),
            purchaseId,
            tenantId,
            previousAmount,
            previousAmount,
        )
        jdbcTemplate.update(
            """
            INSERT INTO peak_product_grants (
                tenant_id, product_code, source, source_purchase_id, status,
                starts_at, ends_at, granted_entitlements
            ) VALUES (?, 'peak_core', 'purchase', ?, 'active',
                      now() - interval '30 days', now() + make_interval(days => ?),
                      '{"module.frontdesk": {"is_enabled": true, "value": {}}}'::jsonb)
            """.trimIndent(),
            tenantId,
            purchaseId,
            daysLeft,
        )

        return RenewalFixture(tenantId, userId, propertyId, purchaseId)
    }

    private data class RenewalFixture(
        val tenantId: UUID,
        val userId: UUID,
        val propertyId: UUID,
        val expiringPurchaseId: UUID,
    ) {
        fun context(idempotencyKey: String? = null): RequestContext {
            val correlationId = "corr-${idempotencyKey ?: "renewal-read"}-$tenantId"
            return RequestContext(
                identity = RequestIdentity.Tenant(
                    tenantId = tenantId,
                    tenantUserId = userId,
                    correlationId = correlationId,
                ),
                correlationId = correlationId,
                idempotencyKey = idempotencyKey,
                httpMethod = "POST",
                requestPath = "/api/v1/tenants/$tenantId/billing/renewal-offers",
            )
        }
    }
}
