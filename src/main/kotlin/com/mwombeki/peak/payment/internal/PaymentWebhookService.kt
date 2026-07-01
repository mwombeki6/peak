package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.billing.api.BillingPort
import com.mwombeki.peak.billing.api.ConfirmedPaymentRequest
import com.mwombeki.peak.payment.api.PaymentConflictException
import com.mwombeki.peak.payment.api.PaymentNotFoundException
import com.mwombeki.peak.payment.api.PaymentRejectedException
import com.mwombeki.peak.payment.api.PaymentWebhookPort
import com.mwombeki.peak.payment.api.PaymentWebhookReceipt
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.secrets.SecretReferenceResolver
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import io.micrometer.core.instrument.MeterRegistry
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Service
class PaymentWebhookService(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val requestContextHolder: RequestContextHolder,
    private val transactionTemplate: TransactionTemplate,
    private val secretResolver: SecretReferenceResolver,
    private val billingPort: BillingPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val objectMapper: ObjectMapper,
    adapters: List<PaymentProviderAdapter>,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) : PaymentWebhookPort {
    private val adaptersByCode = adapters.associateBy { it.providerCode }

    override fun receive(
        providerAccountId: UUID,
        providerEventId: String,
        timestamp: String,
        signature: String,
        payload: String,
    ): PaymentWebhookReceipt {
        meterRegistry.counter("peak.payment.webhook.received").increment()
        return try {
            receiveValidated(
                providerAccountId,
                providerEventId,
                timestamp,
                signature,
                payload,
            ).also { receipt ->
                meterRegistry.counter(
                    "peak.payment.webhook.processed",
                    "result",
                    "accepted",
                    "status",
                    receipt.status,
                ).increment()
            }
        } catch (ex: Exception) {
            meterRegistry.counter(
                "peak.payment.webhook.processed",
                "result",
                "rejected",
                "status",
                "rejected",
            ).increment()
            throw ex
        }
    }

    private fun receiveValidated(
        providerAccountId: UUID,
        providerEventId: String,
        timestamp: String,
        signature: String,
        payload: String,
    ): PaymentWebhookReceipt {
        require(PROVIDER_EVENT_ID.matches(providerEventId)) {
            "Provider event id is invalid"
        }
        require(payload.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Provider webhook payload exceeds the allowed size"
        }
        val receivedAt = timestamp.toLongOrNull()?.let(Instant::ofEpochSecond)
            ?: throw PaymentRejectedException("Provider timestamp is invalid")
        require(
            Duration.between(receivedAt, clock.instant()).abs() <= MAX_WEBHOOK_AGE,
        ) {
            "Provider webhook timestamp is outside the accepted replay window"
        }

        val originalContext = requestContextHolder.current()
        return try {
            requireNotNull(
                transactionTemplate.execute {
                val scope = resolveScope(providerAccountId)
                require(scope.active) {
                    "Payment provider account is inactive"
                }
                verifySignature(
                    timestamp = timestamp,
                    payload = payload,
                    suppliedSignature = signature,
                    secret = secretResolver.resolve(scope.webhookSecretRef),
                )
                val adapter = adaptersByCode[scope.providerCode]
                    ?: throw PaymentRejectedException(
                        "Payment provider callback is not supported",
                    )
                val notification = adapter.parseWebhook(payload)
                validateNotification(notification)

                val tenantContext = originalContext.copy(
                    identity = RequestIdentity.Public(
                        tenantId = scope.tenantId,
                        propertyId = scope.propertyId,
                        correlationId = originalContext.correlationId,
                    ),
                )
                requestContextHolder.set(tenantContext)
                databaseSessionContext.bind(tenantContext.identity)

                processNotification(
                    scope = scope,
                    providerAccountId = providerAccountId,
                    providerEventId = providerEventId,
                    notification = notification,
                )
                },
            )
        } finally {
            requestContextHolder.set(originalContext)
        }
    }

    private fun processNotification(
        scope: WebhookScope,
        providerAccountId: UUID,
        providerEventId: String,
        notification: ProviderWebhookNotification,
    ): PaymentWebhookReceipt {
        val eventId = UUID.randomUUID()
        val inserted = jdbcTemplate.update(
            """
            INSERT INTO payment_webhook_events (
                id, tenant_id, provider_account_id, provider_event_id,
                event_type, payload, status
            )
            VALUES (?, ?, ?, ?, 'collection.status', ?::jsonb, 'received')
            ON CONFLICT (tenant_id, provider_account_id, provider_event_id) DO NOTHING
            """.trimIndent(),
            eventId,
            scope.tenantId,
            providerAccountId,
            providerEventId,
            objectMapper.writeValueAsString(
                notification.metadata + mapOf(
                    "internalReference" to notification.internalReference,
                    "providerReference" to notification.providerReference,
                    "status" to notification.status,
                    "amount" to notification.amount,
                    "feeAmount" to notification.feeAmount,
                    "currency" to notification.currency,
                ),
            ),
        ) == 1

        val transaction = requireTransaction(
            scope.tenantId,
            providerAccountId,
            notification.internalReference,
        )
        if (!inserted) {
            return PaymentWebhookReceipt(
                providerEventId = providerEventId,
                transactionId = transaction.id,
                status = transaction.status,
                replayed = true,
            )
        }
        require(transaction.propertyId == scope.propertyId) {
            "Payment callback property does not match provider account"
        }
        require(transaction.amount.money() == notification.amount.money()) {
            "Payment callback amount does not match transaction"
        }
        require(transaction.currency == notification.currency) {
            "Payment callback currency does not match transaction"
        }
        require(
            transaction.providerReference == null ||
                    transaction.providerReference == notification.providerReference,
        ) {
            "Payment callback provider reference conflicts with transaction"
        }

        if (transaction.status in TERMINAL_STATUSES) {
            markEvent(eventId, scope.tenantId, "ignored", null)
            return PaymentWebhookReceipt(
                providerEventId = providerEventId,
                transactionId = transaction.id,
                status = transaction.status,
                replayed = true,
            )
        }

        val resultStatus = when (notification.status) {
            "confirmed" -> confirmPayment(
                scope = scope,
                eventId = eventId,
                transaction = transaction,
                notification = notification,
            )

            "failed" -> {
                jdbcTemplate.update(
                    """
                    UPDATE payment_transactions
                    SET webhook_event_id = ?,
                        provider_reference = ?,
                        status = 'failed',
                        failed_at = now(),
                        failure_reason = 'Provider reported collection failure',
                        updated_at = now()
                    WHERE tenant_id = ? AND id = ? AND status IN ('initiated', 'pending')
                    """.trimIndent(),
                    eventId,
                    notification.providerReference,
                    scope.tenantId,
                    transaction.id,
                )
                "failed"
            }

            else -> throw PaymentRejectedException(
                "Provider callback status is unsupported",
            )
        }

        markEvent(eventId, scope.tenantId, "processed", null)
        val payload = mapOf(
            "transactionId" to transaction.id,
            "providerAccountId" to providerAccountId,
            "status" to resultStatus,
            "amount" to notification.amount.money(),
        )
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = scope.tenantId,
                action = "payments.provider.callback.$resultStatus",
                resource = AuditResource("payment_transactions", transaction.id),
                after = payload,
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "payment_transactions",
                aggregateId = transaction.id,
                tenantId = scope.tenantId,
                propertyId = scope.propertyId,
                eventType = "payment.transaction.$resultStatus",
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                priority = 2,
            ),
        )
        return PaymentWebhookReceipt(
            providerEventId = providerEventId,
            transactionId = transaction.id,
            status = resultStatus,
            replayed = false,
        )
    }

    private fun confirmPayment(
        scope: WebhookScope,
        eventId: UUID,
        transaction: WebhookTransaction,
        notification: ProviderWebhookNotification,
    ): String {
        val propertyId = transaction.propertyId
            ?: throw PaymentConflictException("Payment transaction has no property")
        val folioId = transaction.folioId
            ?: throw PaymentConflictException("Payment transaction has no folio")
        jdbcTemplate.update(
            """
            UPDATE payment_transactions
            SET webhook_event_id = ?,
                provider_reference = ?,
                fee_amount = ?,
                status = 'confirmed',
                confirmed_at = now(),
                updated_at = now()
            WHERE tenant_id = ?
              AND id = ?
              AND status IN ('initiated', 'pending')
            """.trimIndent(),
            eventId,
            notification.providerReference,
            notification.feeAmount.money(),
            scope.tenantId,
            transaction.id,
        )
        val folioPaymentId = billingPort.postConfirmedPayment(
            tenantId = scope.tenantId,
            propertyId = propertyId,
            request = ConfirmedPaymentRequest(
                folioId = folioId,
                paymentMethod = "mobile_money",
                amount = notification.amount.money(),
                paymentTransactionId = transaction.id,
                processedBy = transaction.initiatedBy,
                referenceNumber = notification.providerReference,
                idempotencyKey = eventId.toString(),
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
            scope.tenantId,
            transaction.id,
        )
        return "confirmed"
    }

    private fun resolveScope(providerAccountId: UUID): WebhookScope {
        return jdbcTemplate.query(
            """
            SELECT tenant_id, property_id, provider_code,
                   webhook_secret_ref, account_active
            FROM resolve_payment_webhook_scope(?)
            """.trimIndent(),
            { rs, _ ->
                WebhookScope(
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    propertyId = rs.getObject("property_id", UUID::class.java),
                    providerCode = rs.getString("provider_code"),
                    webhookSecretRef = rs.getString("webhook_secret_ref"),
                    active = rs.getBoolean("account_active"),
                )
            },
            providerAccountId,
        ).singleOrNull() ?: throw PaymentNotFoundException(
            "Payment provider account was not found",
        )
    }

    private fun requireTransaction(
        tenantId: UUID,
        providerAccountId: UUID,
        internalReference: String,
    ): WebhookTransaction {
        return jdbcTemplate.query(
            """
            SELECT id, property_id, folio_id, initiated_by, amount, currency,
                   provider_reference, status
            FROM payment_transactions
            WHERE tenant_id = ?
              AND provider_account_id = ?
              AND internal_reference = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                WebhookTransaction(
                    id = rs.getObject("id", UUID::class.java),
                    propertyId = rs.getObject("property_id", UUID::class.java),
                    folioId = rs.getObject("folio_id", UUID::class.java),
                    initiatedBy = rs.getObject("initiated_by", UUID::class.java),
                    amount = rs.getBigDecimal("amount"),
                    currency = rs.getString("currency").trim(),
                    providerReference = rs.getString("provider_reference"),
                    status = rs.getString("status"),
                )
            },
            tenantId,
            providerAccountId,
            internalReference,
        ).singleOrNull() ?: throw PaymentNotFoundException(
            "Payment transaction was not found",
        )
    }

    private fun markEvent(
        eventId: UUID,
        tenantId: UUID,
        status: String,
        error: String?,
    ) {
        jdbcTemplate.update(
            """
            UPDATE payment_webhook_events
            SET status = ?,
                processed_at = now(),
                error_message = ?
            WHERE tenant_id = ? AND id = ?
            """.trimIndent(),
            status,
            error,
            tenantId,
            eventId,
        )
    }

    private fun validateNotification(notification: ProviderWebhookNotification) {
        require(INTERNAL_REFERENCE.matches(notification.internalReference)) {
            "Provider callback internal reference is invalid"
        }
        require(notification.providerReference.length in 3..200) {
            "Provider callback reference is invalid"
        }
        require(notification.amount > BigDecimal.ZERO) {
            "Provider callback amount must be positive"
        }
        require(notification.feeAmount >= BigDecimal.ZERO) {
            "Provider callback fee must not be negative"
        }
        require(notification.currency == "TZS") {
            "Provider callback currency must be TZS"
        }
    }

    private fun verifySignature(
        timestamp: String,
        payload: String,
        suppliedSignature: String,
        secret: String,
    ) {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM))
        val expected = mac.doFinal("$timestamp.$payload".toByteArray(StandardCharsets.UTF_8))
        val supplied = try {
            HexFormat.of().parseHex(suppliedSignature.trim().lowercase())
        } catch (ex: IllegalArgumentException) {
            throw PaymentRejectedException("Provider webhook signature is invalid")
        }
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw PaymentRejectedException("Provider webhook signature is invalid")
        }
    }

    private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

    private data class WebhookScope(
        val tenantId: UUID,
        val propertyId: UUID?,
        val providerCode: String,
        val webhookSecretRef: String?,
        val active: Boolean,
    )

    private data class WebhookTransaction(
        val id: UUID,
        val propertyId: UUID?,
        val folioId: UUID?,
        val initiatedBy: UUID?,
        val amount: BigDecimal,
        val currency: String,
        val providerReference: String?,
        val status: String,
    )

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        val MAX_WEBHOOK_AGE: Duration = Duration.ofMinutes(5)
        val PROVIDER_EVENT_ID = Regex("[A-Za-z0-9._:-]{3,200}")
        val INTERNAL_REFERENCE = Regex("PEAK-[A-F0-9]{20}")
        val TERMINAL_STATUSES = setOf("confirmed", "failed", "reversed", "cancelled")
    }
}
