package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.ProviderStatusQuery
import com.mwombeki.peak.shared.secrets.SecretReferenceResolver
import io.micrometer.core.instrument.MeterRegistry
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * Asks the provider what actually happened, for payments we never heard back about.
 *
 * Without this, a lost callback is indistinguishable from a failed payment, and Peak
 * resolves the ambiguity in the worst possible direction:
 *
 * ```
 *   customer's account debited
 *        -> callback lost
 *        -> Peak concludes the payment did not happen
 *        -> module never activates
 *        -> customer is invited to pay a second time
 * ```
 *
 * ## The distinction the whole class exists to preserve
 *
 * A provider that says "failed" has given an answer. A timeout, an HTTP 500, a DNS failure,
 * or an adapter that does not implement status queries have given no answer at all. The
 * first may fail the payment; the second must never. Everything unknown stays unknown,
 * holds the open-attempt slot so the customer cannot be charged twice, and is retried with
 * backoff until either the provider answers or an operator intervenes.
 *
 * Confirmation goes through [PaymentConfirmationService], the same path the webhook uses.
 * A second settlement implementation here would drift from that one.
 */
@Service
class PaymentStatusReconciliationService(
    private val jdbcTemplate: JdbcTemplate,
    private val confirmationService: PaymentConfirmationService,
    private val secretReferenceResolver: SecretReferenceResolver,
    private val properties: PlatformBillingProperties,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    adapters: List<PaymentProvider>,
) {
    private val log = LoggerFactory.getLogger(PaymentStatusReconciliationService::class.java)
    private val adaptersByCode = adapters.associateBy { it.providerCode }

    /** What the provider told us, with "no answer" as a first-class outcome. */
    enum class CollectionStatus {
        PENDING,
        SUCCEEDED,
        FAILED,
        CANCELLED,

        /**
         * We could not find out. A network error, an unparseable response, an adapter with
         * no status support, or a status string we do not recognise. Never treated as
         * failure — that is the bug this class exists to prevent.
         */
        UNKNOWN,
    }

    /**
     * Asks the provider about one attempt now, regardless of when it was next due.
     *
     * The operator-facing action. Deliberately the same code path as the sweep: an operator
     * pressing a button is impatience, not new authority, so it must not be able to reach a
     * conclusion the loop could not.
     */
    fun requeryAttempt(attemptId: UUID): CollectionStatus {
        val attempt = jdbcTemplate.query(
            """
            SELECT id, tenant_id, purchase_id, provider, internal_reference,
                   provider_reference, amount, currency, status_check_count
            FROM peak_payment_attempts
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                DueAttempt(
                    attemptId = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    purchaseId = rs.getObject("purchase_id", UUID::class.java),
                    provider = rs.getString("provider"),
                    internalReference = rs.getString("internal_reference"),
                    providerReference = rs.getString("provider_reference"),
                    amount = rs.getBigDecimal("amount"),
                    currency = rs.getString("currency").trim(),
                    checkCount = rs.getInt("status_check_count"),
                )
            },
            attemptId,
        ).firstOrNull() ?: throw IllegalArgumentException("Payment attempt was not found")

        val outcome = query(attempt)
        applyOutcome(attempt, outcome)
        return outcome.status
    }

    fun reconcileDueAttempts(limit: Int): Int {
        val due = jdbcTemplate.query(
            """
            SELECT attempt_id, tenant_id, purchase_id, provider, internal_reference,
                   provider_reference, amount, currency, status_check_count
            FROM platform_billing_attempts_due_for_status_check(?)
            """.trimIndent(),
            { rs, _ ->
                DueAttempt(
                    attemptId = rs.getObject("attempt_id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    purchaseId = rs.getObject("purchase_id", UUID::class.java),
                    provider = rs.getString("provider"),
                    internalReference = rs.getString("internal_reference"),
                    providerReference = rs.getString("provider_reference"),
                    amount = rs.getBigDecimal("amount"),
                    currency = rs.getString("currency").trim(),
                    checkCount = rs.getInt("status_check_count"),
                )
            },
            limit,
        )

        return due.count { attempt -> reconcile(attempt) }
    }

    private fun reconcile(attempt: DueAttempt): Boolean =
        applyOutcome(attempt, query(attempt))

    /**
     * What to do about an answer. Shared by the sweep and the operator requery so the two
     * cannot reach different conclusions from the same provider response.
     */
    private fun applyOutcome(attempt: DueAttempt, outcome: StatusOutcome): Boolean {
        meterRegistry.counter(
            "peak.platformbilling.status.query",
            "provider", attempt.provider,
            "result", outcome.status.name.lowercase(),
        ).increment()

        return when (outcome.status) {
            CollectionStatus.SUCCEEDED -> {
                // Amount is checked here as it is on the callback path: a figure that
                // disagrees means the reference is not about this purchase.
                if (!outcome.amountMatches(attempt.amount)) {
                    record(
                        attempt,
                        status = "reconciliation_required",
                        providerStatus = outcome.providerStatus,
                        error = "provider reported ${outcome.amount} against ${attempt.amount}",
                    )
                    log.error(
                        "Platform billing status query returned a mismatched amount for attempt {}",
                        attempt.attemptId,
                    )
                    return true
                }
                confirmationService.confirm(
                    PaymentConfirmationService.ConfirmedPayment(
                        tenantId = attempt.tenantId,
                        attemptId = attempt.attemptId,
                        purchaseId = attempt.purchaseId,
                        providerReference = outcome.providerReference ?: attempt.providerReference,
                        source = PaymentConfirmationService.ConfirmationSource.STATUS_QUERY,
                    ),
                )
                true
            }

            CollectionStatus.FAILED, CollectionStatus.CANCELLED -> {
                // An answer, so the purchase may safely become payable again.
                confirmationService.reject(
                    PaymentConfirmationService.RejectedPayment(
                        tenantId = attempt.tenantId,
                        attemptId = attempt.attemptId,
                        purchaseId = attempt.purchaseId,
                        failureCode = "provider_${outcome.status.name.lowercase()}",
                        failureDetail = outcome.providerStatus,
                        source = PaymentConfirmationService.ConfirmationSource.STATUS_QUERY,
                    ),
                )
                true
            }

            CollectionStatus.PENDING -> {
                // Still in flight. The customer may simply not have entered their PIN yet.
                record(attempt, status = null, providerStatus = outcome.providerStatus, error = null)
                false
            }

            CollectionStatus.UNKNOWN -> {
                // The important branch. Hold the slot, keep asking, tell nobody it failed.
                record(
                    attempt,
                    status = "reconciliation_required",
                    providerStatus = outcome.providerStatus,
                    error = outcome.error,
                )
                false
            }
        }
    }

    private fun query(attempt: DueAttempt): StatusOutcome {
        val adapter = adaptersByCode[attempt.provider]
            ?: return StatusOutcome.unknown("no adapter registered for ${attempt.provider}")

        return try {
            val result = adapter.queryStatus(
                ProviderStatusQuery(
                    internalReference = attempt.internalReference,
                    endpointUrl = properties.endpointUrl,
                    clientId = secretReferenceResolver.resolve(properties.clientIdSecretRef),
                    apiKey = secretReferenceResolver.resolve(properties.apiKeySecretRef),
                    checksumKey = secretReferenceResolver.resolve(properties.checksumKeySecretRef),
                ),
            )
            StatusOutcome(
                status = classify(result.status),
                providerStatus = result.providerStatus,
                providerReference = result.providerReference,
                amount = result.amount,
                error = null,
            )
        } catch (ex: UnsupportedOperationException) {
            // The adapter cannot ask. That is a deployment gap, not a failed payment.
            StatusOutcome.unknown("provider adapter does not support status queries")
        } catch (ex: Exception) {
            // A network error, a 500, an unparseable body. We learned nothing.
            log.warn("Platform billing status query failed for attempt {}", attempt.attemptId, ex)
            meterRegistry.counter(
                "peak.platformbilling.status.query.error",
                "provider", attempt.provider,
            ).increment()
            StatusOutcome.unknown(ex.message?.take(300) ?: ex.javaClass.simpleName)
        }
    }

    /**
     * Anything unrecognised is UNKNOWN rather than FAILED. A provider adding a new status
     * string must not cause Peak to start declaring payments failed that it cannot read.
     */
    private fun classify(status: String?): CollectionStatus {
        return when (status?.trim()?.lowercase()) {
            "succeeded", "success", "successful", "completed", "paid" -> CollectionStatus.SUCCEEDED
            "failed", "failure", "rejected", "declined" -> CollectionStatus.FAILED
            "cancelled", "canceled" -> CollectionStatus.CANCELLED
            "pending", "processing", "initiated", "in_progress" -> CollectionStatus.PENDING
            else -> CollectionStatus.UNKNOWN
        }
    }

    private fun record(
        attempt: DueAttempt,
        status: String?,
        providerStatus: String?,
        error: String?,
    ) {
        val nextCheck = nextCheckAt(attempt.checkCount + 1)
        jdbcTemplate.queryForObject(
            "SELECT platform_billing_record_status_check(?, ?, ?, ?, ?, ?)",
            Int::class.java,
            attempt.attemptId,
            status,
            providerStatus,
            null,
            error,
            nextCheck?.let { Timestamp.from(it) },
        )

        if (nextCheck == null) {
            meterRegistry.counter(
                "peak.platformbilling.reconciliation.abandoned",
                "provider", attempt.provider,
            ).increment()
            log.error(
                "Platform billing attempt {} for tenant {} could not be resolved after {} " +
                    "status queries and now needs an operator; see " +
                    "peak_payments_requiring_reconciliation",
                attempt.attemptId,
                attempt.tenantId,
                attempt.checkCount + 1,
            )
        }
    }

    /**
     * Backoff, so a provider is not hammered every two minutes forever.
     *
     * Returns null once automated recovery has been exhausted, which leaves the attempt in
     * `reconciliation_required` for an operator. It deliberately does not fail the payment:
     * after this many attempts we know less than ever, not more.
     */
    internal fun nextCheckAt(checkCount: Int, now: Instant = clock.instant()): Instant? {
        val delay = BACKOFF.getOrNull(checkCount - 1)
            ?: return if (checkCount <= MAX_CHECKS) now.plus(BACKOFF.last()) else null
        return now.plus(delay)
    }

    private data class DueAttempt(
        val attemptId: UUID,
        val tenantId: UUID,
        val purchaseId: UUID,
        val provider: String,
        val internalReference: String,
        val providerReference: String?,
        val amount: BigDecimal,
        val currency: String,
        val checkCount: Int,
    )

    private data class StatusOutcome(
        val status: CollectionStatus,
        val providerStatus: String?,
        val providerReference: String?,
        val amount: BigDecimal?,
        val error: String?,
    ) {
        fun amountMatches(expected: BigDecimal): Boolean =
            amount == null || amount.compareTo(expected) == 0

        companion object {
            fun unknown(reason: String) = StatusOutcome(
                status = CollectionStatus.UNKNOWN,
                providerStatus = null,
                providerReference = null,
                amount = null,
                error = reason,
            )
        }
    }

    internal companion object {
        /** Fast at first, because most answers arrive within a minute or two. */
        val BACKOFF: List<Duration> = listOf(
            Duration.ofSeconds(30),
            Duration.ofMinutes(1),
            Duration.ofMinutes(2),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofMinutes(30),
            Duration.ofHours(1),
            Duration.ofHours(2),
            Duration.ofHours(6),
            Duration.ofHours(12),
        )

        /** After this many, an operator is more likely to help than another query. */
        const val MAX_CHECKS = 20
    }
}
