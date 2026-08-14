package com.mwombeki.peak.platformbilling

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.platformbilling.api.PlatformBillingAdminPort
import com.mwombeki.peak.platformbilling.internal.PurchaseSettlementOutboxHandler
import com.mwombeki.peak.platformbilling.internal.ReceiptService
import com.mwombeki.peak.platformbilling.internal.SubscriptionLifecycleService
import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import com.mwombeki.peak.platformbilling.api.PlatformBillingPort
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Receipts, and the operator view whose whole purpose is to stop someone misreading the
 * database.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReceiptAndAdminIntegrationTests {

    @Autowired private lateinit var settlementHandler: PurchaseSettlementOutboxHandler
    @Autowired private lateinit var receiptService: ReceiptService
    @Autowired private lateinit var lifecycleService: SubscriptionLifecycleService
    @Autowired private lateinit var adminPort: PlatformBillingAdminPort
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var platformBillingPort: PlatformBillingPort
    @Autowired private lateinit var requestContextHolder: RequestContextHolder

    @AfterTest
    fun resetSession() {
        requestContextHolder.clear()
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun settlingAPurchaseIssuesExactlyOneReceipt() {
        val fixture = paidPurchase()

        // The outbox redelivers whenever a worker dies mid-flight, so a customer must not be
        // sent a second document for money they paid once.
        runBlocking { repeat(3) { settlementHandler.handle(settlementEvent(fixture)) } }

        assertEquals(
            1,
            receiptCount(fixture.purchaseId),
            "three deliveries of one settlement is still one sale",
        )
        val receipt = receiptService.findByPurchase(fixture.tenantId, fixture.purchaseId)
        assertNotNull(receipt)
        assertTrue(
            receipt.receiptNumber.startsWith("PEAK-"),
            "a Peak receipt is Peak's document, numbered from Peak's own sequence rather " +
                "than from the property's: ${receipt.receiptNumber}",
        )
        assertEquals(0, receipt.totalAmount.compareTo(BigDecimal("30000.00")))
    }

    @Test
    fun theReceiptSnapshotsTheTenantAsItWasAtTheTimeOfSale() {
        val fixture = paidPurchase()
        runBlocking { settlementHandler.handle(settlementEvent(fixture)) }

        jdbcTemplate.update(
            "UPDATE tenants SET name = 'Renamed Later' WHERE id = ?",
            fixture.tenantId,
        )

        val snapshot = jdbcTemplate.queryForObject(
            "SELECT tenant_snapshot::text FROM peak_receipts WHERE purchase_id = ?",
            String::class.java,
            fixture.purchaseId,
        ).orEmpty()

        assertTrue(
            !snapshot.contains("Renamed Later"),
            "a receipt records the transaction as it stood; a hotel that renames itself " +
                "next year has not changed what it was invoiced as",
        )
    }

    @Test
    fun anUnpaidPurchaseGetsNoReceipt() {
        val fixture = paidPurchase(status = "quoted")

        assertEquals(
            null,
            receiptService.issueFor(fixture.tenantId, fixture.purchaseId),
            "a receipt is evidence of payment, so quoting one would be a lie",
        )
        assertEquals(0, receiptCount(fixture.purchaseId))
    }

    /**
     * The reporting problem this view exists to solve.
     *
     * A suspended tenant's subscription row deliberately still reads `past_due` and stays
     * service-granting, because that is what keeps the restriction allowances reachable so
     * the hotel can still check a guest out. Read on its own, that column says "fine". The
     * operator view has to say the harder thing.
     */
    @Test
    fun aSuspendedTenantReadsAsSuspendedEvenThoughItsSubscriptionRowLooksHealthy() {
        val fixture = paidPurchase()
        jdbcTemplate.update(
            """
            INSERT INTO peak_product_grants (
                tenant_id, product_code, source, source_purchase_id, status,
                starts_at, ends_at, granted_entitlements
            ) VALUES (?, 'peak_core', 'purchase', ?, 'active',
                      now() - interval '90 days', now() - interval '40 days',
                      '{"module.frontdesk": {"is_enabled": true, "value": {}}}'::jsonb)
            """.trimIndent(),
            fixture.tenantId,
            fixture.purchaseId,
        )
        lifecycleService.advance(fixture.tenantId, "corr-standing-suspend")

        val standing = adminPort.commercialStanding(1000)
            .single { it.tenantId == fixture.tenantId }

        assertEquals("suspended", standing.commercialStanding)
        assertTrue(
            standing.subscriptionRowStatus in setOf("past_due", "trialing", "active", "paused"),
            "precondition: the row is deliberately still service-granting",
        )
        assertTrue(
            standing.serviceRelationship.contains("deliberately"),
            "the view must say why the row looks healthy, or someone will 'fix' it: " +
                standing.serviceRelationship,
        )
        assertTrue(
            standing.paymentStatus.startsWith("overdue"),
            "and it must say plainly that the customer has not paid: ${standing.paymentStatus}",
        )
        assertTrue(
            standing.operationalPolicy.contains("checkout"),
            "and what the tenant may still do: ${standing.operationalPolicy}",
        )
    }

    @Test
    fun aStuckPaymentAppearsForOperatorsWithItsNumberMasked() {
        val fixture = paidPurchase(status = "awaiting_payment")
        jdbcTemplate.update(
            """
            INSERT INTO peak_payment_attempts (
                purchase_id, tenant_id, attempt_no, provider, payment_method, payer_msisdn,
                amount, currency, internal_reference, status, last_status_error
            ) VALUES (?, ?, 1, 'azampay', 'mobile_money', '255700123456', 30000.00, 'TZS', ?,
                      'reconciliation_required', 'connection reset')
            """.trimIndent(),
            fixture.purchaseId,
            fixture.tenantId,
            "STUCK-${fixture.purchaseId.toString().take(8)}".uppercase(),
        )

        val stuck = adminPort.paymentsRequiringReconciliation(1000)
            .single { it.purchaseId == fixture.purchaseId }

        assertEquals("connection reset", stuck.lastStatusError)
        assertTrue(
            stuck.payerMsisdnMasked.orEmpty().contains("xxx"),
            "an operator needs to recognise the number, not read it: ${stuck.payerMsisdnMasked}",
        )
        assertTrue(
            !stuck.payerMsisdnMasked.orEmpty().contains("0123456"),
            "the middle digits must not survive masking: ${stuck.payerMsisdnMasked}",
        )
    }

    @Test
    fun receiptNumbersAreUniqueAcrossPurchases() {
        val first = paidPurchase()
        val second = paidPurchase()

        runBlocking {
            settlementHandler.handle(settlementEvent(first))
            settlementHandler.handle(settlementEvent(second))
        }

        val numbers = adminPort.receipts(1000)
            .filter { it.purchaseId in setOf(first.purchaseId, second.purchaseId) }
            .map { it.receiptNumber }

        assertEquals(2, numbers.size)
        assertEquals(2, numbers.toSet().size, "two sales, two distinct receipt numbers")
    }

    /**
     * The customer can read the receipt Peak issued them.
     *
     * V99 built the receipt correctly — one per purchase, sequentially numbered — and gave it
     * exactly one route, `/api/platform/billing/receipts` behind `platform.billing.view`.
     * That is a Peak staff permission. `TenantBillingController` had catalog, quotes,
     * purchases and renewal offers, and no receipts at all. Peak took a hotel's money,
     * allocated a numbered receipt for it, and filed it where the hotel could not reach it —
     * which for a Tanzanian business is the document their bookkeeping and their own filing
     * rest on.
     */
    @Test
    fun aTenantCanReadTheReceiptPeakIssuedIt() {
        val fixture = paidPurchase()
        runBlocking { settlementHandler.handle(settlementEvent(fixture)) }

        requestContextHolder.set(fixture.context())
        val receipts = platformBillingPort.receipts(fixture.tenantId)

        assertEquals(1, receipts.size, "the purchase settled, so its receipt must be readable")
        assertEquals(fixture.purchaseId, receipts.single().purchaseId)
        assertTrue(
            receipts.single().receiptNumber.isNotBlank(),
            "a receipt without its number is not evidence of anything",
        )
    }

    /** One hotel's receipts must not be another's, whatever the caller asks for. */
    @Test
    fun aTenantSeesOnlyItsOwnReceipts() {
        val mine = paidPurchase()
        val theirs = paidPurchase()
        runBlocking {
            settlementHandler.handle(settlementEvent(mine))
            settlementHandler.handle(settlementEvent(theirs))
        }

        requestContextHolder.set(mine.context())
        val receipts = platformBillingPort.receipts(mine.tenantId)

        assertEquals(
            listOf(mine.purchaseId),
            receipts.map { it.purchaseId },
            "the read is bound to the caller's own tenant, not to the id it passed",
        )
    }

    /**
     * A suspended tenant pays to end the suspension, so the evidence of having paid has to
     * survive it too. `tenant.subscription.view` matches `tenant.subscription.%`, which is
     * allowed under suspension — this asserts that rather than leaving it to be noticed.
     */
    @Test
    fun theReceiptsRouteStaysReachableWhileSuspended() {
        val unreachable = jdbcTemplate.queryForList(
            """
            SELECT route.api_pattern
            FROM module_access_matrix route
            WHERE route.api_pattern LIKE '/api/tenants/:tenantId/billing/%'
              AND route.permission_code IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM peak_restriction_allowances allowance
                  WHERE allowance.restriction_state = 'suspended'
                    AND route.permission_code LIKE allowance.permission_pattern
              )
            """.trimIndent(),
            String::class.java,
        )

        assertEquals(
            emptyList(),
            unreachable,
            "suspension is ended by paying, so both the route to paying and the evidence of " +
                "having paid must outlive it",
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM module_access_matrix
                WHERE api_pattern = '/api/tenants/:tenantId/billing/receipts'
                """.trimIndent(),
                Int::class.java,
            ),
            "the route must be registered, or the guard above passes over nothing",
        )
    }

    private fun receiptCount(purchaseId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM peak_receipts WHERE purchase_id = ?",
            Int::class.java,
            purchaseId,
        ) ?: 0

    private fun settlementEvent(fixture: PurchaseFixture): ClaimedOutboxEvent {
        val now = Instant.now()
        return ClaimedOutboxEvent(
            id = UUID.randomUUID(),
            tenantId = fixture.tenantId,
            propertyId = null,
            aggregateType = "peak_purchase",
            aggregateId = fixture.purchaseId,
            eventType = "platform.purchase.paid",
            destination = OutboxDestination.PLATFORM_BILLING,
            payload = """{"purchaseId":"${fixture.purchaseId}"}""",
            headers = "{}",
            correlationId = "corr-receipt-${fixture.purchaseId}",
            idempotencyKeyId = null,
            status = OutboxStatus.LOCKED,
            priority = 5,
            attemptCount = 1,
            maxAttempts = 10,
            nextAttemptAt = now,
            lockedBy = "test",
            lockedAt = now,
            deliveredAt = null,
            failedAt = null,
            errorMessage = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun paidPurchase(status: String = "paid"): PurchaseFixture {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val purchaseId = UUID.randomUUID()

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId, "Plan $planId", "plan-$planId",
        )
        jdbcTemplate.update(
            "INSERT INTO tenants (id, name, slug, schema_name, plan_id) VALUES (?, ?, ?, ?, ?)",
            tenantId, "Original Hotel $tenantId", "tenant-$tenantId",
            "tenant_$tenantId".replace("-", "_"), planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_control_states (
                tenant_id, lifecycle_status, verification_status, provisioning_status,
                subscription_status, service_status, offboarding_status
            ) VALUES (?, 'active', 'verified', 'ready', 'active', 'operational', 'none')
            """.trimIndent(),
            tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_subscriptions (
                tenant_id, plan_id, status, billing_cycle, billing_currency,
                provider, current_period_starts_at
            ) VALUES (?, ?, 'active', 'monthly', 'TZS', 'manual', now() - interval '60 days')
            """.trimIndent(),
            tenantId, planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO peak_purchases (
                id, tenant_id, status, currency, term_months, total_amount,
                period_starts_at, period_ends_at, quote_expires_at
            ) VALUES (?, ?, ?, 'TZS', 1, 30000.00,
                      now(), now() + interval '30 days', now() + interval '2 hours')
            """.trimIndent(),
            purchaseId, tenantId, status,
        )
        jdbcTemplate.update(
            """
            INSERT INTO peak_purchase_lines (
                purchase_id, tenant_id, product_code, term_months, quantity,
                covered_property_ids, unit_amount, amount, entitlement_snapshot
            ) VALUES (?, ?, 'peak_core', 1, 1, '[]'::jsonb, 30000.00, 30000.00,
                      '{"module.frontdesk": {"is_enabled": true, "auto_activate": true, "value": {}}}'::jsonb)
            """.trimIndent(),
            purchaseId, tenantId,
        )

        return PurchaseFixture(tenantId, purchaseId)
    }

    private data class PurchaseFixture(val tenantId: UUID, val purchaseId: UUID) {
        fun context(): RequestContext {
            val correlationId = "corr-receipts-$tenantId"
            return RequestContext(
                identity = RequestIdentity.Tenant(
                    tenantId = tenantId,
                    tenantUserId = UUID.randomUUID(),
                    correlationId = correlationId,
                ),
                correlationId = correlationId,
                idempotencyKey = null,
                httpMethod = "GET",
                requestPath = "/api/v1/tenants/$tenantId/billing/receipts",
            )
        }
    }
}
