package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.billing.api.BillingPort
import com.mwombeki.peak.billing.api.ConfirmedPaymentRequest
import com.mwombeki.peak.payment.api.PaymentConflictException
import com.mwombeki.peak.payment.api.PaymentNotFoundException
import com.mwombeki.peak.payment.api.PaymentRejectedException
import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.PaymentStatus
import com.mwombeki.peak.payment.api.ProviderPaymentStatus
import com.mwombeki.peak.payment.api.ProviderWebhookNotification
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
import java.sql.Timestamp
import java.util.HexFormat
import java.util.UUID
import io.micrometer.core.instrument.MeterRegistry
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.core.env.Environment
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
    private val confirmationService: GuestPaymentConfirmationService,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val objectMapper: ObjectMapper,
    adapters: List<PaymentProvider>,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    private val environment: Environment,
) : PaymentWebhookPort {
    private val adaptersByCode = adapters.associateBy { it.providerCode }

    override fun receive(
        providerAccountId: UUID,
        payload: String,
        headers: Map<String, String>,
    ): PaymentWebhookReceipt {
        meterRegistry.counter("peak.payment.webhook.received").increment()
        return try {
            receiveValidated(
                headers,
                providerAccountId,
                payload,
            ).also { receipt ->
                meterRegistry.counter(
                    "peak.payment.webhook.processed",
                    "result",
                    "accepted",
                    "status",
                    receipt.status.databaseValue,
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
        headers: Map<String, String>,
        providerAccountId: UUID,
        payload: String,
    ): PaymentWebhookReceipt {
        require(payload.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Provider webhook payload exceeds the allowed size"
        }

        val originalContext = requestContextHolder.current()
        return try {
            requireNotNull(
                transactionTemplate.execute {
                val scope = resolveScope(providerAccountId)
                require(scope.active) {
                    "Payment provider account is inactive"
                }
                // Any provider with a registered adapter, not ClickPesa alone. The gate
                // used to name one provider, which meant no second guest rail could ever
                // confirm a payment however complete its adapter was — a hotel connected to
                // anything else would watch collections sit pending until they were swept.
                //
                // The adapter is still what verifies; an account whose provider has none is
                // refused, because an unverifiable callback is an anonymous HTTP client
                // asserting it has paid a hotel.
                val adapter = adaptersByCode[scope.providerCode]
                    ?: throw PaymentRejectedException(
                        "No adapter is registered for ${scope.providerCode}, so its " +
                            "callbacks cannot be verified",
                    )
                val notification = try {
                    adapter.verifyAndParseWebhook(
                        payload = payload,
                        checksumKey = secretResolver.resolve(
                            scope.checksumKeySecretRef,
                        ),
                        checksumRequired = environment.activeProfiles
                            .contains("prod"),
                        // Some providers sign in a header rather than in the body; one that
                        // signs in the body ignores these.
                        headers = headers,
                    )
                } catch (ex: IllegalArgumentException) {
                    throw PaymentRejectedException(
                        ex.message ?: "${scope.providerCode} callback was rejected",
                    )
                }
                validateNotification(notification)
                require(
                    notification.merchantIdentity == null ||
                        notification.merchantIdentity == scope.clientId,
                ) {
                    "${scope.providerCode} callback names a different merchant than the " +
                        "account it was sent to"
                }
                notification.providerTimestamp?.let { providerTimestamp ->
                    require(
                        Duration.between(
                            providerTimestamp,
                            clock.instant(),
                        ).abs() <= MAX_WEBHOOK_AGE,
                    ) {
                        "${scope.providerCode} callback is outside the accepted " +
                            "replay window"
                    }
                }

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
                    notification = notification,
                    payloadHash = sha256(payload),
                )
                },
            )
        } catch (ex: Exception) {
            persistVerifiedRejection(
                providerAccountId = providerAccountId,
                payload = payload,
                payloadHash = sha256(payload),
                originalContext = originalContext,
                failureType = ex::class.simpleName ?: "WebhookRejected",
            )
            throw ex
        } finally {
            requestContextHolder.set(originalContext)
        }
    }

    private fun processNotification(
        scope: WebhookScope,
        providerAccountId: UUID,
        notification: ProviderWebhookNotification,
        payloadHash: String,
    ): PaymentWebhookReceipt {
        val eventId = UUID.randomUUID()
        val inserted = jdbcTemplate.update(
            """
            INSERT INTO payment_webhook_events (
                id, tenant_id, provider_account_id, provider_event_id,
                event_type, payload, payload_hash, event_key,
                provider_timestamp, checksum_method, processing_result, status
            )
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, 'received', 'received')
            ON CONFLICT (tenant_id, provider_account_id, provider_event_id) DO NOTHING
            """.trimIndent(),
            eventId,
            scope.tenantId,
            providerAccountId,
            notification.eventKey,
            notification.eventType,
            sanitizedPayload(notification),
            payloadHash,
            notification.eventKey,
            notification.providerTimestamp?.let(Timestamp::from),
            notification.checksumMethod,
        ) == 1

        val transaction = requireTransaction(
            scope.tenantId,
            providerAccountId,
            notification.internalReference,
        )
        if (!inserted) {
            jdbcTemplate.update(
                """
                UPDATE payment_webhook_events
                SET replay_count = replay_count + 1
                WHERE tenant_id = ?
                  AND provider_account_id = ?
                  AND provider_event_id = ?
                """.trimIndent(),
                scope.tenantId,
                providerAccountId,
                notification.eventKey,
            )
            return PaymentWebhookReceipt(
                providerEventId = notification.eventKey,
                transactionId = transaction.id,
                status = PaymentStatus.fromDatabase(transaction.status),
                replayed = true,
            )
        }
        require(transaction.propertyId == scope.propertyId) {
            "Payment callback property does not match provider account"
        }
        if (notification.status == ProviderPaymentStatus.SUCCEEDED) {
            require(transaction.amount.money() == notification.amount.money()) {
                "Payment callback amount does not match transaction"
            }
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
                providerEventId = notification.eventKey,
                transactionId = transaction.id,
                status = PaymentStatus.fromDatabase(transaction.status),
                replayed = true,
            )
        }

        val resultStatus = when (notification.status) {
            ProviderPaymentStatus.SUCCEEDED -> confirmPayment(
                scope = scope,
                providerAccountId = providerAccountId,
                eventId = eventId,
                transaction = transaction,
                notification = notification,
            )

            ProviderPaymentStatus.FAILED, ProviderPaymentStatus.CANCELLED -> {
                jdbcTemplate.update(
                    """
                    UPDATE payment_transactions
                    SET webhook_event_id = ?,
                        provider_reference = ?,
                        status = 'failed',
                        failed_at = now(),
                        failure_reason = 'Provider reported collection failure',
                        updated_at = now()
                    WHERE tenant_id = ?
                      AND id = ?
                      AND status IN ('created', 'initiated', 'pending')
                    """.trimIndent(),
                    eventId,
                    notification.providerReference,
                    scope.tenantId,
                    transaction.id,
                )
                "failed"
            }

            // A callback that does not settle the question is not an error and must not be
            // treated as one: the transaction stays in flight and the status query still runs.
            // Rejecting here would make a provider's progress notification look like an
            // attack, and 'unknown' would strand a payment the provider may yet confirm.
            ProviderPaymentStatus.PENDING, ProviderPaymentStatus.UNKNOWN -> "pending"
        }

        markEvent(eventId, scope.tenantId, "processed", null)
        val payload = mapOf(
            "transactionId" to transaction.id,
            "providerAccountId" to providerAccountId,
            "posOrderId" to transaction.posOrderId,
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
        if (transaction.posOrderId != null) {
            outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = "payment_transactions",
                    aggregateId = transaction.id,
                    tenantId = scope.tenantId,
                    propertyId = scope.propertyId,
                    eventType = "payment.transaction.$resultStatus",
                    destination = OutboxDestination.POS,
                    payload = payload,
                    priority = 1,
                ),
            )
        }
        return PaymentWebhookReceipt(
            providerEventId = notification.eventKey,
            transactionId = transaction.id,
            status = PaymentStatus.fromDatabase(resultStatus),
            replayed = false,
        )
    }

    private fun persistVerifiedRejection(
        providerAccountId: UUID,
        payload: String,
        payloadHash: String,
        originalContext: com.mwombeki.peak.shared.context.RequestContext,
        failureType: String,
    ) {
        try {
            transactionTemplate.executeWithoutResult {
                val scope = resolveScope(providerAccountId)
                if (!scope.active) {
                    return@executeWithoutResult
                }
                val adapter = adaptersByCode[scope.providerCode]
                    ?: return@executeWithoutResult
                val notification = adapter.verifyAndParseWebhook(
                    payload = payload,
                    checksumKey = secretResolver.resolve(
                        scope.checksumKeySecretRef,
                    ),
                    checksumRequired = environment.activeProfiles
                        .contains("prod"),
                )
                val tenantContext = originalContext.copy(
                    identity = RequestIdentity.Public(
                        tenantId = scope.tenantId,
                        propertyId = scope.propertyId,
                        correlationId = originalContext.correlationId,
                    ),
                )
                requestContextHolder.set(tenantContext)
                databaseSessionContext.bind(tenantContext.identity)
                jdbcTemplate.update(
                    """
                    INSERT INTO payment_webhook_events (
                        id, tenant_id, provider_account_id, provider_event_id,
                        event_type, payload, payload_hash, event_key,
                        provider_timestamp, checksum_method, processing_result,
                        status, processed_at, error_message
                    )
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, 'failed',
                            'failed', now(), ?)
                    ON CONFLICT (
                        tenant_id,
                        provider_account_id,
                        provider_event_id
                    ) DO NOTHING
                    """.trimIndent(),
                    UUID.randomUUID(),
                    scope.tenantId,
                    providerAccountId,
                    notification.eventKey,
                    notification.eventType,
                    sanitizedPayload(notification),
                    payloadHash,
                    notification.eventKey,
                    notification.providerTimestamp?.let(Timestamp::from),
                    notification.checksumMethod,
                    failureType.take(200),
                )
            }
        } catch (_: Exception) {
            // Invalid checksums and unavailable accounts are not trusted enough
            // to persist. The bounded rejection metric remains authoritative.
        } finally {
            requestContextHolder.set(originalContext)
        }
    }

    private fun sanitizedPayload(
        notification: ProviderWebhookNotification,
    ): String {
        return objectMapper.writeValueAsString(
            mapOf(
                "internalReference" to notification.internalReference,
                "providerReference" to notification.providerReference,
                "status" to notification.status,
                "amount" to notification.amount,
                "feeAmount" to notification.feeAmount,
                "currency" to notification.currency,
                "eventType" to notification.eventType,
            ),
        )
    }

    /**
     * Builds an observation and hands it to the shared path.
     *
     * The callback used to apply the payment itself, in a method the status query had its own
     * near-copy of. They had already drifted — fee recorded on one side only, the status
     * sweep cleared on the other, different idempotency keys for the same folio posting.
     */
    private fun confirmPayment(
        scope: WebhookScope,
        providerAccountId: UUID,
        eventId: UUID,
        transaction: WebhookTransaction,
        notification: ProviderWebhookNotification,
    ): String {
        val propertyId = transaction.propertyId
            ?: throw PaymentConflictException("Payment transaction has no property")

        confirmationService.confirm(
            ProviderPaymentObservation(
                tenantId = scope.tenantId,
                propertyId = propertyId,
                transactionId = transaction.id,
                providerAccountId = providerAccountId,
                internalReference = notification.internalReference,
                provider = scope.providerCode,
                status = ProviderPaymentObservation.CanonicalStatus.SUCCEEDED,
                providerReference = notification.providerReference,
                providerStatus = notification.providerStatus,
                amount = notification.amount.money(),
                currency = notification.currency,
                feeAmount = notification.feeAmount.money(),
                folioId = transaction.folioId,
                posOrderId = transaction.posOrderId,
                initiatedBy = transaction.initiatedBy,
                source = ProviderPaymentObservation.ObservationSource.WEBHOOK,
                webhookEventId = eventId,
            ),
        )
        return "posted"
    }

    private fun resolveScope(providerAccountId: UUID): WebhookScope {
        return jdbcTemplate.query(
            """
            SELECT tenant_id, property_id, provider_code,
                   checksum_key_secret_ref, client_id, account_active
            FROM resolve_payment_webhook_scope(?)
            """.trimIndent(),
            { rs, _ ->
                WebhookScope(
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    propertyId = rs.getObject("property_id", UUID::class.java),
                    providerCode = rs.getString("provider_code"),
                    checksumKeySecretRef = rs.getString(
                        "checksum_key_secret_ref",
                    ),
                    clientId = rs.getString("client_id"),
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
            SELECT id, property_id, folio_id, pos_order_id, initiated_by, amount, currency,
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
                    posOrderId = rs.getObject("pos_order_id", UUID::class.java),
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
                processing_result = ?,
                processed_at = now(),
                error_message = ?
            WHERE tenant_id = ? AND id = ?
            """.trimIndent(),
            status,
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
        // Deliberately no check on eventType. It used to be required to be one of ClickPesa's
        // two event names, which meant every Snippe and AzamPay callback was rejected here —
        // after its signature had verified correctly — and a hotel on either rail watched its
        // collections sit pending forever. Event names are one vocabulary per provider;
        // reducing them to an outcome is the adapter's job, and `status` is that outcome.
        require(
            notification.status != ProviderPaymentStatus.SUCCEEDED ||
                notification.amount > BigDecimal.ZERO,
        ) {
            "Provider callback reports a successful payment of ${notification.amount}"
        }
        require(notification.amount >= BigDecimal.ZERO) {
            "Provider callback amount is invalid"
        }
        require(notification.feeAmount >= BigDecimal.ZERO) {
            "Provider callback fee must not be negative"
        }
        require(notification.currency == "TZS") {
            "Provider callback currency must be TZS"
        }
        require(notification.providerTimestamp != null) {
            "Provider callback timestamp is required"
        }
    }

    private fun sha256(payload: String): String {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(
                payload.toByteArray(StandardCharsets.UTF_8),
            ),
        )
    }

    private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

    private data class WebhookScope(
        val tenantId: UUID,
        val propertyId: UUID?,
        val providerCode: String,
        val checksumKeySecretRef: String,
        val clientId: String,
        val active: Boolean,
    )

    private data class WebhookTransaction(
        val id: UUID,
        val propertyId: UUID?,
        val folioId: UUID?,
        val posOrderId: UUID?,
        val initiatedBy: UUID?,
        val amount: BigDecimal,
        val currency: String,
        val providerReference: String?,
        val status: String,
    )

    private companion object {
        const val CLICKPESA_PROVIDER = "clickpesa"
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        val MAX_WEBHOOK_AGE: Duration = Duration.ofMinutes(5)
        val INTERNAL_REFERENCE = Regex("PEAK-[A-F0-9]{20}")
        val TERMINAL_STATUSES = setOf(
            "posted",
            "failed",
            "expired",
            "reversed",
            "partially_refunded",
            "refunded",
            "reconciled",
        )
    }
}
