package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.platformbilling.api.PlatformBillingConflictException
import com.mwombeki.peak.platformbilling.api.PlatformBillingPort
import com.mwombeki.peak.platformbilling.api.PlatformBillingUncollectableException
import com.mwombeki.peak.platformbilling.api.ProductKind
import com.mwombeki.peak.platformbilling.api.PurchaseStatus
import com.mwombeki.peak.platformbilling.api.QuoteLineRequest
import com.mwombeki.peak.platformbilling.api.QuoteRequest
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Covers the two things that cost money if they are wrong: what a selection is priced at,
 * and whether one customer action can produce two orders.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PlatformBillingPurchaseIntegrationTests {

    @Autowired
    private lateinit var platformBillingPort: PlatformBillingPort

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
    }

    @Test
    fun catalogOffersOnlySellableProductsAndHidesTheContractOnlyTier() {
        val fixture = billingFixture()
        requestContextHolder.set(fixture.context())

        val catalog = platformBillingPort.catalog()
        val codes = catalog.map { it.code }

        assertTrue("peak_core" in codes, "catalog was $codes")
        assertTrue("peak_pos" in codes, "catalog was $codes")
        assertFalse(
            "peak_group" in codes,
            "peak_group is contract-only and must never be self-service purchasable",
        )
        assertTrue(
            catalog.single { it.code == "peak_core" }.kind == ProductKind.BASE,
        )
        assertTrue(
            catalog.single { it.code == "peak_pos" }.isPerProperty,
        )
        catalog.forEach { product ->
            assertEquals(
                setOf(1, 3, 6, 12),
                product.prices.map { it.termMonths }.toSet(),
                "${product.code} is missing a term",
            )
        }
    }

    @Test
    fun everyTermQuotesAndLongerTermsCostLessPerMonth() {
        val fixture = billingFixture()
        requestContextHolder.set(fixture.context())

        val monthlyRates = listOf(1, 3, 6, 12).associateWith { term ->
            val quote = platformBillingPort.quote(
                fixture.tenantId,
                QuoteRequest(
                    lines = listOf(QuoteLineRequest(productCode = "peak_core")),
                    termMonths = term,
                ),
            )
            assertEquals(term, quote.termMonths)
            assertEquals("TZS", quote.currency)
            assertTrue(quote.expiresAt > quote.periodStartsAt, "a quote must expire in the future")
            quote.totalAmount.divide(BigDecimal(term), 2, java.math.RoundingMode.HALF_UP)
        }

        assertTrue(
            monthlyRates.getValue(12) < monthlyRates.getValue(1),
            "a 12 month term must beat monthly: $monthlyRates",
        )
        assertTrue(
            monthlyRates.getValue(12) <= monthlyRates.getValue(6),
            "longer terms must not get more expensive per month: $monthlyRates",
        )
    }

    @Test
    fun perPropertyAddOnIsPricedByTheNumberOfPropertiesChosen() {
        val fixture = billingFixture(propertyCount = 3)
        requestContextHolder.set(fixture.context())

        val one = platformBillingPort.quote(
            fixture.tenantId,
            QuoteRequest(
                lines = listOf(
                    QuoteLineRequest("peak_pos", propertyIds = listOf(fixture.propertyIds[0])),
                ),
                termMonths = 1,
            ),
        )
        val two = platformBillingPort.quote(
            fixture.tenantId,
            QuoteRequest(
                lines = listOf(
                    QuoteLineRequest("peak_pos", propertyIds = fixture.propertyIds.take(2)),
                ),
                termMonths = 1,
            ),
        )

        assertEquals(1, one.lines.single().quantity)
        assertEquals(2, two.lines.single().quantity)
        assertEquals(
            one.totalAmount.multiply(BigDecimal(2)),
            two.totalAmount,
            "two properties must cost exactly twice one",
        )
    }

    @Test
    fun perPropertyAddOnWithoutAPropertyIsRejected() {
        val fixture = billingFixture()
        requestContextHolder.set(fixture.context())

        val failure = assertFailsWith<IllegalArgumentException> {
            platformBillingPort.quote(
                fixture.tenantId,
                QuoteRequest(
                    lines = listOf(QuoteLineRequest("peak_pos")),
                    termMonths = 1,
                ),
            )
        }
        assertTrue(
            failure.message.orEmpty().contains("per property"),
            "message was ${failure.message}",
        )
    }

    @Test
    fun addOnRequiringAnotherProductIsRejectedWhenThatProductIsAbsent() {
        val fixture = billingFixture(propertyCount = 1)
        requestContextHolder.set(fixture.context())

        val failure = assertFailsWith<IllegalArgumentException> {
            platformBillingPort.quote(
                fixture.tenantId,
                QuoteRequest(
                    lines = listOf(
                        QuoteLineRequest("peak_inventory", propertyIds = fixture.propertyIds),
                    ),
                    termMonths = 1,
                ),
            )
        }
        assertTrue(
            failure.message.orEmpty().contains("peak_pos"),
            "message was ${failure.message}",
        )

        // The same line succeeds once its prerequisite is in the same order.
        val quote = platformBillingPort.quote(
            fixture.tenantId,
            QuoteRequest(
                lines = listOf(
                    QuoteLineRequest("peak_pos", propertyIds = fixture.propertyIds),
                    QuoteLineRequest("peak_inventory", propertyIds = fixture.propertyIds),
                ),
                termMonths = 1,
            ),
        )
        assertEquals(2, quote.lines.size)
    }

    @Test
    fun propertyBelongingToAnotherTenantCannotBeQuotedFor() {
        val buyer = billingFixture(propertyCount = 1)
        val neighbour = billingFixture(propertyCount = 1)
        requestContextHolder.set(buyer.context())

        assertFailsWith<IllegalArgumentException> {
            platformBillingPort.quote(
                buyer.tenantId,
                QuoteRequest(
                    lines = listOf(
                        QuoteLineRequest("peak_pos", propertyIds = neighbour.propertyIds),
                    ),
                    termMonths = 1,
                ),
            )
        }
    }

    @Test
    fun selectionAboveTheCollectionCapIsRejectedAtQuoteWithAnActionableMessage() {
        val unitAmount = jdbcTemplate.queryForObject(
            """
            SELECT amount FROM peak_product_prices
            WHERE product_code = 'peak_pos' AND term_months = 12 AND currency = 'TZS'
              AND effective_from <= now()
              AND (effective_to IS NULL OR effective_to > now())
            """.trimIndent(),
            BigDecimal::class.java,
        )
        requireNotNull(unitAmount) { "peak_pos must have a 12 month price" }

        val cap = BigDecimal("5000000.00")
        val propertiesNeeded = cap.divide(unitAmount, 0, java.math.RoundingMode.FLOOR).toInt() + 1
        require(propertiesNeeded in 2..200) {
            "peak_pos pricing has moved so far that this test would insert " +
                "$propertiesNeeded properties; revisit the cap or the price"
        }

        val fixture = billingFixture(propertyCount = propertiesNeeded)
        requestContextHolder.set(fixture.context())

        val failure = assertFailsWith<PlatformBillingUncollectableException> {
            platformBillingPort.quote(
                fixture.tenantId,
                QuoteRequest(
                    lines = listOf(
                        QuoteLineRequest("peak_pos", propertyIds = fixture.propertyIds),
                    ),
                    termMonths = 12,
                ),
            )
        }

        val message = failure.message
        assertTrue(message.contains("5000000"), "message must name the limit: $message")
        assertTrue(
            message.contains("shorter term") || message.contains("bank transfer"),
            "message must tell the customer what to do instead: $message",
        )
    }

    @Test
    fun purchaseFreezesThePriceAndTheEntitlementsItWillGrant() {
        val fixture = billingFixture(propertyCount = 2)
        requestContextHolder.set(fixture.context("idem-purchase-freeze"))

        val purchase = platformBillingPort.createPurchase(
            fixture.tenantId,
            QuoteRequest(
                lines = listOf(
                    QuoteLineRequest("peak_core"),
                    QuoteLineRequest("peak_pos", propertyIds = fixture.propertyIds),
                ),
                termMonths = 3,
            ),
        )

        assertEquals(PurchaseStatus.QUOTED, purchase.status)
        assertEquals(3, purchase.termMonths)
        assertFalse(purchase.replayed)
        assertEquals(2, purchase.lines.size)

        val posLine = purchase.lines.single { it.productCode == "peak_pos" }
        assertEquals(2, posLine.quantity)
        assertEquals(fixture.propertyIds.toSet(), posLine.coveredPropertyIds.toSet())
        assertEquals(
            purchase.lines.sumOf { it.amount },
            purchase.totalAmount,
            "the stored total must equal the sum of its lines",
        )

        val snapshots = jdbcTemplate.queryForList(
            """
            SELECT entitlement_snapshot::text
            FROM peak_purchase_lines
            WHERE purchase_id = ?
            """.trimIndent(),
            String::class.java,
            purchase.id,
        ).filterNotNull()
        assertEquals(2, snapshots.size)
        assertTrue(
            snapshots.any { it.contains("module.pos") },
            "the POS line must record the entitlement it was sold as granting: $snapshots",
        )

        // A later catalog change must not reach an order already placed.
        assertTrue(
            snapshots.none { it == "{}" },
            "an empty snapshot would grant nothing on settlement: $snapshots",
        )
    }

    @Test
    fun replayingAPurchaseReturnsTheOriginalRatherThanCreatingASecond() {
        val fixture = billingFixture()
        requestContextHolder.set(fixture.context("idem-purchase-replay"))
        val request = QuoteRequest(
            lines = listOf(QuoteLineRequest("peak_core")),
            termMonths = 1,
        )

        val first = platformBillingPort.createPurchase(fixture.tenantId, request)
        val second = platformBillingPort.createPurchase(fixture.tenantId, request)

        assertFalse(first.replayed)
        assertTrue(second.replayed)
        assertEquals(first.id, second.id)
        assertEquals(first.totalAmount, second.totalAmount)
        assertEquals(1, openPurchaseCount(fixture.tenantId))
    }

    @Test
    fun aSecondOpenPurchaseIsRefusedSoOneCustomerActionCannotProduceTwoOrders() {
        val fixture = billingFixture()
        requestContextHolder.set(fixture.context("idem-purchase-first"))
        platformBillingPort.createPurchase(
            fixture.tenantId,
            QuoteRequest(lines = listOf(QuoteLineRequest("peak_core")), termMonths = 1),
        )

        // A different key, so idempotency does not absorb it — this must be caught by the
        // one-open-order index instead.
        requestContextHolder.set(fixture.context("idem-purchase-second"))
        val failure = assertFailsWith<PlatformBillingConflictException> {
            platformBillingPort.createPurchase(
                fixture.tenantId,
                QuoteRequest(lines = listOf(QuoteLineRequest("peak_pro")), termMonths = 1),
            )
        }

        assertTrue(
            failure.message.contains("open purchase"),
            "message was ${failure.message}",
        )
        assertEquals(1, openPurchaseCount(fixture.tenantId))
    }

    @Test
    fun anUnknownProductIsRejectedRatherThanPricedAtZero() {
        val fixture = billingFixture()
        requestContextHolder.set(fixture.context())

        assertFailsWith<com.mwombeki.peak.platformbilling.api.PlatformBillingNotFoundException> {
            platformBillingPort.quote(
                fixture.tenantId,
                QuoteRequest(
                    lines = listOf(QuoteLineRequest("peak_nonexistent")),
                    termMonths = 1,
                ),
            )
        }
    }

    private fun openPurchaseCount(tenantId: UUID): Int {
        return jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM peak_purchases
            WHERE tenant_id = ? AND status IN ('quoted', 'awaiting_payment')
            """.trimIndent(),
            Int::class.java,
            tenantId,
        ) ?: 0
    }

    private fun billingFixture(propertyCount: Int = 0): BillingFixture {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val userId = UUID.randomUUID()

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId,
            "Plan $planId",
            "plan-$planId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, schema_name, plan_id)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
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

        val propertyIds = (1..propertyCount).map { index ->
            val propertyId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO properties (id, tenant_id, name, code, type, status, is_active)
                VALUES (?, ?, ?, ?, 'HOTEL', 'active', true)
                """.trimIndent(),
                propertyId,
                tenantId,
                "Property $index",
                "P$index-${propertyId.toString().take(6)}",
            )
            propertyId
        }

        return BillingFixture(tenantId, userId, propertyIds)
    }

    private data class BillingFixture(
        val tenantId: UUID,
        val userId: UUID,
        val propertyIds: List<UUID>,
    ) {
        fun context(idempotencyKey: String? = null): RequestContext {
            val correlationId = "corr-${idempotencyKey ?: "billing-read"}-$tenantId"
            return RequestContext(
                identity = RequestIdentity.Tenant(
                    tenantId = tenantId,
                    tenantUserId = userId,
                    correlationId = correlationId,
                ),
                correlationId = correlationId,
                idempotencyKey = idempotencyKey,
                httpMethod = "POST",
                requestPath = "/api/v1/tenants/$tenantId/billing/purchases",
            )
        }
    }
}
