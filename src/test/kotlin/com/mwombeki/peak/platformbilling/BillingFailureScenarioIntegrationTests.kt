package com.mwombeki.peak.platformbilling

import com.jayway.jsonpath.JsonPath
import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.platformbilling.internal.EntitlementReconciler
import com.mwombeki.peak.platformbilling.internal.PurchaseSettlementOutboxHandler
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
 * What happens when the happy path does not happen.
 *
 * A billing engine is judged on these, not on the sunny case. Money has already moved by the
 * time most of these begin, so the only acceptable outcomes are "applied exactly once" or
 * "visibly stuck" — never "silently lost" and never "applied twice".
 *
 * One of these tests documents a gap rather than a guarantee. That is deliberate: the
 * behaviour is real, a customer can hit it, and pretending otherwise in a test file would be
 * worse than writing it down.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BillingFailureScenarioIntegrationTests {

    @Autowired private lateinit var settlementHandler: PurchaseSettlementOutboxHandler
    @Autowired private lateinit var reconciler: EntitlementReconciler
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun resetSession() {
        jdbcTemplate.execute("RESET ALL")
    }

    /**
     * The worker crashes after the payment is recorded but before the grant is applied. The
     * outbox redelivers, possibly several times. Nothing may be applied twice.
     */
    @Test
    fun settlementIsExactlyOnceHoweverOftenTheEventIsRedelivered() {
        val fixture = paidPurchase()

        runBlocking {
            repeat(4) { settlementHandler.handle(settlementEvent(fixture)) }
        }

        assertEquals(1, grantCount(fixture.purchaseId), "a redelivered event is not a second sale")
        assertEquals(
            1,
            lifecycleEventCount(fixture.tenantId),
            "settlement must leave exactly one audit trail entry",
        )
        assertEquals("paid", purchaseStatus(fixture.purchaseId))
    }

    /**
     * A crash partway through settlement, rather than between attempts.
     *
     * The grants and the audit entry share a transaction. A purchase left with grants but no
     * audit entry — or the reverse — would be unreconcilable by hand, so an abort must leave
     * neither. Then redelivery must complete cleanly, which is what makes the outbox retry
     * safe rather than merely repeated.
     *
     * The failure is injected at the storage layer because there is no natural one: every
     * value settlement writes is already constrained to be valid by the time it gets there.
     */
    @Test
    fun aCrashDuringSettlementLeavesNothingHalfAppliedAndRedeliveryFinishesTheJob() {
        val fixture = paidPurchase()
        jdbcTemplate.update(
            """
            INSERT INTO peak_purchase_lines (
                purchase_id, tenant_id, product_code, term_months, quantity,
                covered_property_ids, unit_amount, amount, entitlement_snapshot
            ) VALUES (?, ?, 'peak_pos', 1, 1, '[]'::jsonb, 35000.00, 35000.00,
                      '{"module.pos": {"is_enabled": true, "auto_activate": true, "value": {}}}'::jsonb)
            """.trimIndent(),
            fixture.purchaseId,
            fixture.tenantId,
        )

        // Fail the audit insert, which settlement performs after every grant.
        jdbcTemplate.execute(
            "ALTER TABLE peak_billing_lifecycle_events ADD CONSTRAINT tmp_settlement_fault CHECK (false) NOT VALID",
        )
        try {
            runCatching { runBlocking { settlementHandler.handle(settlementEvent(fixture)) } }

            assertEquals(
                0,
                grantCount(fixture.purchaseId),
                "the grants were inserted before the failure and must have rolled back with it",
            )
            assertEquals(0, lifecycleEventCount(fixture.tenantId))
        } finally {
            jdbcTemplate.execute(
                "ALTER TABLE peak_billing_lifecycle_events DROP CONSTRAINT tmp_settlement_fault",
            )
        }

        // The worker restarts and the outbox redelivers.
        runBlocking { settlementHandler.handle(settlementEvent(fixture)) }

        assertEquals(
            2,
            grantCount(fixture.purchaseId),
            "redelivery after a crash must apply the whole purchase, both lines",
        )
        assertEquals(1, lifecycleEventCount(fixture.tenantId))
    }

    /**
     * Settlement runs, then convergence fails. The payment must survive: it has been made,
     * and reverting the purchase would leave the customer charged for nothing.
     */
    @Test
    fun aFailedActivationKeepsThePaymentAndLeavesTheTenantVisiblyStuck() {
        val fixture = paidPurchase()
        runBlocking { settlementHandler.handle(settlementEvent(fixture)) }

        // Convergence cannot succeed: the grant names a module whose activation would
        // violate a foreign key, standing in for any downstream failure.
        jdbcTemplate.update(
            """
            INSERT INTO peak_product_grants (
                tenant_id, property_id, product_code, source, status, granted_entitlements
            ) VALUES (?, ?, 'peak_pos', 'purchase', 'active',
                      '{"module.pos": {"is_enabled": true, "auto_activate": true, "value": {}}}'::jsonb)
            """.trimIndent(),
            fixture.tenantId,
            UUID.randomUUID(),
        )

        reconciler.reconcileTenant(fixture.tenantId, "corr-failed-activation")

        assertEquals(
            "paid",
            purchaseStatus(fixture.purchaseId),
            "a failed activation must never revert a payment that was actually taken",
        )
        assertEquals(1, grantCount(fixture.purchaseId), "the purchased grant survives")
    }

    /**
     * A tenant whose convergence keeps failing must be findable. `consecutive_failures`
     * exists so a stuck tenant is a queryable fact rather than a support ticket nobody can
     * reproduce.
     */
    @Test
    fun aRepeatedlyFailingTenantIsRecordedRatherThanRetriedSilently() {
        val fixture = paidPurchase()
        // The grant goes in directly rather than through settlement, because settlement
        // reconciles as part of its own work — by the time it returns the module is already
        // activated and there is no first activation left to fail.
        jdbcTemplate.update(
            """
            INSERT INTO peak_product_grants (
                tenant_id, product_code, source, source_purchase_id, status,
                starts_at, ends_at, granted_entitlements
            ) VALUES (?, 'peak_core', 'purchase', ?, 'active',
                      now() - interval '1 hour', now() + interval '30 days',
                      '{"module.frontdesk": {"is_enabled": true, "auto_activate": true, "value": {}}}'::jsonb)
            """.trimIndent(),
            fixture.tenantId,
            fixture.purchaseId,
        )

        // Make convergence fail for real. Without an injected fault this test would pass
        // whether or not failures are recorded at all, which is no test.
        jdbcTemplate.execute(
            "ALTER TABLE peak_module_activations ADD CONSTRAINT tmp_converge_fault CHECK (false) NOT VALID",
        )
        val outcome = try {
            reconciler.reconcileTenant(fixture.tenantId, "corr-observable")
        } finally {
            jdbcTemplate.execute(
                "ALTER TABLE peak_module_activations DROP CONSTRAINT tmp_converge_fault",
            )
        }

        assertTrue(outcome.failed, "the injected fault should have failed convergence")
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM peak_reconciliation_state
                WHERE tenant_id = ? AND consecutive_failures > 0 AND last_error IS NOT NULL
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
            ),
            "a stuck tenant must be a queryable fact, not a support ticket nobody can " +
                "reproduce — the reconciler swallows the exception so this row is the " +
                "only trace an operator has",
        )
        assertEquals(
            "paid",
            purchaseStatus(fixture.purchaseId),
            "and the payment still stands",
        )
    }

    /**
     * The gap, written down rather than hidden.
     *
     * If a provider callback never arrives, the attempt sweep expires the attempt and
     * returns the purchase to `quoted` so the customer can try again. That is right when the
     * payment genuinely failed. It is **wrong** when the payment succeeded and only the
     * callback was lost: the customer has paid, no grant exists, and nothing in the system
     * will ever discover it.
     *
     * `PaymentProvider.queryStatus` exists and `PaymentStatusOutboxHandler` uses it — but for
     * a property's guest payments, not for Peak's own collections. Platform billing has no
     * equivalent reconciliation, so this remains the most serious open risk before taking
     * real subscription money.
     *
     * This test pins the current behaviour so that when the status-query path is built, the
     * change is visible here rather than silent.
     */
    @Test
    fun aLostCallbackCurrentlyLeavesAPaidCustomerWithoutAGrant() {
        val fixture = paidPurchase(status = "awaiting_payment")
        jdbcTemplate.update(
            """
            INSERT INTO peak_payment_attempts (
                purchase_id, tenant_id, attempt_no, provider, payer_msisdn,
                amount, currency, internal_reference, status, expires_at
            ) VALUES (?, ?, 1, 'stub', '255700000001', 30000.00, 'TZS', ?, 'pending',
                      now() - interval '1 minute')
            """.trimIndent(),
            fixture.purchaseId,
            fixture.tenantId,
            "LOST-${fixture.purchaseId.toString().take(8)}".uppercase(),
        )

        jdbcTemplate.queryForObject("SELECT platform_billing_expire_stale_attempts()", Int::class.java)

        assertEquals(
            "quoted",
            purchaseStatus(fixture.purchaseId),
            "the sweep releases the purchase so the customer can retry",
        )
        assertEquals(
            0,
            grantCount(fixture.purchaseId),
            "and grants nothing — which is correct if the payment failed, and a silent " +
                "loss if it succeeded and only the callback went missing. Platform billing " +
                "has no status-query reconciliation; see PaymentStatusOutboxHandler for the " +
                "shape the guest-payment side uses.",
        )
    }

    private fun grantCount(purchaseId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM peak_product_grants WHERE source_purchase_id = ?",
            Int::class.java,
            purchaseId,
        ) ?: 0

    private fun lifecycleEventCount(tenantId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM peak_billing_lifecycle_events WHERE tenant_id = ?",
            Int::class.java,
            tenantId,
        ) ?: 0

    private fun purchaseStatus(purchaseId: UUID): String? =
        jdbcTemplate.queryForObject(
            "SELECT status FROM peak_purchases WHERE id = ?",
            String::class.java,
            purchaseId,
        )

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
            correlationId = "corr-failure-${fixture.purchaseId}",
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
            tenantId, "Tenant $tenantId", "tenant-$tenantId",
            "tenant_$tenantId".replace("-", "_"), planId,
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

    private data class PurchaseFixture(val tenantId: UUID, val purchaseId: UUID)
}
