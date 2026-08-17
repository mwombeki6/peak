package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.platformbilling.api.ReconciliationOutcome
import com.mwombeki.peak.platformbilling.api.ResolvePaymentCommand
import com.mwombeki.peak.platformbilling.api.ResolutionKind
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.math.BigDecimal
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * What an operator may do about a payment Peak could not resolve.
 *
 * ## Two actions, deliberately unequal
 *
 * **Requery** is the ordinary one and decides nothing. It asks the provider — the system
 * that actually knows — to answer again, and whatever comes back goes through exactly the
 * path the background sweep uses. An operator pressing a button is impatience, not new
 * authority.
 *
 * **Resolve** is the exception, for when the provider's API cannot answer but a human can
 * see the truth in a portal, a settlement report or a bank statement. It records an
 * *observation*, not a verdict on the database: the observation then enters
 * [PaymentConfirmationService], the same path a signed callback enters, so everything
 * already built around idempotency, grants, receipts and convergence keeps applying.
 *
 * What this deliberately is not is a `[Mark Paid]` button. There is no code path here that
 * writes a purchase, a grant, a subscription or a receipt. Letting support staff edit those
 * directly would let them manufacture financial truth, and would route around every
 * guarantee the settlement machinery provides.
 *
 * ## What this is not for
 *
 * Deciding not to collect a debt — a waiver, a credit, a complimentary extension, a write-off
 * — is a commercial decision, not a reconciliation. Routing one through CONFIRMED_PAID would
 * record revenue that never arrived and make the books fiction. It needs its own audited
 * workflow and does not belong here.
 */
@Service
class OperatorReconciliationService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val statusReconciliation: PaymentStatusReconciliationService,
    private val confirmationService: PaymentConfirmationService,
) {
    private val log = LoggerFactory.getLogger(OperatorReconciliationService::class.java)

    /**
     * Asks the provider again, now. Records that it was asked, so a queue item nobody has
     * touched is distinguishable from one that has been chased six times.
     */
    fun requery(attemptId: UUID): ReconciliationOutcome {
        val attempt = loadAttempt(attemptId)
        val status = statusReconciliation.requeryAttempt(attemptId)

        recordResolution(
            attempt = attempt,
            resolution = "requeried",
            reason = "Operator asked the provider again; it answered $status",
            command = null,
        )

        return ReconciliationOutcome(
            attemptId = attemptId,
            providerStatus = status.name,
            resolved = status == PaymentStatusReconciliationService.CollectionStatus.SUCCEEDED ||
                status == PaymentStatusReconciliationService.CollectionStatus.FAILED ||
                status == PaymentStatusReconciliationService.CollectionStatus.CANCELLED,
            message = when (status) {
                PaymentStatusReconciliationService.CollectionStatus.SUCCEEDED ->
                    "The provider confirms this was paid; it has been settled"
                PaymentStatusReconciliationService.CollectionStatus.FAILED,
                PaymentStatusReconciliationService.CollectionStatus.CANCELLED ->
                    "The provider confirms this did not complete; the customer may retry"
                PaymentStatusReconciliationService.CollectionStatus.PENDING ->
                    "Still in flight at the provider. Nothing to do yet."
                PaymentStatusReconciliationService.CollectionStatus.UNKNOWN ->
                    "The provider still cannot tell us. The payment remains unresolved and " +
                        "the customer is still blocked from paying again."
            },
        )
    }

    /**
     * Records what an operator has established from evidence outside the API.
     *
     * A confirmation must agree with what was asked for. An operator reading the wrong line
     * of a settlement report is exactly the mistake this catches, and settling against a
     * figure nobody checked is how a customer ends up granted something they did not buy.
     */
    fun resolve(attemptId: UUID, command: ResolvePaymentCommand): ReconciliationOutcome {
        val attempt = loadAttempt(attemptId)

        require(attempt.status == "reconciliation_required") {
            "This payment is ${attempt.status} and does not need resolving. Only a payment " +
                "whose outcome could not be determined may be resolved by hand."
        }

        if (command.resolution == ResolutionKind.CONFIRMED_PAID) {
            val observedAmount = requireNotNull(command.observedAmount) {
                "Confirming a payment requires the amount the evidence shows"
            }
            require(observedAmount.compareTo(attempt.amount) == 0) {
                "The evidence shows ${observedAmount.toPlainString()} but this payment was " +
                    "for ${attempt.amount.toPlainString()}. Settling against a figure that " +
                    "does not match would grant the customer something they did not buy."
            }
            require(
                command.observedCurrency?.equals(attempt.currency, ignoreCase = true) == true,
            ) {
                "The evidence is in ${command.observedCurrency} but this payment was in " +
                    attempt.currency
            }
            require(!command.evidenceReference.isNullOrBlank()) {
                "Confirming a payment requires a reference an auditor could follow — a " +
                    "provider transaction id, a settlement line, a bank reference"
            }
        }

        recordResolution(attempt, command.resolution.databaseValue, command.reason, command)

        return when (command.resolution) {
            ResolutionKind.CONFIRMED_PAID -> {
                // Into the ordinary settlement path. The operator supplied the observation;
                // the engine does what it always does with a confirmed payment.
                confirmationService.confirm(
                    PaymentConfirmationService.ConfirmedPayment(
                        tenantId = attempt.tenantId,
                        attemptId = attemptId,
                        purchaseId = attempt.purchaseId,
                        providerReference = command.providerReference ?: attempt.providerReference,
                        source = PaymentConfirmationService.ConfirmationSource.OPERATOR,
                    ),
                )
                log.warn(
                    "Payment {} settled on operator evidence ({}), not on a provider answer",
                    attemptId,
                    command.evidenceType,
                )
                ReconciliationOutcome(
                    attemptId = attemptId,
                    providerStatus = "operator_confirmed",
                    resolved = true,
                    message = "Recorded as paid on operator evidence and settled",
                )
            }

            ResolutionKind.CONFIRMED_FAILED -> {
                confirmationService.reject(
                    PaymentConfirmationService.RejectedPayment(
                        tenantId = attempt.tenantId,
                        attemptId = attemptId,
                        purchaseId = attempt.purchaseId,
                        failureCode = "operator_confirmed_failed",
                        failureDetail = command.reason,
                        source = PaymentConfirmationService.ConfirmationSource.OPERATOR,
                    ),
                )
                ReconciliationOutcome(
                    attemptId = attemptId,
                    providerStatus = "operator_failed",
                    resolved = true,
                    message = "Recorded as not paid; the customer may try again",
                )
            }

            ResolutionKind.ABANDONED -> {
                // Deliberately grants nothing and unblocks nothing. The attempt stays
                // unresolved and the customer stays protected from a second charge; this
                // only records that automated recovery has been given up on.
                ReconciliationOutcome(
                    attemptId = attemptId,
                    providerStatus = "abandoned",
                    resolved = false,
                    message = "Recorded as abandoned. The payment is still unresolved and the " +
                        "customer is still blocked from paying again, deliberately.",
                )
            }
        }
    }

    private fun recordResolution(
        attempt: AttemptUnderReview,
        resolution: String,
        reason: String,
        command: ResolvePaymentCommand?,
    ) {
        val platformUserId = (requestContextHolder.current().identity as? RequestIdentity.Platform)
            ?.platformUserId
            ?: throw IllegalStateException("Reconciliation actions require a platform operator")

        transactionTemplate.execute {
            jdbcTemplate.update(
                """
                INSERT INTO peak_reconciliation_resolutions (
                    payment_attempt_id, tenant_id, resolution, evidence_type,
                    evidence_reference, provider_reference, observed_amount,
                    observed_currency, reason, resolved_by_platform_user_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                attempt.attemptId,
                attempt.tenantId,
                resolution,
                command?.evidenceType?.databaseValue,
                command?.evidenceReference,
                command?.providerReference,
                command?.observedAmount,
                command?.observedCurrency,
                reason,
                platformUserId,
            )
        }
    }

    private fun loadAttempt(attemptId: UUID): AttemptUnderReview {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, purchase_id, status, amount, currency, provider_reference
            FROM peak_payment_attempts
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                AttemptUnderReview(
                    attemptId = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    purchaseId = rs.getObject("purchase_id", UUID::class.java),
                    status = rs.getString("status"),
                    amount = rs.getBigDecimal("amount"),
                    currency = rs.getString("currency").trim(),
                    providerReference = rs.getString("provider_reference"),
                )
            },
            attemptId,
        ).firstOrNull() ?: throw IllegalArgumentException("Payment attempt was not found")
    }

    private data class AttemptUnderReview(
        val attemptId: UUID,
        val tenantId: UUID,
        val purchaseId: UUID,
        val status: String,
        val amount: BigDecimal,
        val currency: String,
        val providerReference: String?,
    )
}
