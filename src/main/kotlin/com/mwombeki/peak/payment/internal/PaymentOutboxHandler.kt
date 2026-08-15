package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.ProviderPaymentStatus
import com.mwombeki.peak.payment.api.ProviderCollectionCommand
import com.mwombeki.peak.realtime.api.RealtimeEventRequest
import com.mwombeki.peak.realtime.api.RealtimeEventTypes
import com.mwombeki.peak.realtime.api.RealtimePort
import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.secrets.SecretReferenceResolver
import java.math.BigDecimal
import java.util.UUID
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class PaymentOutboxHandler(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val transactionTemplate: TransactionTemplate,
    adapters: List<PaymentProvider>,
    private val secretResolver: SecretReferenceResolver,
    private val meterRegistry: MeterRegistry,
    private val outboxPort: OutboxPort,
    private val realtime: ObjectProvider<RealtimePort>,
) : OutboxEventHandler {
    private val adaptersByCode = adapters.associateBy { it.providerCode }

    override val destination = OutboxDestination.PAYMENT

    override fun supports(event: ClaimedOutboxEvent): Boolean {
        return event.destination == destination &&
                event.eventType == PAYMENT_COLLECTION_REQUESTED
    }

    override suspend fun handle(event: ClaimedOutboxEvent) {
        withContext(Dispatchers.IO) {
            handleBlocking(event)
        }
    }

    private fun handleBlocking(event: ClaimedOutboxEvent) {
        val tenantId = requireNotNull(event.tenantId) {
            "Payment collection events must be tenant scoped"
        }
        val work = requireNotNull(
            transactionTemplate.execute {
                databaseSessionContext.bind(
                    RequestIdentity.Public(
                        tenantId = tenantId,
                        propertyId = event.propertyId,
                        correlationId = event.correlationId.toString(),
                    ),
                )
                loadWork(
                    tenantId,
                    requireNotNull(event.aggregateId) {
                        "Payment collection event aggregate id is required"
                    },
                )
            },
        )
        if (work.status !in setOf("created", "initiated", "pending")) {
            return
        }

        val adapter = adaptersByCode[work.providerCode]
            ?: error("No payment provider adapter is registered for ${work.providerCode}")
        val timer = Timer.start(meterRegistry)
        val result = try {
            adapter.initiate(
                ProviderCollectionCommand(
                    transactionId = work.transactionId,
                    internalReference = work.internalReference,
                    endpointUrl = work.endpointUrl,
                    clientId = work.clientId,
                    payerIdentifier = work.payerIdentifier,
                    // Carried from the request rather than worked out here. A provider that
                    // needs a network was already refused at the boundary if it had none,
                    // so reaching the worker without one means the provider does not need it.
                    providerChannel = work.mobileNetwork,
                    amount = work.amount,
                    currency = work.currency,
                    apiKey = secretResolver.resolve(work.apiKeySecretRef),
                    checksumKey = secretResolver.resolve(
                        work.checksumKeySecretRef,
                    ),
                ),
            ).also {
                meterRegistry.counter(
                    "peak.payment.provider.initiation",
                    "provider",
                    adapter.providerCode,
                    "result",
                    "accepted",
                ).increment()
            }
        } catch (ex: Exception) {
            meterRegistry.counter(
                "peak.payment.provider.initiation",
                "provider",
                adapter.providerCode,
                "result",
                "failed",
            ).increment()
            throw ex
        } finally {
            timer.stop(
                meterRegistry.timer(
                    "peak.payment.provider.latency",
                    "provider",
                    adapter.providerCode,
                ),
            )
        }
        // Acceptance evidence, not settlement evidence. A provider that answers SUCCEEDED
        // synchronously is still only telling us it took the request; the folio is posted by
        // a callback or a status query, never here.
        require(
            result.status == ProviderPaymentStatus.PENDING ||
                result.status == ProviderPaymentStatus.SUCCEEDED,
        ) {
            "Provider initiation returned ${result.status} (${result.providerStatus}), so the " +
                "push cannot be assumed to have reached the payer"
        }

        transactionTemplate.executeWithoutResult {
            databaseSessionContext.bind(
                RequestIdentity.Public(
                    tenantId = tenantId,
                    propertyId = event.propertyId,
                    correlationId = event.correlationId.toString(),
                ),
            )
            jdbcTemplate.update(
                """
                UPDATE payment_transactions
                SET provider_reference = ?,
                    provider_status = ?,
                    status = 'pending',
                    next_status_check_at = now() + interval '15 seconds',
                    updated_at = now()
                WHERE tenant_id = ?
                  AND id = ?
                  AND status IN ('created', 'initiated', 'pending')
                """.trimIndent(),
                result.providerReference,
                result.providerStatus,
                tenantId,
                work.transactionId,
            )
            realtime.ifAvailable {
                it.broadcastRealtimeEvent(
                    RealtimeEventRequest(
                        tenantId = tenantId,
                        propertyId = requireNotNull(event.propertyId) {
                            "Payment collection events must be property scoped"
                        },
                        eventType = RealtimeEventTypes.PAYMENT_PENDING,
                        aggregateType = RealtimeEventTypes.AGGREGATE_PAYMENT_TRANSACTION,
                        aggregateId = work.transactionId,
                        aggregateVersion = jdbcTemplate.queryForObject(
                            "SELECT status_version FROM payment_transactions " +
                                "WHERE tenant_id = ? AND id = ?",
                            Long::class.java,
                            tenantId,
                            work.transactionId,
                        ),
                        payload = mapOf(
                            "transactionId" to work.transactionId,
                            "providerReference" to result.providerReference,
                            "providerStatus" to result.providerStatus,
                        ),
                    ),
                )
            }
            outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = "payment_transactions",
                    aggregateId = work.transactionId,
                    tenantId = tenantId,
                    propertyId = event.propertyId,
                    eventType = PAYMENT_STATUS_CHECK_REQUESTED,
                    destination = OutboxDestination.PAYMENT,
                    payload = mapOf("transactionId" to work.transactionId),
                    priority = 3,
                    availableAt = java.time.Instant.now().plusSeconds(15),
                ),
            )
        }
    }

    private fun loadWork(tenantId: UUID, transactionId: UUID): PaymentProviderWork {
        return jdbcTemplate.query(
            """
            SELECT pt.id, pt.internal_reference, pt.payer_identifier, pt.mobile_network,
                   pt.amount,
                   pt.currency, pt.status, pp.provider_code, ppa.client_id,
                   ppa.endpoint_url, ppa.api_key_secret_ref,
                   ppa.checksum_key_secret_ref,
                   ppa.provider_app_name
            FROM payment_transactions pt
            JOIN payment_provider_accounts ppa
              ON ppa.tenant_id = pt.tenant_id
             AND ppa.id = pt.provider_account_id
             AND ppa.is_active = true
             AND ppa.lifecycle_status = 'enabled'
            JOIN payment_providers pp
              ON pp.tenant_id = ppa.tenant_id
             AND pp.id = ppa.provider_id
             AND pp.is_active = true
            WHERE pt.tenant_id = ?
              AND pt.id = ?
            FOR UPDATE OF pt
            """.trimIndent(),
            { rs, _ ->
                PaymentProviderWork(
                    transactionId = rs.getObject("id", UUID::class.java),
                    internalReference = rs.getString("internal_reference"),
                    payerIdentifier = rs.getString("payer_identifier"),
                    mobileNetwork = rs.getString("mobile_network"),
                    amount = rs.getBigDecimal("amount"),
                    currency = rs.getString("currency").trim(),
                    status = rs.getString("status"),
                    providerCode = rs.getString("provider_code"),
                    endpointUrl = rs.getString("endpoint_url"),
                    clientId = rs.getString("client_id"),
                    apiKeySecretRef = rs.getString("api_key_secret_ref"),
                    checksumKeySecretRef = rs.getString(
                        "checksum_key_secret_ref",
                    ),
                    providerAppName = rs.getString("provider_app_name"),
                )
            },
            tenantId,
            transactionId,
        ).singleOrNull() ?: error("Payment transaction or provider account is unavailable")
    }

    private data class PaymentProviderWork(
        val transactionId: UUID,
        val internalReference: String,
        val payerIdentifier: String,
        val mobileNetwork: String?,
        val amount: BigDecimal,
        val currency: String,
        val status: String,
        val providerCode: String,
        val endpointUrl: String?,
        val clientId: String,
        val apiKeySecretRef: String,
        val checksumKeySecretRef: String,
        val providerAppName: String?,
    )

    private companion object {
        const val PAYMENT_COLLECTION_REQUESTED = "payment.collection.requested"
        const val PAYMENT_STATUS_CHECK_REQUESTED =
            "payment.status_check.requested"
    }
}
