package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.secrets.SecretReferenceResolver
import java.math.BigDecimal
import java.util.UUID
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class PaymentOutboxHandler(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val transactionTemplate: TransactionTemplate,
    adapters: List<PaymentProviderAdapter>,
    private val secretResolver: SecretReferenceResolver,
    private val meterRegistry: MeterRegistry,
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
        if (work.status !in setOf("initiated", "pending")) {
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
                    merchantId = work.merchantId,
                    payerIdentifier = work.payerIdentifier,
                    amount = work.amount,
                    currency = work.currency,
                    credential = secretResolver.resolve(work.secretRef),
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
        require(result.status == "pending" || result.status == "initiated") {
            "Provider initiation returned unsupported status ${result.status}"
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
                    status = 'pending',
                    updated_at = now()
                WHERE tenant_id = ?
                  AND id = ?
                  AND status IN ('initiated', 'pending')
                """.trimIndent(),
                result.providerReference,
                tenantId,
                work.transactionId,
            )
        }
    }

    private fun loadWork(tenantId: UUID, transactionId: UUID): PaymentProviderWork {
        return jdbcTemplate.query(
            """
            SELECT pt.id, pt.internal_reference, pt.payer_identifier, pt.amount,
                   pt.currency, pt.status, pp.provider_code, ppa.merchant_id,
                   ppa.endpoint_url, ppa.secret_ref
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
              AND pt.id = ?
            FOR UPDATE OF pt
            """.trimIndent(),
            { rs, _ ->
                PaymentProviderWork(
                    transactionId = rs.getObject("id", UUID::class.java),
                    internalReference = rs.getString("internal_reference"),
                    payerIdentifier = rs.getString("payer_identifier"),
                    amount = rs.getBigDecimal("amount"),
                    currency = rs.getString("currency").trim(),
                    status = rs.getString("status"),
                    providerCode = rs.getString("provider_code"),
                    endpointUrl = rs.getString("endpoint_url"),
                    merchantId = rs.getString("merchant_id"),
                    secretRef = rs.getString("secret_ref"),
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
        val amount: BigDecimal,
        val currency: String,
        val status: String,
        val providerCode: String,
        val endpointUrl: String?,
        val merchantId: String?,
        val secretRef: String?,
    )

    private companion object {
        const val PAYMENT_COLLECTION_REQUESTED = "payment.collection.requested"
    }
}
