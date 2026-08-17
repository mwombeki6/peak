package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxStatus
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Exercises the settlement handler against a real database.
 *
 * Written after discovering the handler inserted columns that do not exist on
 * `peak_billing_lifecycle_events` — a statement that would have thrown on the first real
 * payment, invisible to a full suite that never called it. A handler with no test is
 * unverified however green the build is.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PurchaseSettlementIntegrationTests {

    @Autowired
    private lateinit var handler: PurchaseSettlementOutboxHandler

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun resetSession() {
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun settlingAPaidPurchaseGrantsWhatWasSoldAndRecordsIt() {
        val fixture = paidPurchase()

        runBlocking { handler.handle(settlementEvent(fixture)) }

        val grants = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM peak_product_grants WHERE source_purchase_id = ?",
            Int::class.java,
            fixture.purchaseId,
        ) ?: 0
        assertEquals(
            1,
            grants,
            "a tenant-scoped product must produce exactly one grant",
        )

        val lifecycleEvents = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM peak_billing_lifecycle_events WHERE purchase_id = ?",
            Int::class.java,
            fixture.purchaseId,
        ) ?: 0
        assertEquals(1, lifecycleEvents, "settlement must leave an audit trail")

        val grantedEntitlements = jdbcTemplate.queryForObject(
            "SELECT granted_entitlements::text FROM peak_product_grants WHERE source_purchase_id = ?",
            String::class.java,
            fixture.purchaseId,
        ).orEmpty()
        assertTrue(
            grantedEntitlements.contains("module.pos"),
            "the grant must carry the entitlements the purchase was sold as granting, " +
                "not whatever the catalog says today: $grantedEntitlements",
        )
    }

    @Test
    fun settlingTheSamePurchaseTwiceGrantsOnce() {
        val fixture = paidPurchase()

        runBlocking {
            handler.handle(settlementEvent(fixture))
            handler.handle(settlementEvent(fixture))
        }

        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM peak_product_grants WHERE source_purchase_id = ?",
                Int::class.java,
                fixture.purchaseId,
            ),
            "a replayed settlement event is not a second sale",
        )
    }

    @Test
    fun aPurchaseThatIsNotPaidGrantsNothing() {
        val fixture = paidPurchase(status = "quoted")

        runBlocking { handler.handle(settlementEvent(fixture)) }

        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM peak_product_grants WHERE source_purchase_id = ?",
                Int::class.java,
                fixture.purchaseId,
            ),
            "only a paid purchase grants; a replayed event must not resurrect a cancelled one",
        )
    }

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
            correlationId = "corr-settlement-${fixture.purchaseId}",
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
            INSERT INTO peak_purchases (
                id, tenant_id, status, currency, term_months, total_amount,
                period_starts_at, period_ends_at, quote_expires_at
            ) VALUES (?, ?, ?, 'TZS', 1, 35000.00,
                      now(), now() + interval '30 days', now() + interval '2 hours')
            """.trimIndent(),
            purchaseId,
            tenantId,
            status,
        )
        jdbcTemplate.update(
            """
            INSERT INTO peak_purchase_lines (
                purchase_id, tenant_id, product_code, term_months, quantity,
                covered_property_ids, unit_amount, amount, entitlement_snapshot
            ) VALUES (?, ?, 'peak_pos', 1, 1, '[]'::jsonb, 35000.00, 35000.00,
                      '{"module.pos": {"is_enabled": true, "auto_activate": true, "value": {}}}'::jsonb)
            """.trimIndent(),
            purchaseId,
            tenantId,
        )

        return PurchaseFixture(tenantId, purchaseId)
    }

    private data class PurchaseFixture(val tenantId: UUID, val purchaseId: UUID)
}
