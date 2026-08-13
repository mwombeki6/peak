package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.billing.api.BillingPort
import com.mwombeki.peak.billing.api.ConfirmedPaymentRequest
import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.ProviderStatusQuery
import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.secrets.SecretReferenceResolver
import io.micrometer.core.instrument.MeterRegistry
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class PaymentStatusOutboxHandler(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val transactionTemplate: TransactionTemplate,
    private val secretResolver: SecretReferenceResolver,
    private val confirmationService: GuestPaymentConfirmationService,
    private val outboxPort: OutboxPort,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    providers: List<PaymentProvider>,
) : OutboxEventHandler {
    override val destination = OutboxDestination.PAYMENT
    private val providersByCode = providers.associateBy(PaymentProvider::providerCode)

    override fun supports(event: ClaimedOutboxEvent): Boolean {
        return event.destination == destination &&
                event.eventType == PAYMENT_STATUS_CHECK_REQUESTED
    }

    override suspend fun handle(event: ClaimedOutboxEvent) {
        val tenantId = requireNotNull(event.tenantId)
        val propertyId = requireNotNull(event.propertyId)
        val transactionId = requireNotNull(event.aggregateId)
        val identity = RequestIdentity.Public(
            tenantId = tenantId,
            propertyId = propertyId,
            correlationId = event.correlationId.toString(),
        )
        val work = transactionTemplate.execute {
            databaseSessionContext.bind(identity)
            loadWork(tenantId, propertyId, transactionId)
        } ?: return
        if (work.status !in setOf("created", "initiated", "pending")) {
            return
        }
        if (!clock.instant().isBefore(work.expiresAt)) {
            transactionTemplate.executeWithoutResult {
                databaseSessionContext.bind(identity)
                jdbcTemplate.update(
                    """
                    UPDATE payment_transactions
                    SET status = 'expired',
                        expired_at = now(),
                        next_status_check_at = NULL,
                        updated_at = now()
                    WHERE tenant_id = ?
                      AND id = ?
                      AND status IN ('created', 'initiated', 'pending')
                    """.trimIndent(),
                    tenantId,
                    transactionId,
                )
            }
            return
        }
        val provider = providersByCode[work.providerCode]
            ?: error("No payment provider for ${work.providerCode}")
        val result = provider.queryStatus(
            ProviderStatusQuery(
                internalReference = work.internalReference,
                endpointUrl = work.endpointUrl,
                clientId = work.clientId,
                apiKey = secretResolver.resolve(work.apiKeySecretRef),
                checksumKey = secretResolver.resolve(work.checksumKeySecretRef),
            ),
        )
        require(result.internalReference == work.internalReference) {
            "Provider status order reference does not match transaction"
        }
        require(result.clientId == null || result.clientId == work.clientId) {
            "Provider status client identity does not match account"
        }
        result.amount?.let {
            require(it.money() == work.amount.money()) {
                "Provider status amount does not match transaction"
            }
        }
        result.currency?.let {
            require(it.uppercase() == work.currency) {
                "Provider status currency does not match transaction"
            }
        }
        require(
            work.providerReference == null ||
                    result.providerReference == null ||
                    work.providerReference == result.providerReference,
        ) {
            "Provider status reference conflicts with transaction"
        }
        transactionTemplate.executeWithoutResult {
            databaseSessionContext.bind(identity)
            when (result.status) {
                "posted" -> postPayment(tenantId, propertyId, work, result)
                "failed" -> jdbcTemplate.update(
                    """
                    UPDATE payment_transactions
                    SET provider_status = ?,
                        provider_reference = COALESCE(?, provider_reference),
                        status = 'failed',
                        failed_at = now(),
                        last_status_check_at = now(),
                        next_status_check_at = NULL,
                        failure_reason = 'Provider status query reported failure',
                        updated_at = now()
                    WHERE tenant_id = ?
                      AND id = ?
                      AND status IN ('created', 'initiated', 'pending')
                    """.trimIndent(),
                    result.providerStatus,
                    result.providerReference,
                    tenantId,
                    transactionId,
                )
                "pending", "initiated" -> scheduleNext(
                    event,
                    work,
                    result.providerStatus,
                )
                else -> error("Unsupported provider status ${result.status}")
            }
        }
        meterRegistry.counter(
            "peak.payment.status_poll",
            "provider",
            work.providerCode,
            "result",
            result.status,
        ).increment()
    }

    /**
     * Builds an observation and hands it to the shared path, exactly as the callback does.
     *
     * This used to apply the payment itself, in a near-copy of the callback's version. The
     * copies had drifted: no fee recorded here, a different idempotency key for the same
     * folio posting, and a different set of columns cleared.
     */
    private fun postPayment(
        tenantId: UUID,
        propertyId: UUID,
        work: StatusWork,
        result: com.mwombeki.peak.payment.api.ProviderStatusResult,
    ) {
        val applied = confirmationService.confirm(
            ProviderPaymentObservation(
                tenantId = tenantId,
                propertyId = propertyId,
                transactionId = work.transactionId,
                providerAccountId = work.providerAccountId,
                internalReference = work.internalReference,
                provider = work.providerCode,
                status = ProviderPaymentObservation.CanonicalStatus.SUCCEEDED,
                providerReference = result.providerReference ?: work.providerReference,
                providerStatus = result.providerStatus,
                // Peak's own figure, not the provider's. The webhook path compares the two
                // before it gets here; a status query that disagreed would have to be
                // reconciled rather than silently believed.
                amount = work.amount,
                currency = work.currency,
                folioId = work.folioId,
                posOrderId = work.posOrderId,
                initiatedBy = work.initiatedBy,
                source = ProviderPaymentObservation.ObservationSource.STATUS_QUERY,
            ),
        )
        if (!applied) {
            // Another source had already applied it — the ordinary outcome of a callback and
            // a poll agreeing.
            return
        }

        val payload = mapOf(
            "transactionId" to work.transactionId,
            "posOrderId" to work.posOrderId,
            "status" to "posted",
            "amount" to work.amount,
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "payment_transactions",
                aggregateId = work.transactionId,
                tenantId = tenantId,
                propertyId = propertyId,
                eventType = "payment.transaction.posted",
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                priority = 2,
            ),
        )
        if (work.posOrderId != null) {
            outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = "payment_transactions",
                    aggregateId = work.transactionId,
                    tenantId = tenantId,
                    propertyId = propertyId,
                    eventType = "payment.transaction.posted",
                    destination = OutboxDestination.POS,
                    payload = payload,
                    priority = 1,
                ),
            )
        }
    }

    private fun scheduleNext(
        event: ClaimedOutboxEvent,
        work: StatusWork,
        providerStatus: String,
    ) {
        val next = clock.instant().plusSeconds(30)
        jdbcTemplate.update(
            """
            UPDATE payment_transactions
            SET provider_status = ?,
                status = 'pending',
                last_status_check_at = now(),
                next_status_check_at = ?,
                updated_at = now()
            WHERE tenant_id = ?
              AND id = ?
              AND status IN ('created', 'initiated', 'pending')
            """.trimIndent(),
            providerStatus,
            Timestamp.from(next),
            work.tenantId,
            work.transactionId,
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "payment_transactions",
                aggregateId = work.transactionId,
                tenantId = work.tenantId,
                propertyId = work.propertyId,
                eventType = PAYMENT_STATUS_CHECK_REQUESTED,
                destination = OutboxDestination.PAYMENT,
                payload = mapOf("transactionId" to work.transactionId),
                priority = 3,
                maxAttempts = event.maxAttempts,
                availableAt = next,
            ),
        )
    }

    private fun loadWork(
        tenantId: UUID,
        propertyId: UUID,
        transactionId: UUID,
    ): StatusWork? {
        return jdbcTemplate.query(
            """
            SELECT pt.id, pt.tenant_id, pt.property_id, pt.folio_id,
                   pt.pos_order_id, pt.initiated_by, pt.provider_account_id,
                   pt.internal_reference,
                   pt.provider_reference, pt.amount, trim(pt.currency) AS currency,
                   pt.status, pt.expires_at, pp.provider_code, ppa.endpoint_url,
                   ppa.client_id, ppa.api_key_secret_ref,
                   ppa.checksum_key_secret_ref
            FROM payment_transactions pt
            JOIN payment_provider_accounts ppa
              ON ppa.tenant_id = pt.tenant_id
             AND ppa.id = pt.provider_account_id
             AND ppa.is_active = true
            JOIN payment_providers pp
              ON pp.tenant_id = ppa.tenant_id
             AND pp.id = ppa.provider_id
             AND pp.is_active = true
            WHERE pt.tenant_id = ?
              AND pt.property_id = ?
              AND pt.id = ?
            FOR UPDATE OF pt
            """.trimIndent(),
            { rs, _ ->
                StatusWork(
                    transactionId = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    propertyId = rs.getObject("property_id", UUID::class.java),
                    folioId = rs.getObject("folio_id", UUID::class.java),
                    posOrderId = rs.getObject("pos_order_id", UUID::class.java),
                    initiatedBy = rs.getObject("initiated_by", UUID::class.java),
                    providerAccountId = rs.getObject("provider_account_id", UUID::class.java),
                    internalReference = rs.getString("internal_reference"),
                    providerReference = rs.getString("provider_reference"),
                    amount = rs.getBigDecimal("amount"),
                    currency = rs.getString("currency"),
                    status = rs.getString("status"),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                    providerCode = rs.getString("provider_code"),
                    endpointUrl = rs.getString("endpoint_url"),
                    clientId = rs.getString("client_id"),
                    apiKeySecretRef = rs.getString("api_key_secret_ref"),
                    checksumKeySecretRef = rs.getString(
                        "checksum_key_secret_ref",
                    ),
                )
            },
            tenantId,
            propertyId,
            transactionId,
        ).singleOrNull()
    }

    private fun BigDecimal.money(): BigDecimal {
        return setScale(2, RoundingMode.HALF_UP)
    }

    private data class StatusWork(
        val transactionId: UUID,
        val tenantId: UUID,
        val propertyId: UUID,
        val folioId: UUID?,
        val posOrderId: UUID?,
        val initiatedBy: UUID?,
        val providerAccountId: UUID,
        val internalReference: String,
        val providerReference: String?,
        val amount: BigDecimal,
        val currency: String,
        val status: String,
        val expiresAt: java.time.Instant,
        val providerCode: String,
        val endpointUrl: String,
        val clientId: String,
        val apiKeySecretRef: String,
        val checksumKeySecretRef: String,
    )

    private companion object {
        const val PAYMENT_STATUS_CHECK_REQUESTED =
            "payment.status_check.requested"
    }
}
