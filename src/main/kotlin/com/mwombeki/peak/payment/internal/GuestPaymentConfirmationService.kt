package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.billing.api.BillingPort
import com.mwombeki.peak.billing.api.ConfirmedPaymentRequest
import java.math.BigDecimal
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * What Peak learned about a guest payment, in one shape whatever told it.
 *
 * A signed callback and a status query answer the same question and used to be applied by
 * two separate implementations. They had already drifted: the callback recorded `fee_amount`
 * and the status query did not; the status query cleared `next_status_check_at` and the
 * callback did not; and the two posted the folio payment under different idempotency keys.
 * None of that was visible from either method alone, which is how this kind of drift
 * survives.
 */
data class ProviderPaymentObservation(
    val tenantId: UUID,
    val propertyId: UUID,
    val transactionId: UUID,
    /** The account the callback was addressed to, resolved from a trusted route. */
    val providerAccountId: UUID,
    /** Peak's own reference, so a mismatched observation cannot be applied to a payment. */
    val internalReference: String,
    val provider: String,
    /** What the observation means, reduced to the five outcomes the domain acts on. */
    val status: CanonicalStatus,
    /** The provider's own identifier, where it gave one. */
    val providerReference: String?,
    /** The provider's raw status string, kept for evidence rather than for decisions. */
    val providerStatus: String?,
    val amount: BigDecimal,
    val currency: String,
    val feeAmount: BigDecimal = BigDecimal.ZERO,
    val folioId: UUID?,
    val posOrderId: UUID?,
    val initiatedBy: UUID?,
    val source: ObservationSource,
    /** Set only where the source is a callback, so the event can be linked to the payment. */
    val webhookEventId: UUID? = null,
) {
    /**
     * Deliberately small. Providers use dozens of words for these five states, and mapping
     * each provider's vocabulary is the adapter's job — past this boundary the payment domain
     * must never ask which PSP an observation came from.
     */
    enum class CanonicalStatus {
        PENDING,
        SUCCEEDED,
        FAILED,
        CANCELLED,

        /**
         * Peak could not find out. A timeout, an unreadable response, a status string nobody
         * recognises. Never treated as failure: not knowing is not knowing.
         */
        UNKNOWN,
    }

    enum class ObservationSource(val label: String) {
        WEBHOOK("webhook"),
        STATUS_QUERY("status_query"),
        OPERATOR("operator"),
    }
}

/**
 * The one place a guest payment is applied, whatever told Peak about it.
 *
 * ```
 * signed callback ──────┐
 *                       │
 * provider status query ┼──►  confirm()  ──►  transaction posted
 *                       │                     folio payment posted, once
 * operator resolution ──┘
 * ```
 *
 * Idempotency is a compare-and-set on the transaction's own status, matching only a row that
 * is still in flight, so whichever source arrives first wins and the second changes
 * nothing. That is what makes it safe for a callback and a status query to race, which they
 * do — a provider that answers a query and then delivers its retry a second later hits both.
 *
 * The folio posting key is derived from the transaction rather than from whatever observed
 * it. Keying on the source meant the two paths presented different keys for the same
 * payment, so the deduplication in `postConfirmedPayment` could not have caught a double
 * post if the compare-and-set had ever failed to.
 */
@Service
class GuestPaymentConfirmationService(
    private val jdbcTemplate: JdbcTemplate,
    private val billingPort: BillingPort,
) {
    private val log = LoggerFactory.getLogger(GuestPaymentConfirmationService::class.java)

    /**
     * Applies a payment now known to have succeeded. Returns false when another source had
     * already applied it, which is the ordinary outcome of a callback and a poll agreeing.
     */
    fun confirm(observation: ProviderPaymentObservation): Boolean {
        require(observation.status == ProviderPaymentObservation.CanonicalStatus.SUCCEEDED) {
            "Only a succeeded observation may confirm a payment, not ${observation.status}"
        }
        require((observation.folioId == null) != (observation.posOrderId == null)) {
            "A payment must target exactly one folio or POS order"
        }
        requireBinding(observation)

        // Compare-and-set. Only a transaction still in flight transitions, so a second
        // observation of the same payment does nothing rather than posting again.
        val applied = jdbcTemplate.update(
            """
            UPDATE payment_transactions
            SET status = 'posted',
                provider_reference = COALESCE(?, provider_reference),
                provider_status = COALESCE(?, provider_status),
                fee_amount = COALESCE(?, fee_amount),
                webhook_event_id = COALESCE(?, webhook_event_id),
                posted_at = now(),
                confirmed_at = now(),
                last_status_check_at = now(),
                next_status_check_at = NULL,
                updated_at = now()
            WHERE tenant_id = ?
              AND id = ?
              AND status IN ('created', 'initiated', 'pending')
            """.trimIndent(),
            observation.providerReference,
            observation.providerStatus,
            observation.feeAmount.takeIf { it > BigDecimal.ZERO },
            observation.webhookEventId,
            observation.tenantId,
            observation.transactionId,
        ) == 1

        if (!applied) {
            return false
        }

        observation.folioId?.let { folioId ->
            val folioPaymentId = billingPort.postConfirmedPayment(
                tenantId = observation.tenantId,
                propertyId = observation.propertyId,
                request = ConfirmedPaymentRequest(
                    folioId = folioId,
                    paymentMethod = "mobile_money",
                    amount = observation.amount,
                    paymentTransactionId = observation.transactionId,
                    processedBy = observation.initiatedBy,
                    referenceNumber = observation.providerReference,
                    // Keyed on the payment, not on what observed it, so the two sources
                    // present the same key rather than two.
                    idempotencyKey = "collection:${observation.transactionId}",
                ),
                idempotencyKeyId = null,
            )
            jdbcTemplate.update(
                """
                UPDATE payment_transactions
                SET folio_payment_id = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """.trimIndent(),
                folioPaymentId,
                observation.tenantId,
                observation.transactionId,
            )
        }

        log.info(
            "Confirmed guest payment transaction={} provider={} via {}",
            observation.transactionId,
            observation.provider,
            observation.source.label,
        )
        return true
    }

    /**
     * Every binding must agree before money moves.
     *
     * The security invariant this protects is that one hotel's merchant context cannot
     * confirm another's payment. A group holds several properties under one tenant, so
     * matching on tenant alone would let a callback addressed to one hotel's account settle
     * a transaction belonging to its sibling — and both callers would look correct in
     * isolation.
     *
     * The provider never chooses its own trust context: the account comes from the route,
     * the transaction's own row says which account and property it belongs to, and this
     * refuses if they disagree.
     */
    private fun requireBinding(observation: ProviderPaymentObservation) {
        val bound = jdbcTemplate.query(
            """
            SELECT provider_account_id, property_id, internal_reference, amount, currency
            FROM payment_transactions
            WHERE tenant_id = ? AND id = ?
            """.trimIndent(),
            { rs, _ ->
                TransactionBinding(
                    providerAccountId = rs.getObject("provider_account_id", UUID::class.java),
                    propertyId = rs.getObject("property_id", UUID::class.java),
                    internalReference = rs.getString("internal_reference"),
                    amount = rs.getBigDecimal("amount"),
                    currency = rs.getString("currency")?.trim(),
                )
            },
            observation.tenantId,
            observation.transactionId,
        ).firstOrNull()

        requireNotNull(bound) { "Payment transaction was not found for this tenant" }
        require(bound.providerAccountId == observation.providerAccountId) {
            "This observation was addressed to a different merchant account than the " +
                "payment it claims to settle"
        }
        require(bound.propertyId == observation.propertyId) {
            "This observation belongs to a different property than the payment it claims " +
                "to settle"
        }
        require(bound.internalReference == observation.internalReference) {
            "This observation names a different payment reference"
        }
        require(bound.amount.compareTo(observation.amount) == 0) {
            "Observed ${observation.amount.toPlainString()} against a payment for " +
                bound.amount.toPlainString()
        }
        require(bound.currency.equals(observation.currency, ignoreCase = true)) {
            "Observed ${observation.currency} against a payment in ${bound.currency}"
        }
    }

    private data class TransactionBinding(
        val providerAccountId: UUID?,
        val propertyId: UUID?,
        val internalReference: String,
        val amount: BigDecimal,
        val currency: String?,
    )

    /**
     * Records that the provider says the payment did not happen.
     *
     * Only ever called with an answer from the provider. A timeout or a network error must
     * never reach here: not knowing is not the same as knowing it failed, and treating one
     * as the other is how a guest is asked to pay twice.
     */
    fun reject(observation: ProviderPaymentObservation, reason: String): Boolean {
        return jdbcTemplate.update(
            """
            UPDATE payment_transactions
            SET status = 'failed',
                provider_reference = COALESCE(?, provider_reference),
                provider_status = COALESCE(?, provider_status),
                webhook_event_id = COALESCE(?, webhook_event_id),
                failed_at = now(),
                failure_reason = ?,
                last_status_check_at = now(),
                next_status_check_at = NULL,
                updated_at = now()
            WHERE tenant_id = ?
              AND id = ?
              AND status IN ('created', 'initiated', 'pending')
            """.trimIndent(),
            observation.providerReference,
            observation.providerStatus,
            observation.webhookEventId,
            reason,
            observation.tenantId,
            observation.transactionId,
        ) == 1
    }
}
