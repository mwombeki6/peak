package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.ProviderWebhookNotification
import com.mwombeki.peak.platformbilling.api.PlatformBillingWebhookPort
import com.mwombeki.peak.platformbilling.api.PlatformBillingWebhookReceipt
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.secrets.SecretReferenceResolver
import java.math.BigDecimal
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Accepts a provider's confirmation that Peak has been paid.
 *
 * Three things have to be true before a callback is allowed to move money's worth of
 * entitlement, and they are checked in this order because each is cheaper than the last:
 * the signature verifies, the reference resolves to an attempt we started, and the amount
 * matches what that attempt asked for.
 *
 * Nothing is granted here. A verified callback records the fact and enqueues an outbox
 * event; the worker applies it. Settling inline would put entitlement writes on the
 * provider's retry path, so a slow grant would look like a failed callback and be retried.
 */
@Service
class PlatformBillingWebhookService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val paymentConfirmationService: PaymentConfirmationService,
    private val secretReferenceResolver: SecretReferenceResolver,
    private val properties: PlatformBillingProperties,
    adapters: List<PaymentProvider>,
) : PlatformBillingWebhookPort {

    private val adaptersByCode = adapters.associateBy { it.providerCode }

    override fun receive(
        providerCode: String,
        payload: String,
        headers: Map<String, String>,
    ): PlatformBillingWebhookReceipt {
        val adapter = adaptersByCode[providerCode.trim().lowercase()]
            ?: throw IllegalArgumentException("Unknown platform billing provider: $providerCode")

        val notification = adapter.verifyAndParseWebhook(
            payload = payload,
            checksumKey = secretReferenceResolver.resolve(properties.checksumKeySecretRef),
            checksumRequired = true,
            headers = headers,
        )

        val scope = resolveScope(notification.internalReference)
            ?: throw IllegalArgumentException(
                "Platform billing callback referenced an unknown attempt",
            )

        val originalContext = requestContextHolder.current()
        return try {
            requireNotNull(
                transactionTemplate.execute {
                    val boundContext = originalContext.copy(
                        identity = RequestIdentity.Public(
                            tenantId = scope.tenantId,
                            correlationId = originalContext.correlationId,
                        ),
                    )
                    requestContextHolder.set(boundContext)
                    databaseSessionContext.bind(boundContext.identity)

                    apply(adapter.providerCode, payload, notification, scope)
                },
            )
        } finally {
            requestContextHolder.set(originalContext)
        }
    }

    private fun apply(
        providerCode: String,
        payload: String,
        notification: ProviderWebhookNotification,
        scope: BillingScope,
    ): PlatformBillingWebhookReceipt {
        // The ledger insert is the replay defence, and it is deliberately the first write.
        // The unique index on (provider, provider_event_id) means a duplicate loses the
        // race here rather than after it has already confirmed an attempt twice.
        val recorded = recordEvent(providerCode, payload, notification, scope)
        if (!recorded) {
            return PlatformBillingWebhookReceipt(
                accepted = true,
                duplicate = true,
                attemptId = scope.attemptId,
            )
        }

        // An amount that disagrees with what we asked for is not a partial payment to be
        // reconciled later — it means the callback is about something else, or has been
        // tampered with. Record it and refuse rather than grant against the wrong figure.
        require(notification.amount.compareTo(scope.expectedAmount) == 0) {
            "Platform billing callback amount ${notification.amount.toPlainString()} does not " +
                "match the ${scope.expectedAmount.toPlainString()} that was requested"
        }
        require(notification.currency.equals(scope.expectedCurrency, ignoreCase = true)) {
            "Platform billing callback currency ${notification.currency} does not match " +
                scope.expectedCurrency
        }

        return when (notification.status) {
            "succeeded" -> confirm(notification, scope)
            "failed" -> fail(notification, scope)
            // Anything else is a progress ping. Recorded above, acted on by nothing.
            else -> PlatformBillingWebhookReceipt(
                accepted = true,
                duplicate = false,
                attemptId = scope.attemptId,
            )
        }
    }

    /**
     * Delegates to the shared settlement path rather than applying the payment itself.
     *
     * The status poller reaches the same method. Two settlement implementations would
     * drift, and the drift would stay invisible until a customer was settled by whichever
     * one had the bug.
     */
    private fun confirm(
        notification: ProviderWebhookNotification,
        scope: BillingScope,
    ): PlatformBillingWebhookReceipt {
        val applied = paymentConfirmationService.confirm(
            PaymentConfirmationService.ConfirmedPayment(
                tenantId = scope.tenantId,
                attemptId = scope.attemptId,
                purchaseId = scope.purchaseId,
                providerReference = notification.providerReference,
                source = PaymentConfirmationService.ConfirmationSource.WEBHOOK,
            ),
        )
        recordOutcome(scope.provider, notification.eventKey, "confirmed")
        return PlatformBillingWebhookReceipt(
            accepted = true,
            duplicate = !applied,
            attemptId = scope.attemptId,
        )
    }

    /**
     * The provider has told us the payment did not happen, which is an answer rather than a
     * silence, so the purchase may safely become payable again. The customer can try another
     * number or network; a failed push is not a failed order.
     */
    private fun fail(
        notification: ProviderWebhookNotification,
        scope: BillingScope,
    ): PlatformBillingWebhookReceipt {
        paymentConfirmationService.reject(
            PaymentConfirmationService.RejectedPayment(
                tenantId = scope.tenantId,
                attemptId = scope.attemptId,
                purchaseId = scope.purchaseId,
                failureCode = "provider_declined",
                failureDetail = notification.metadata["operator"]?.toString(),
                source = PaymentConfirmationService.ConfirmationSource.WEBHOOK,
            ),
        )
        recordOutcome(scope.provider, notification.eventKey, "failed")
        return PlatformBillingWebhookReceipt(true, duplicate = false, attemptId = scope.attemptId)
    }

    private fun recordOutcome(provider: String, eventKey: String, outcome: String) {
        jdbcTemplate.update(
            """
            UPDATE peak_provider_events SET outcome = ?, processed_at = now()
            WHERE provider = ? AND provider_event_id = ?
            """.trimIndent(),
            outcome,
            provider,
            eventKey,
        )
    }

    private fun recordEvent(
        providerCode: String,
        payload: String,
        notification: ProviderWebhookNotification,
        scope: BillingScope,
    ): Boolean {
        return try {
            jdbcTemplate.update(
                """
                INSERT INTO peak_provider_events (
                    provider, provider_event_id, tenant_id, attempt_id, payload,
                    signature_method, signature_verified
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """.trimIndent(),
                providerCode,
                notification.eventKey,
                scope.tenantId,
                scope.attemptId,
                payload,
                notification.checksumMethod,
                notification.checksumMethod != null,
            )
            true
        } catch (ex: DuplicateKeyException) {
            false
        }
    }

    private fun resolveScope(internalReference: String): BillingScope? {
        return jdbcTemplate.query(
            """
            SELECT tenant_id, attempt_id, purchase_id, attempt_status, purchase_status,
                   expected_amount, expected_currency, provider
            FROM resolve_platform_billing_scope(?)
            """.trimIndent(),
            { rs, _ ->
                BillingScope(
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    attemptId = rs.getObject("attempt_id", UUID::class.java),
                    purchaseId = rs.getObject("purchase_id", UUID::class.java),
                    attemptStatus = rs.getString("attempt_status"),
                    purchaseStatus = rs.getString("purchase_status"),
                    expectedAmount = rs.getBigDecimal("expected_amount"),
                    expectedCurrency = rs.getString("expected_currency").trim(),
                    provider = rs.getString("provider"),
                )
            },
            internalReference,
        ).firstOrNull()
    }

    private data class BillingScope(
        val tenantId: UUID,
        val attemptId: UUID,
        val purchaseId: UUID,
        val attemptStatus: String,
        val purchaseStatus: String,
        val expectedAmount: BigDecimal,
        val expectedCurrency: String,
        val provider: String,
    )
}
