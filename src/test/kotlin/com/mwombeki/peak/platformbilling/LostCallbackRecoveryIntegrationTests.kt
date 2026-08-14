package com.mwombeki.peak.platformbilling

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.ProviderCollectionCommand
import com.mwombeki.peak.payment.api.ProviderCollectionResult
import com.mwombeki.peak.payment.api.ProviderPaymentStatus
import com.mwombeki.peak.payment.api.ProviderStatusQuery
import com.mwombeki.peak.payment.api.ProviderStatusResult
import com.mwombeki.peak.payment.api.ProviderWebhookNotification
import com.mwombeki.peak.platformbilling.api.PlatformBillingWebhookPort
import com.mwombeki.peak.platformbilling.internal.PaymentStatusReconciliationService
import com.mwombeki.peak.platformbilling.internal.PurchaseSettlementOutboxHandler
import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxStatus
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The most important acceptance test in platformbilling.
 *
 * A customer's account is debited, the callback never arrives, and Peak must still end up
 * knowing they paid. The alternative — concluding the payment failed because we heard
 * nothing — turns a lost network message into a customer who got nothing and is invited to
 * pay a second time.
 *
 * The distinction under test throughout: **a provider saying "failed" is an answer; silence,
 * a timeout, or an HTTP 500 are not.** Only the first may fail a payment.
 */
@Import(TestcontainersConfiguration::class, LostCallbackRecoveryIntegrationTests.Provider::class)
@SpringBootTest(
    properties = [
        "peak.platformbilling.primary-provider=stub_recovery_status",
        "peak.platformbilling.endpoint-url=https://stub.invalid",
        "peak.platformbilling.client-id-secret-ref=literal:stub-client",
        "peak.platformbilling.api-key-secret-ref=literal:stub-key",
        "peak.platformbilling.checksum-key-secret-ref=literal:stub-checksum",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class LostCallbackRecoveryIntegrationTests {

    @Autowired private lateinit var reconciliation: PaymentStatusReconciliationService
    @Autowired private lateinit var webhookPort: PlatformBillingWebhookPort
    @Autowired private lateinit var settlementHandler: PurchaseSettlementOutboxHandler
    @Autowired private lateinit var provider: StubStatusProvider
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var requestContextHolder: RequestContextHolder

    @BeforeTest
    fun resetProvider() {
        provider.reset()
    }

    @AfterTest
    fun resetSession() {
        requestContextHolder.clear()
        jdbcTemplate.execute("RESET ALL")
    }

    /**
     * In production a callback arrives over HTTP and carries a request context. Calling the
     * port directly skips the filter that establishes one, so the test supplies it.
     */
    private fun deliverCallback(fixture: PendingFixture) = try {
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Public(correlationId = "corr-callback"),
                correlationId = "corr-callback",
                idempotencyKey = null,
                httpMethod = "POST",
                requestPath = "/api/v1/platform-billing/webhooks/stub_recovery_status",
            ),
        )
        webhookPort.receive("stub_recovery_status", callbackFor(fixture))
    } finally {
        requestContextHolder.clear()
    }

    /**
     * The headline case. The payment succeeded, the callback was dropped, and the status
     * query has to be what saves the customer.
     */
    @Test
    fun aPaymentWhoseCallbackWasLostIsRecoveredByAskingTheProvider() {
        val fixture = pendingPayment()
        provider.answer(status = "success", amount = fixture.amount)

        // No webhook is ever delivered.
        val resolved = reconciliation.reconcileDueAttempts(50)

        assertTrue(resolved >= 1)
        assertEquals("confirmed", attemptStatus(fixture.attemptId))
        assertEquals("paid", purchaseStatus(fixture.purchaseId))

        runBlocking { settlementHandler.handle(settlementEvent(fixture)) }
        assertEquals(
            1,
            grantCount(fixture.purchaseId),
            "recovery must produce exactly the grant the customer paid for",
        )
    }

    /**
     * The rule that prevents the second charge. While the outcome is unknown the attempt
     * still holds the open-attempt slot, so nothing can offer another payment.
     */
    @Test
    fun anUnknownOutcomeIsNotAFailureAndKeepsTheRetrySlotHeld() {
        val fixture = pendingPayment()
        provider.failQueriesWith(RuntimeException("connection reset"))

        reconciliation.reconcileDueAttempts(50)

        assertEquals(
            "reconciliation_required",
            attemptStatus(fixture.attemptId),
            "a failed query means we do not know, not that the payment failed",
        )
        assertEquals(
            "awaiting_payment",
            purchaseStatus(fixture.purchaseId),
            "the purchase must not become payable again while the money may already be gone",
        )
        assertTrue(
            openAttemptSlotHeld(fixture.purchaseId),
            "the open-attempt index must still block a second collection",
        )
    }

    @Test
    fun anAdapterWithoutStatusSupportLeavesThePaymentUnknownRatherThanFailed() {
        val fixture = pendingPayment()
        provider.failQueriesWith(UnsupportedOperationException("no status endpoint"))

        reconciliation.reconcileDueAttempts(50)

        assertEquals("reconciliation_required", attemptStatus(fixture.attemptId))
        assertEquals("awaiting_payment", purchaseStatus(fixture.purchaseId))
    }

    @Test
    fun aStatusStringWeDoNotRecogniseIsUnknownRatherThanFailed() {
        val fixture = pendingPayment()
        provider.answer(status = "REVERSAL_IN_PROGRESS", amount = fixture.amount)

        reconciliation.reconcileDueAttempts(50)

        assertEquals(
            "reconciliation_required",
            attemptStatus(fixture.attemptId),
            "a provider adding a status we cannot read must not start failing payments",
        )
    }

    @Test
    fun aPaymentStillInFlightIsLeftAloneAndCheckedAgainLater() {
        val fixture = pendingPayment()
        provider.answer(status = "pending", amount = fixture.amount)

        reconciliation.reconcileDueAttempts(50)

        assertEquals(
            "pending",
            attemptStatus(fixture.attemptId),
            "the customer may simply not have entered their PIN yet",
        )
        assertTrue(nextCheckAt(fixture.attemptId) != null, "it must be scheduled to ask again")
        assertEquals("awaiting_payment", purchaseStatus(fixture.purchaseId))
    }

    /**
     * The only path that may conclude a payment failed: the provider said so.
     */
    @Test
    fun aDefinitiveProviderFailureIsTheOnlyThingThatMakesThePurchasePayableAgain() {
        val fixture = pendingPayment()
        provider.answer(status = "failed", amount = fixture.amount)

        reconciliation.reconcileDueAttempts(50)

        assertEquals("failed", attemptStatus(fixture.attemptId))
        assertEquals(
            "quoted",
            purchaseStatus(fixture.purchaseId),
            "now, and only now, the customer may safely try again",
        )
    }

    // ---- races between the two ways of learning the same fact ----

    @Test
    fun aPollThatWinsAndAWebhookThatArrivesLaterSettleOnce() {
        val fixture = pendingPayment()
        provider.answer(status = "success", amount = fixture.amount)

        reconciliation.reconcileDueAttempts(50)
        val receipt = deliverCallback(fixture)

        assertTrue(receipt.duplicate, "the later callback must recognise it is a replay")
        assertSettledExactlyOnce(fixture)
    }

    @Test
    fun aWebhookThatWinsAndAPollThatFollowsSettleOnce() {
        val fixture = pendingPayment()
        provider.answer(status = "success", amount = fixture.amount)

        deliverCallback(fixture)
        reconciliation.reconcileDueAttempts(50)

        assertSettledExactlyOnce(fixture)
    }

    @Test
    fun aRecoveredPaymentThatIsQueriedAgainDoesNotSettleTwice() {
        val fixture = pendingPayment()
        provider.answer(status = "success", amount = fixture.amount)

        reconciliation.reconcileDueAttempts(50)
        // The confirm clears next_status_check_at, so a second sweep should not even see it.
        val secondPass = reconciliation.reconcileDueAttempts(50)

        assertEquals(0, secondPass, "a confirmed attempt must leave the polling set")
        assertSettledExactlyOnce(fixture)
    }

    /**
     * A status query claiming a different amount is not a partial payment to reconcile — it
     * means the reference is not about this purchase, or something has been tampered with.
     */
    @Test
    fun aStatusQueryReportingTheWrongAmountDoesNotConfirmAnything() {
        val fixture = pendingPayment()
        provider.answer(status = "success", amount = BigDecimal("1.00"))

        reconciliation.reconcileDueAttempts(50)

        assertEquals("reconciliation_required", attemptStatus(fixture.attemptId))
        assertEquals("awaiting_payment", purchaseStatus(fixture.purchaseId))
    }

    /**
     * The sweep that used to conclude "failed" must now conclude "unknown".
     */
    @Test
    fun theStaleAttemptSweepNoLongerDeclaresAnythingFailed() {
        val fixture = pendingPayment()
        jdbcTemplate.update(
            "UPDATE peak_payment_attempts SET expires_at = now() - interval '1 minute' WHERE id = ?",
            fixture.attemptId,
        )

        jdbcTemplate.queryForObject("SELECT platform_billing_expire_stale_attempts()", Int::class.java)

        assertEquals("reconciliation_required", attemptStatus(fixture.attemptId))
        assertEquals(
            "awaiting_payment",
            purchaseStatus(fixture.purchaseId),
            "returning the purchase to quoted on a timeout is what invited the second charge",
        )
    }

    @Test
    fun anUnresolvablePaymentEventuallyStopsPollingAndWaitsForAnOperator() {
        val fixture = pendingPayment()
        provider.failQueriesWith(RuntimeException("provider unavailable"))

        // Far past the backoff schedule.
        jdbcTemplate.update(
            "UPDATE peak_payment_attempts SET status_check_count = ? WHERE id = ?",
            PaymentStatusReconciliationService.MAX_CHECKS + 1,
            fixture.attemptId,
        )
        reconciliation.reconcileDueAttempts(50)

        assertNull(
            nextCheckAt(fixture.attemptId),
            "automated recovery gives up rather than hammering the provider forever",
        )
        assertEquals("reconciliation_required", attemptStatus(fixture.attemptId))
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM peak_payments_requiring_reconciliation WHERE attempt_id = ?",
                Int::class.java,
                fixture.attemptId,
            ),
            "and it surfaces where an operator will find it",
        )
    }

    private fun assertSettledExactlyOnce(fixture: PendingFixture) {
        assertEquals("confirmed", attemptStatus(fixture.attemptId))
        assertEquals("paid", purchaseStatus(fixture.purchaseId))
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM outbox_events
                WHERE tenant_id = ? AND event_type = 'platform.purchase.paid'
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
            ),
            "two ways of learning the same fact must enqueue one settlement",
        )

        runBlocking { settlementHandler.handle(settlementEvent(fixture)) }
        assertEquals(1, grantCount(fixture.purchaseId))
    }

    private fun callbackFor(fixture: PendingFixture) =
        """{"reference":"${fixture.internalReference}","amount":"${fixture.amount.toPlainString()}"}"""

    private fun attemptStatus(attemptId: UUID): String? =
        jdbcTemplate.queryForObject(
            "SELECT status FROM peak_payment_attempts WHERE id = ?",
            String::class.java,
            attemptId,
        )

    private fun nextCheckAt(attemptId: UUID): Instant? =
        jdbcTemplate.queryForObject(
            "SELECT next_status_check_at FROM peak_payment_attempts WHERE id = ?",
            java.sql.Timestamp::class.java,
            attemptId,
        )?.toInstant()

    private fun purchaseStatus(purchaseId: UUID): String? =
        jdbcTemplate.queryForObject(
            "SELECT status FROM peak_purchases WHERE id = ?",
            String::class.java,
            purchaseId,
        )

    private fun grantCount(purchaseId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM peak_product_grants WHERE source_purchase_id = ?",
            Int::class.java,
            purchaseId,
        ) ?: 0

    private fun openAttemptSlotHeld(purchaseId: UUID): Boolean =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM peak_payment_attempts
                WHERE purchase_id = ?
                  AND status IN ('created', 'initiated', 'pending', 'reconciliation_required')
            )
            """.trimIndent(),
            Boolean::class.java,
            purchaseId,
        ) == true

    private fun settlementEvent(fixture: PendingFixture): ClaimedOutboxEvent {
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
            correlationId = "corr-lost-callback",
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

    private fun pendingPayment(): PendingFixture {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val purchaseId = UUID.randomUUID()
        val attemptId = UUID.randomUUID()
        val amount = BigDecimal("30000.00")
        val reference = "LOST-${UUID.randomUUID().toString().take(8)}".uppercase()

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
            ) VALUES (?, ?, 'awaiting_payment', 'TZS', 1, ?,
                      now(), now() + interval '30 days', now() + interval '2 hours')
            """.trimIndent(),
            purchaseId, tenantId, amount,
        )
        jdbcTemplate.update(
            """
            INSERT INTO peak_purchase_lines (
                purchase_id, tenant_id, product_code, term_months, quantity,
                covered_property_ids, unit_amount, amount, entitlement_snapshot
            ) VALUES (?, ?, 'peak_core', 1, 1, '[]'::jsonb, ?, ?,
                      '{"module.frontdesk": {"is_enabled": true, "auto_activate": true, "value": {}}}'::jsonb)
            """.trimIndent(),
            purchaseId, tenantId, amount, amount,
        )
        jdbcTemplate.update(
            """
            INSERT INTO peak_payment_attempts (
                id, purchase_id, tenant_id, attempt_no, provider, payer_msisdn,
                amount, currency, internal_reference, status, expires_at, next_status_check_at
            ) VALUES (?, ?, ?, 1, 'stub_recovery_status', '255700000001', ?, 'TZS', ?,
                      'pending', now() + interval '15 minutes', now() - interval '1 second')
            """.trimIndent(),
            attemptId, purchaseId, tenantId, amount, reference,
        )

        return PendingFixture(tenantId, purchaseId, attemptId, reference, amount)
    }

    private data class PendingFixture(
        val tenantId: UUID,
        val purchaseId: UUID,
        val attemptId: UUID,
        val internalReference: String,
        val amount: BigDecimal,
    )

    /**
     * A provider whose answers the test controls, including the ability to not answer at
     * all — which is the case that matters most here.
     */
    class StubStatusProvider : PaymentProvider {
        override val providerCode = "stub_recovery_status"

        private val status = AtomicReference("pending")
        private val amount = AtomicReference<BigDecimal?>(null)
        private val failure = AtomicReference<RuntimeException?>(null)
        val queryCount = AtomicInteger(0)

        fun reset() {
            status.set("pending")
            amount.set(null)
            failure.set(null)
            queryCount.set(0)
        }

        fun answer(status: String, amount: BigDecimal) {
            this.status.set(status)
            this.amount.set(amount)
            this.failure.set(null)
        }

        fun failQueriesWith(ex: RuntimeException) {
            failure.set(ex)
        }

        override fun initiate(command: ProviderCollectionCommand) = ProviderCollectionResult(
            providerReference = "STUB-${command.internalReference}",
            status = ProviderPaymentStatus.PENDING,
            providerStatus = "pending",
        )

        override fun queryStatus(command: ProviderStatusQuery): ProviderStatusResult {
            queryCount.incrementAndGet()
            failure.get()?.let { throw it }
            return ProviderStatusResult(
                internalReference = command.internalReference,
                providerReference = "STUB-${command.internalReference}",
                status = status.get().asCanonicalStatus(),
                providerStatus = status.get(),
                amount = amount.get(),
                currency = "TZS",
                clientId = null,
                providerTimestamp = Instant.now(),
            )
        }

        override fun parseWebhook(payload: String) = notification(payload)

        override fun verifyAndParseWebhook(
            payload: String,
            checksumKey: String,
            checksumRequired: Boolean,
        ) = notification(payload)

        private fun notification(payload: String): ProviderWebhookNotification {
            val reference = Regex("\"reference\":\"([^\"]+)\"").find(payload)!!.groupValues[1]
            val amountText = Regex("\"amount\":\"([^\"]+)\"").find(payload)!!.groupValues[1]
            return ProviderWebhookNotification(
                eventKey = "STUB-EVENT-$reference",
                eventType = "collection.updated",
                internalReference = reference,
                providerReference = "STUB-$reference",
                status = ProviderPaymentStatus.SUCCEEDED,
                providerStatus = "succeeded",
                amount = BigDecimal(amountText),
                currency = "TZS",
                merchantIdentity = null,
                payerIdentity = null,
                providerTimestamp = Instant.now(),
                checksumMethod = "stub",
            )
        }

        /**
         * A stub still has to do an adapter's job. The domain no longer accepts a raw provider
         * word, so mapping one is what this models — including the case that matters most,
         * where a word no adapter knows becomes UNKNOWN rather than a failed payment.
         */
        private fun String.asCanonicalStatus(): ProviderPaymentStatus = when (trim().lowercase()) {
            "success", "succeeded", "completed" -> ProviderPaymentStatus.SUCCEEDED
            "failed", "failure" -> ProviderPaymentStatus.FAILED
            "cancelled", "canceled" -> ProviderPaymentStatus.CANCELLED
            "pending", "processing" -> ProviderPaymentStatus.PENDING
            else -> ProviderPaymentStatus.UNKNOWN
        }
    }

    @TestConfiguration
    class Provider {
        @Bean
        fun stubStatusProvider() = StubStatusProvider()

    }
}
