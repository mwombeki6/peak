package com.mwombeki.peak.platformbilling

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.ProviderCollectionCommand
import com.mwombeki.peak.payment.api.ProviderCollectionResult
import com.mwombeki.peak.payment.api.ProviderPaymentStatus
import com.mwombeki.peak.payment.api.ProviderStatusQuery
import com.mwombeki.peak.payment.api.ProviderStatusResult
import com.mwombeki.peak.payment.api.ProviderWebhookNotification
import com.mwombeki.peak.payment.api.StatusQueryablePaymentProvider
import com.mwombeki.peak.platformbilling.api.EvidenceType
import com.mwombeki.peak.platformbilling.api.PlatformBillingAdminPort
import com.mwombeki.peak.platformbilling.api.ResolutionKind
import com.mwombeki.peak.platformbilling.api.ResolvePaymentCommand
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
 * What an operator can and cannot do about a payment Peak could not resolve.
 *
 * The queue exists so a stuck payment is visible. These tests are about the actions attached
 * to it, and the most important ones are the refusals: a resolution that does not match the
 * payment it claims to be about must not settle anything, because settling on a misread
 * figure grants a customer something they did not buy.
 */
@Import(TestcontainersConfiguration::class, OperatorReconciliationIntegrationTests.Provider::class)
@SpringBootTest(
    properties = [
        "peak.platformbilling.primary-provider=stub_operator",
        "peak.platformbilling.endpoint-url=https://stub.invalid",
        "peak.platformbilling.client-id-secret-ref=literal:stub-client",
        "peak.platformbilling.api-key-secret-ref=literal:stub-key",
        "peak.platformbilling.checksum-key-secret-ref=literal:stub-checksum",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class OperatorReconciliationIntegrationTests {

    @Autowired private lateinit var adminPort: PlatformBillingAdminPort
    @Autowired private lateinit var settlementHandler: PurchaseSettlementOutboxHandler
    @Autowired private lateinit var reconciliation: PaymentStatusReconciliationService
    @Autowired private lateinit var provider: StubOperatorProvider
    @Autowired private lateinit var requestContextHolder: RequestContextHolder
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeTest
    fun resetProvider() {
        provider.reset()
    }

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
        jdbcTemplate.execute("RESET ALL")
    }

    // ---- requery: the ordinary action, which decides nothing itself ----

    @Test
    fun requeryingAndFindingTheProviderSaysPaidSettlesExactlyOnce() {
        val fixture = unresolvedPayment()
        provider.answer("success", fixture.amount)

        val outcome = asOperator(fixture) { adminPort.requeryPayment(fixture.attemptId) }

        assertTrue(outcome.resolved)
        assertEquals("confirmed", attemptStatus(fixture.attemptId))
        assertEquals("paid", purchaseStatus(fixture.purchaseId))

        runBlocking { settlementHandler.handle(settlementEvent(fixture)) }
        assertEquals(1, grantCount(fixture.purchaseId))
        assertEquals(1, receiptCount(fixture.purchaseId))
    }

    @Test
    fun requeryingAndFindingItFailedMakesThePurchasePayableAgain() {
        val fixture = unresolvedPayment()
        provider.answer("failed", fixture.amount)

        val outcome = asOperator(fixture) { adminPort.requeryPayment(fixture.attemptId) }

        assertTrue(outcome.resolved)
        assertEquals("failed", attemptStatus(fixture.attemptId))
        assertEquals("quoted", purchaseStatus(fixture.purchaseId))
        assertEquals(0, grantCount(fixture.purchaseId))
    }

    @Test
    fun requeryingWhileStillPendingChangesNothingAndKeepsTheCustomerBlocked() {
        val fixture = unresolvedPayment()
        provider.answer("pending", fixture.amount)

        val outcome = asOperator(fixture) { adminPort.requeryPayment(fixture.attemptId) }

        assertFalse(outcome.resolved)
        assertEquals(
            "awaiting_payment",
            purchaseStatus(fixture.purchaseId),
            "an unresolved payment must keep the customer from paying a second time",
        )
        assertTrue(
            outcome.message.contains("Still in flight") || outcome.message.contains("cannot tell"),
            "the operator needs to be told there is nothing to do: ${outcome.message}",
        )
    }

    @Test
    fun requeryingIsRecordedEvenWhenItResolvesNothing() {
        val fixture = unresolvedPayment()
        provider.failQueriesWith(RuntimeException("provider unavailable"))

        asOperator(fixture) { adminPort.requeryPayment(fixture.attemptId) }
        asOperator(fixture) { adminPort.requeryPayment(fixture.attemptId) }

        assertEquals(
            2,
            resolutionCount(fixture.attemptId, "requeried"),
            "an item nobody has touched must be distinguishable from one chased twice",
        )
        assertEquals("reconciliation_required", attemptStatus(fixture.attemptId))
    }

    // ---- resolve: the exception, and its refusals ----

    @Test
    fun anOperatorConfirmationWithMatchingEvidenceSettlesThroughTheOrdinaryPath() {
        val fixture = unresolvedPayment()

        val outcome = asOperator(fixture) {
            adminPort.resolvePayment(
                fixture.attemptId,
                ResolvePaymentCommand(
                    resolution = ResolutionKind.CONFIRMED_PAID,
                    evidenceType = EvidenceType.SETTLEMENT_REPORT,
                    evidenceReference = "AZAM-SETTLE-2026-08-13-line-42",
                    observedAmount = fixture.amount,
                    observedCurrency = "TZS",
                    reason = "Present on the AzamPay settlement report for 13 August",
                ),
            )
        }

        assertTrue(outcome.resolved)
        assertEquals("confirmed", attemptStatus(fixture.attemptId))
        assertEquals("paid", purchaseStatus(fixture.purchaseId))

        runBlocking { settlementHandler.handle(settlementEvent(fixture)) }
        assertEquals(1, grantCount(fixture.purchaseId))
        assertEquals(1, receiptCount(fixture.purchaseId))
    }

    /**
     * The refusal that matters most. Misreading a line of a settlement report is easy, and
     * settling against the wrong figure grants a customer something they did not buy.
     */
    @Test
    fun aConfirmationWhoseAmountDisagreesWithThePaymentIsRefused() {
        val fixture = unresolvedPayment()

        val failure = assertFailsWith<IllegalArgumentException> {
            asOperator(fixture) {
                adminPort.resolvePayment(
                    fixture.attemptId,
                    ResolvePaymentCommand(
                        resolution = ResolutionKind.CONFIRMED_PAID,
                        evidenceType = EvidenceType.PROVIDER_PORTAL,
                        evidenceReference = "TXN-999",
                        observedAmount = BigDecimal("1.00"),
                        observedCurrency = "TZS",
                        reason = "Operator misread the settlement report line",
                    ),
                )
            }
        }

        assertTrue(failure.message.orEmpty().contains("does not match"), failure.message.orEmpty())
        assertEquals("reconciliation_required", attemptStatus(fixture.attemptId))
        assertEquals("awaiting_payment", purchaseStatus(fixture.purchaseId))
        assertEquals(0, grantCount(fixture.purchaseId))
    }

    @Test
    fun aConfirmationWithoutEvidenceIsRefused() {
        val fixture = unresolvedPayment()

        assertFailsWith<IllegalArgumentException> {
            asOperator(fixture) {
                adminPort.resolvePayment(
                    fixture.attemptId,
                    ResolvePaymentCommand(
                        resolution = ResolutionKind.CONFIRMED_PAID,
                        observedAmount = fixture.amount,
                        observedCurrency = "TZS",
                        reason = "Customer says they paid and sounded convincing",
                    ),
                )
            }
        }

        assertEquals("reconciliation_required", attemptStatus(fixture.attemptId))
        assertEquals(0, grantCount(fixture.purchaseId))
    }

    @Test
    fun abandoningRecordsTheDecisionButGrantsNothingAndUnblocksNothing() {
        val fixture = unresolvedPayment()

        val outcome = asOperator(fixture) {
            adminPort.resolvePayment(
                fixture.attemptId,
                ResolvePaymentCommand(
                    resolution = ResolutionKind.ABANDONED,
                    reason = "Provider cannot locate the transaction after three weeks",
                ),
            )
        }

        assertFalse(outcome.resolved)
        assertEquals("reconciliation_required", attemptStatus(fixture.attemptId))
        assertEquals(
            "awaiting_payment",
            purchaseStatus(fixture.purchaseId),
            "abandoning the chase does not make it safe to charge the customer again",
        )
        assertEquals(0, grantCount(fixture.purchaseId))
    }

    @Test
    fun aPaymentThatIsNotStuckCannotBeResolvedByHand() {
        val fixture = unresolvedPayment()
        provider.answer("success", fixture.amount)
        asOperator(fixture) { adminPort.requeryPayment(fixture.attemptId) }

        // Already settled by the provider's own answer.
        val failure = assertFailsWith<IllegalArgumentException> {
            asOperator(fixture) {
                adminPort.resolvePayment(
                    fixture.attemptId,
                    ResolvePaymentCommand(
                        resolution = ResolutionKind.CONFIRMED_PAID,
                        evidenceType = EvidenceType.PROVIDER_PORTAL,
                        evidenceReference = "TXN-1",
                        observedAmount = fixture.amount,
                        observedCurrency = "TZS",
                        reason = "Attempting to settle an already settled payment",
                    ),
                )
            }
        }
        assertTrue(failure.message.orEmpty().contains("does not need resolving"))
    }

    // ---- races: two ways of learning the same fact ----

    @Test
    fun anAutomaticPollThatSettlesFirstIsNotUndoneByALaterOperatorRequery() {
        val fixture = unresolvedPayment()
        provider.answer("success", fixture.amount)

        jdbcTemplate.update(
            "UPDATE peak_payment_attempts SET next_status_check_at = now() - interval '1 second' WHERE id = ?",
            fixture.attemptId,
        )
        reconciliation.reconcileDueAttempts(50)
        asOperator(fixture) { adminPort.requeryPayment(fixture.attemptId) }

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
        assertEquals(1, receiptCount(fixture.purchaseId))
    }

    @Test
    fun everyOperatorActionLeavesAnAuditRowNamingWhoDidIt() {
        val fixture = unresolvedPayment()

        asOperator(fixture) {
            adminPort.resolvePayment(
                fixture.attemptId,
                ResolvePaymentCommand(
                    resolution = ResolutionKind.CONFIRMED_FAILED,
                    reason = "Provider support confirmed the debit was reversed",
                ),
            )
        }

        val actor = jdbcTemplate.queryForObject(
            """
            SELECT resolved_by_platform_user_id FROM peak_reconciliation_resolutions
            WHERE payment_attempt_id = ?
            """.trimIndent(),
            UUID::class.java,
            fixture.attemptId,
        )
        assertEquals(
            fixture.platformUserId,
            actor,
            "a financial decision must name the person who made it",
        )
    }

    @Test
    fun anActionWithoutAPlatformOperatorIsRefused() {
        val fixture = unresolvedPayment()

        // No platform identity bound: a background job or a tenant session must not be able
        // to record an operator resolution.
        assertFailsWith<IllegalStateException> {
            requestContextHolder.set(
                RequestContext(
                    identity = RequestIdentity.Public(correlationId = "corr-not-an-operator"),
                    correlationId = "corr-not-an-operator",
                    idempotencyKey = null,
                    httpMethod = "POST",
                    requestPath = "/api/v1/platform/billing/reconciliation",
                ),
            )
            adminPort.resolvePayment(
                fixture.attemptId,
                ResolvePaymentCommand(
                    resolution = ResolutionKind.CONFIRMED_FAILED,
                    reason = "Should never be recorded without an operator",
                ),
            )
        }
        assertEquals(0, resolutionCount(fixture.attemptId, "confirmed_failed"))
    }

    private fun <T> asOperator(fixture: UnresolvedFixture, block: () -> T): T {
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Platform(
                    platformUserId = fixture.platformUserId,
                    correlationId = "corr-operator",
                ),
                correlationId = "corr-operator",
                idempotencyKey = null,
                httpMethod = "POST",
                requestPath = "/api/v1/platform/billing/reconciliation",
            ),
        )
        return try {
            block()
        } finally {
            requestContextHolder.clear()
        }
    }

    private fun attemptStatus(attemptId: UUID): String? =
        jdbcTemplate.queryForObject(
            "SELECT status FROM peak_payment_attempts WHERE id = ?",
            String::class.java,
            attemptId,
        )

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

    private fun receiptCount(purchaseId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM peak_receipts WHERE purchase_id = ?",
            Int::class.java,
            purchaseId,
        ) ?: 0

    private fun resolutionCount(attemptId: UUID, resolution: String): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM peak_reconciliation_resolutions
            WHERE payment_attempt_id = ? AND resolution = ?
            """.trimIndent(),
            Int::class.java,
            attemptId,
            resolution,
        ) ?: 0

    private fun settlementEvent(fixture: UnresolvedFixture): ClaimedOutboxEvent {
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
            correlationId = "corr-operator-settle",
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

    private fun unresolvedPayment(): UnresolvedFixture {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val purchaseId = UUID.randomUUID()
        val attemptId = UUID.randomUUID()
        val platformUserId = UUID.randomUUID()
        val amount = BigDecimal("30000.00")

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
            INSERT INTO platform_users (id, email, full_name, status)
            VALUES (?, ?, 'Billing Operator', 'active')
            """.trimIndent(),
            platformUserId,
            "operator-$platformUserId@peak.example",
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
                id, purchase_id, tenant_id, attempt_no, provider, payment_method,
                payer_msisdn, amount, currency, internal_reference, status
            ) VALUES (?, ?, ?, 1, 'stub_operator', 'mobile_money', '255700000001', ?, 'TZS', ?,
                      'reconciliation_required')
            """.trimIndent(),
            attemptId, purchaseId, tenantId, amount,
            "OPS-${UUID.randomUUID().toString().take(8)}".uppercase(),
        )

        return UnresolvedFixture(tenantId, purchaseId, attemptId, platformUserId, amount)
    }

    private data class UnresolvedFixture(
        val tenantId: UUID,
        val purchaseId: UUID,
        val attemptId: UUID,
        val platformUserId: UUID,
        val amount: BigDecimal,
    )

    class StubOperatorProvider : StatusQueryablePaymentProvider {
        override val providerCode = "stub_operator"

        private val status = AtomicReference("pending")
        private val amount = AtomicReference<BigDecimal?>(null)
        private val failure = AtomicReference<RuntimeException?>(null)

        fun reset() {
            status.set("pending")
            amount.set(null)
            failure.set(null)
        }

        fun answer(status: String, amount: BigDecimal) {
            this.status.set(status)
            this.amount.set(amount)
            this.failure.set(null)
        }

        fun failQueriesWith(ex: RuntimeException) = failure.set(ex)

        override fun initiate(command: ProviderCollectionCommand) = ProviderCollectionResult(
            providerReference = "STUB-${command.internalReference}",
            status = ProviderPaymentStatus.PENDING,
            providerStatus = "pending",
        )

        override fun queryStatus(command: ProviderStatusQuery): ProviderStatusResult {
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

        override fun parseWebhook(payload: String): ProviderWebhookNotification =
            throw UnsupportedOperationException("not used in these tests")

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
        fun stubOperatorProvider() = StubOperatorProvider()

    }
}
