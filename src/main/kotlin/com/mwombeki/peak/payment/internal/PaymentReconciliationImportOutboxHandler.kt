package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.ProviderStatementQuery
import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.secrets.SecretReferenceResolver
import io.micrometer.core.instrument.MeterRegistry
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class PaymentReconciliationImportOutboxHandler(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val transactionTemplate: TransactionTemplate,
    private val secretResolver: SecretReferenceResolver,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    providers: List<PaymentProvider>,
) : OutboxEventHandler {
    override val destination = OutboxDestination.PAYMENT
    private val providersByCode = providers.associateBy(PaymentProvider::providerCode)

    override fun supports(event: ClaimedOutboxEvent): Boolean {
        return event.destination == destination &&
                event.eventType == IMPORT_REQUESTED
    }

    override suspend fun handle(event: ClaimedOutboxEvent) {
        val tenantId = requireNotNull(event.tenantId)
        val propertyId = requireNotNull(event.propertyId)
        val importId = requireNotNull(event.aggregateId)
        val payload = objectMapper.readTree(event.payload)
        val providerAccountId = UUID.fromString(
            payload.path("providerAccountId").asString(),
        )
        val startDate = LocalDate.parse(payload.path("startDate").asString())
        val endDate = LocalDate.parse(payload.path("endDate").asString())
        val currency = payload.path("currency").asString("TZS").uppercase()
        val identity = RequestIdentity.Public(
            tenantId = tenantId,
            propertyId = propertyId,
            correlationId = event.correlationId.toString(),
        )
        val account = requireNotNull(
            transactionTemplate.execute {
                databaseSessionContext.bind(identity)
                loadAccount(tenantId, propertyId, providerAccountId)
            },
        )
        val provider = providersByCode[account.providerCode]
            ?: error("No payment provider for ${account.providerCode}")
        val statement = provider.statement(
            ProviderStatementQuery(
                endpointUrl = account.endpointUrl,
                clientId = account.clientId,
                apiKey = secretResolver.resolve(account.apiKeySecretRef),
                checksumKey = secretResolver.resolve(
                    account.checksumKeySecretRef,
                ),
                startDate = startDate,
                endDate = endDate,
                currency = currency,
            ),
        )
        transactionTemplate.executeWithoutResult {
            databaseSessionContext.bind(identity)
            if (reconciliationExists(tenantId, importId)) {
                return@executeWithoutResult
            }
            val matches = statement.items.map { item ->
                val match = jdbcTemplate.query(
                    """
                    SELECT id, folio_payment_id, amount
                    FROM payment_transactions
                    WHERE tenant_id = ?
                      AND property_id = ?
                      AND provider_account_id = ?
                      AND (
                          provider_reference = ?
                          OR internal_reference = ?
                      )
                      AND status IN ('posted', 'reconciled')
                    FOR UPDATE
                    """.trimIndent(),
                    { rs, _ ->
                        Match(
                            transactionId = rs.getObject(
                                "id",
                                UUID::class.java,
                            ),
                            folioPaymentId = rs.getObject(
                                "folio_payment_id",
                                UUID::class.java,
                            ),
                            amount = rs.getBigDecimal("amount").money(),
                        )
                    },
                    tenantId,
                    propertyId,
                    providerAccountId,
                    item.providerReference,
                    item.orderReference,
                ).singleOrNull()
                item to match
            }
            val providerTotal = statement.items.fold(BigDecimal.ZERO) {
                    total,
                    item,
                ->
                total.add(item.amount)
            }.money()
            val systemTotal = matches.fold(BigDecimal.ZERO) { total, pair ->
                total.add(pair.second?.amount ?: BigDecimal.ZERO)
            }.money()
            val fullyMatched = matches.all { (item, match) ->
                match != null && item.amount.money() == match.amount
            }
            val status = if (
                fullyMatched && providerTotal == systemTotal
            ) {
                "matched"
            } else {
                "variance"
            }
            jdbcTemplate.update(
                """
                INSERT INTO payment_reconciliations (
                    id, tenant_id, property_id, provider_account_id,
                    reconciliation_date, statement_reference, opening_balance,
                    provider_total, system_total, currency, status, notes
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'Imported from ClickPesa account statement')
                """.trimIndent(),
                importId,
                tenantId,
                propertyId,
                providerAccountId,
                endDate,
                "CLICKPESA-$startDate-$endDate",
                statement.openingBalance.money(),
                providerTotal,
                systemTotal,
                currency,
                status,
            )
            matches.forEach { (item, match) ->
                val systemAmount = match?.amount ?: BigDecimal.ZERO
                jdbcTemplate.update(
                    """
                    INSERT INTO payment_reconciliation_items (
                        id, tenant_id, reconciliation_id,
                        payment_transaction_id, folio_payment_id,
                        provider_reference, item_date, provider_amount,
                        system_amount, match_status
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    tenantId,
                    importId,
                    match?.transactionId,
                    match?.folioPaymentId,
                    item.providerReference,
                    Timestamp.from(item.occurredAt),
                    item.amount.money(),
                    systemAmount,
                    when {
                        match == null -> "unmatched"
                        item.amount.money() == match.amount -> "matched"
                        else -> "variance"
                    },
                )
            }
        }
        meterRegistry.counter(
            "peak.payment.reconciliation.import",
            "provider",
            account.providerCode,
            "result",
            "completed",
        ).increment()
    }

    private fun loadAccount(
        tenantId: UUID,
        propertyId: UUID,
        providerAccountId: UUID,
    ): ProviderAccount {
        return jdbcTemplate.query(
            """
            SELECT pp.provider_code, ppa.endpoint_url, ppa.client_id,
                   ppa.api_key_secret_ref, ppa.checksum_key_secret_ref
            FROM payment_provider_accounts ppa
            JOIN payment_providers pp
              ON pp.tenant_id = ppa.tenant_id
             AND pp.id = ppa.provider_id
            WHERE ppa.tenant_id = ?
              AND ppa.property_id = ?
              AND ppa.id = ?
              AND ppa.is_active = true
              AND pp.is_active = true
            """.trimIndent(),
            { rs, _ ->
                ProviderAccount(
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
            providerAccountId,
        ).singleOrNull() ?: error("Payment provider account is unavailable")
    }

    private fun reconciliationExists(tenantId: UUID, id: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM payment_reconciliations
                WHERE tenant_id = ? AND id = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            id,
        ) == true
    }

    private fun BigDecimal.money(): BigDecimal {
        return setScale(2, RoundingMode.HALF_UP)
    }

    private data class ProviderAccount(
        val providerCode: String,
        val endpointUrl: String,
        val clientId: String,
        val apiKeySecretRef: String,
        val checksumKeySecretRef: String,
    )

    private data class Match(
        val transactionId: UUID,
        val folioPaymentId: UUID?,
        val amount: BigDecimal,
    )

    private companion object {
        const val IMPORT_REQUESTED = "payment.reconciliation.import.requested"
    }
}
