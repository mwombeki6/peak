package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * The one place a payment is applied, whatever told us about it.
 *
 * ```
 *   signed callback ─────────┐
 *                            │
 *   provider status query ───┼──►  confirm(...)  ──►  attempt confirmed
 *                            │                        purchase paid
 *   operator reconciliation ─┘                        settlement enqueued
 * ```
 *
 * Deliberately not "the webhook settles, and the poller has its own version". Two settlement
 * implementations would drift, and the drift would stay invisible until the day a customer
 * was settled by whichever one had the bug. Everything that can learn a payment succeeded
 * funnels through here.
 *
 * Idempotent on the purchase's own status under a row lock: a purchase already paid is a
 * replay, not a second sale. The callback and the poller genuinely race — a provider that
 * answers a status query and then delivers its retry a second later hits both — so this is
 * load-bearing rather than defensive.
 */
@Service
class PaymentConfirmationService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val requestContextHolder: RequestContextHolder,
    private val outboxPort: OutboxPort,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(PaymentConfirmationService::class.java)

    /** How Peak came to believe the payment succeeded, kept so the two can be compared. */
    enum class ConfirmationSource(val label: String) {
        WEBHOOK("webhook"),
        STATUS_QUERY("status_query"),
        OPERATOR("operator"),
    }

    data class ConfirmedPayment(
        val tenantId: UUID,
        val attemptId: UUID,
        val purchaseId: UUID,
        val providerReference: String?,
        val source: ConfirmationSource,
    )

    data class RejectedPayment(
        val tenantId: UUID,
        val attemptId: UUID,
        val purchaseId: UUID,
        val failureCode: String,
        val failureDetail: String?,
        val source: ConfirmationSource,
    )

    /**
     * Applies a payment we now know succeeded. Returns false when it had already been
     * applied, which is the ordinary outcome of a callback and a poll agreeing.
     */
    fun confirm(command: ConfirmedPayment): Boolean {
        val applied = withRequestContext(command.tenantId, "confirm-${command.attemptId}") {
            requireNotNull(transactionTemplate.execute { applyConfirmation(command) })
        }

        meterRegistry.counter(
            if (applied) CONFIRMED_METRIC else REPLAYED_METRIC,
            "source",
            command.source.label,
        ).increment()

        if (applied) {
            log.info(
                "Confirmed platform billing payment purchase={} via {}",
                command.purchaseId,
                command.source.label,
            )
        }
        return applied
    }

    private fun applyConfirmation(command: ConfirmedPayment): Boolean {
        bind(command.tenantId, "confirm-${command.attemptId}")

        // FOR UPDATE, because the callback and the status poller can reach this row at the
        // same moment and both believe they are first.
        val status = jdbcTemplate.query(
            "SELECT status FROM peak_purchases WHERE id = ? AND tenant_id = ? FOR UPDATE",
            { rs, _ -> rs.getString("status") },
            command.purchaseId,
            command.tenantId,
        ).firstOrNull() ?: return false

        if (status == "paid") {
            return false
        }

        jdbcTemplate.update(
            """
            UPDATE peak_payment_attempts
            SET status = 'confirmed',
                provider_reference = coalesce(?, provider_reference),
                next_status_check_at = NULL,
                last_status_error = NULL,
                updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            command.providerReference,
            command.attemptId,
        )
        jdbcTemplate.update(
            "UPDATE peak_purchases SET status = 'paid', updated_at = now() WHERE id = ?",
            command.purchaseId,
        )

        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "peak_purchase",
                eventType = PURCHASE_PAID,
                destination = OutboxDestination.PLATFORM_BILLING,
                payload = mapOf(
                    "purchaseId" to command.purchaseId.toString(),
                    "tenantId" to command.tenantId.toString(),
                    "attemptId" to command.attemptId.toString(),
                    "confirmedBy" to command.source.label,
                ),
                aggregateId = command.purchaseId,
                tenantId = command.tenantId,
            ),
        )
        return true
    }

    /**
     * Records that the provider says the payment did not happen.
     *
     * Only ever called with an answer from the provider. A timeout, a network error or an
     * unsupported status query must never reach here — not knowing is not the same as
     * knowing it failed, and treating one as the other is what invites a second charge.
     */
    fun reject(command: RejectedPayment): Boolean {
        val applied = withRequestContext(command.tenantId, "reject-${command.attemptId}") {
            requireNotNull(transactionTemplate.execute { applyRejection(command) })
        }

        if (applied) {
            meterRegistry.counter(
                FAILED_METRIC,
                "source",
                command.source.label,
                "code",
                command.failureCode,
            ).increment()
        }
        return applied
    }

    private fun applyRejection(command: RejectedPayment): Boolean {
        bind(command.tenantId, "reject-${command.attemptId}")

        // Never overrule a confirmation. A late "failed" after a successful settlement is a
        // provider inconsistency, not a reason to take capability away from someone who paid.
        val updated = jdbcTemplate.update(
            """
            UPDATE peak_payment_attempts
            SET status = 'failed', failure_code = ?, failure_detail = ?,
                next_status_check_at = NULL, updated_at = now()
            WHERE id = ? AND status <> 'confirmed'
            """.trimIndent(),
            command.failureCode,
            command.failureDetail?.take(500),
            command.attemptId,
        )
        if (updated == 0) {
            return false
        }

        // Now, and only now, the purchase becomes payable again.
        jdbcTemplate.update(
            """
            UPDATE peak_purchases SET status = 'quoted', updated_at = now()
            WHERE id = ? AND status = 'awaiting_payment'
            """.trimIndent(),
            command.purchaseId,
        )
        return true
    }

    /**
     * The callback arrives with a request context; the status poller runs in a worker loop
     * and has none. Enqueuing an outbox event needs one, so this supplies a system context
     * when the caller has not brought its own and restores what was there afterwards.
     */
    private fun <T> withRequestContext(tenantId: UUID, correlationId: String, block: () -> T): T {
        if (requestContextHolder.currentOrNull() != null) {
            return block()
        }
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Public(
                    tenantId = tenantId,
                    correlationId = correlationId,
                ),
                correlationId = correlationId,
                idempotencyKey = null,
                httpMethod = "SYSTEM",
                requestPath = "platformbilling/payment-confirmation",
            ),
        )
        return try {
            block()
        } finally {
            requestContextHolder.clear()
        }
    }

    private fun bind(tenantId: UUID, correlationId: String) {
        databaseSessionContext.bind(
            RequestIdentity.Public(tenantId = tenantId, correlationId = correlationId),
        )
    }

    private companion object {
        const val PURCHASE_PAID = "platform.purchase.paid"
        const val CONFIRMED_METRIC = "peak.platformbilling.payment.confirmed"
        const val REPLAYED_METRIC = "peak.platformbilling.payment.confirmed.replayed"
        const val FAILED_METRIC = "peak.platformbilling.payment.failed"
    }
}
