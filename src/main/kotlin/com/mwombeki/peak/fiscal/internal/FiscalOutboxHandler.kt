package com.mwombeki.peak.fiscal.internal

import com.mwombeki.peak.billing.api.BillingSnapshotPort
import com.mwombeki.peak.fiscal.api.FiscalInvoiceItem
import com.mwombeki.peak.fiscal.api.FiscalProvider
import com.mwombeki.peak.fiscal.api.FiscalSubmissionCommand
import com.mwombeki.peak.fiscal.api.FiscalSubmissionResult
import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.secrets.SecretReferenceResolver
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class FiscalOutboxHandler(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val secretResolver: SecretReferenceResolver,
    private val meterRegistry: MeterRegistry,
    private val billingSnapshotPort: BillingSnapshotPort,
    adapters: List<FiscalProvider>,
) : OutboxEventHandler {
    private val adaptersByCode = adapters.associateBy { it.providerCode }

    override val destination = OutboxDestination.FISCAL

    override fun supports(event: ClaimedOutboxEvent): Boolean {
        return event.destination == destination &&
                event.eventType in SUPPORTED_EVENTS
    }

    override suspend fun handle(event: ClaimedOutboxEvent) {
        val tenantId = requireNotNull(event.tenantId) {
            "Fiscal events must be tenant scoped"
        }
        val propertyId = requireNotNull(event.propertyId) {
            "Fiscal events must be property scoped"
        }
        val invoiceId = requireNotNull(event.aggregateId) {
            "Fiscal event invoice id is required"
        }
        val identity = RequestIdentity.Public(
            tenantId = tenantId,
            propertyId = propertyId,
            correlationId = event.correlationId.toString(),
        )
        val work = transactionTemplate.execute {
            databaseSessionContext.bind(identity)
            prepareSubmission(tenantId, propertyId, invoiceId, event.id)
        } ?: return

        val adapter = adaptersByCode[work.providerCode]
            ?: error("No fiscal provider adapter is registered for ${work.providerCode}")

        val timer = Timer.start(meterRegistry)
        try {
            val result = adapter.submit(
                FiscalSubmissionCommand(
                    receiptId = work.receiptId,
                    invoiceId = work.invoiceId,
                    invoiceNumber = work.invoiceNumber,
                    taxpayerIdentifier = work.taxpayerIdentifier,
                    deviceSerial = work.deviceSerial,
                    endpointUrl = work.endpointUrl,
                    credential = secretResolver.resolve(work.secretRef),
                    currency = work.currency,
                    subtotal = work.subtotal,
                    taxTotal = work.taxTotal,
                    total = work.total,
                    items = work.items,
                ),
            )
            transactionTemplate.executeWithoutResult {
                databaseSessionContext.bind(identity)
                completeSubmission(tenantId, work, result)
            }
            meterRegistry.counter(
                "peak.fiscal.provider.submission",
                "provider",
                adapter.providerCode,
                "result",
                if (result.accepted) "accepted" else "rejected",
            ).increment()
        } catch (ex: Exception) {
            meterRegistry.counter(
                "peak.fiscal.provider.submission",
                "provider",
                adapter.providerCode,
                "result",
                "failed",
            ).increment()
            transactionTemplate.executeWithoutResult {
                databaseSessionContext.bind(identity)
                failAttempt(tenantId, work, ex)
            }
            throw ex
        } finally {
            timer.stop(
                meterRegistry.timer(
                    "peak.fiscal.provider.latency",
                    "provider",
                    adapter.providerCode,
                ),
            )
        }
    }

    private fun prepareSubmission(
        tenantId: UUID,
        propertyId: UUID,
        invoiceId: UUID,
        outboxEventId: UUID,
    ): FiscalSubmissionWork? {
        val invoice = billingSnapshotPort.fiscalInvoiceSnapshot(
            tenantId,
            propertyId,
            invoiceId,
        )
        if (invoice.status == "voided") {
            return null
        }
        require(invoice.status in setOf("issued", "sent", "paid")) {
            "Only issued invoices can be fiscalized"
        }
        require(invoice.number.isNotBlank()) {
            "Invoice number is required before fiscalization"
        }

        val config = jdbcTemplate.query(
            """
            SELECT fpc.id, fpc.secret_ref, fpc.endpoint_url, fpc.device_serial,
                   fpc.taxpayer_identifier, fp.provider_code, fp.fiscal_mode
            FROM fiscal_provider_configs fpc
            JOIN fiscal_providers fp ON fp.id = fpc.provider_id AND fp.is_active = true
            WHERE fpc.tenant_id = ?
              AND fpc.property_id = ?
              AND fpc.is_active = true
            ORDER BY fpc.is_default DESC, fpc.created_at
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                FiscalConfig(
                    id = rs.getObject("id", UUID::class.java),
                    secretRef = rs.getString("secret_ref"),
                    endpointUrl = rs.getString("endpoint_url"),
                    deviceSerial = rs.getString("device_serial"),
                    taxpayerIdentifier = rs.getString("taxpayer_identifier"),
                    providerCode = rs.getString("provider_code"),
                    fiscalMode = rs.getString("fiscal_mode"),
                )
            },
            tenantId,
            propertyId,
        ).singleOrNull() ?: error("Active fiscal provider configuration was not found")

        val existing = jdbcTemplate.query(
            """
            SELECT id, status
            FROM fiscal_receipts
            WHERE tenant_id = ? AND invoice_id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                rs.getObject("id", UUID::class.java) to rs.getString("status")
            },
            tenantId,
            invoiceId,
        ).singleOrNull()
        if (existing?.second == "accepted") {
            return null
        }
        require(existing?.second != "rejected") {
            "Rejected fiscal receipt must be explicitly queued for retry"
        }

        val receiptId = existing?.first ?: UUID.randomUUID()
        if (existing == null) {
            jdbcTemplate.update(
                """
                INSERT INTO fiscal_receipts (
                    id, tenant_id, property_id, invoice_id, fiscal_mode,
                    receipt_number, status
                )
                VALUES (?, ?, ?, ?, ?, ?, 'submitted')
                """.trimIndent(),
                receiptId,
                tenantId,
                propertyId,
                invoiceId,
                config.fiscalMode,
                "PEAK-FISC-${receiptId.toString().replace("-", "").take(20).uppercase()}",
            )
        } else {
            jdbcTemplate.update(
                """
                UPDATE fiscal_receipts
                SET status = 'submitted', updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'pending'
                """.trimIndent(),
                tenantId,
                receiptId,
            )
        }

        val items = invoice.items.map {
                FiscalInvoiceItem(
                    description = it.description,
                    amount = it.amount.money(),
                    taxAmount = it.taxAmount.money(),
                )
            }
        require(items.isNotEmpty()) {
            "Fiscal invoice has no posted items"
        }
        val attemptNumber = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(attempt_no), 0) + 1
            FROM fiscal_submission_attempts
            WHERE tenant_id = ? AND fiscal_receipt_id = ?
            """.trimIndent(),
            Int::class.java,
            tenantId,
            receiptId,
        ) ?: 1
        val attemptId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO fiscal_submission_attempts (
                id, tenant_id, provider_config_id, fiscal_receipt_id,
                attempt_no, request_payload, status
            )
            VALUES (?, ?, ?, ?, ?, ?::jsonb, 'pending')
            """.trimIndent(),
            attemptId,
            tenantId,
            config.id,
            receiptId,
            attemptNumber,
            objectMapper.writeValueAsString(
                mapOf(
                    "invoiceId" to invoiceId,
                    "invoiceNumber" to invoice.number,
                    "outboxEventId" to outboxEventId,
                    "currency" to invoice.currency,
                    "subtotal" to invoice.subtotal,
                    "taxTotal" to invoice.taxTotal,
                    "total" to invoice.total,
                    "itemCount" to items.size,
                ),
            ),
        )
        return FiscalSubmissionWork(
            receiptId = receiptId,
            attemptId = attemptId,
            providerConfigId = config.id,
            providerCode = config.providerCode,
            secretRef = config.secretRef,
            endpointUrl = config.endpointUrl,
            deviceSerial = config.deviceSerial,
            taxpayerIdentifier = config.taxpayerIdentifier,
            invoiceId = invoiceId,
            invoiceNumber = invoice.number,
            currency = invoice.currency,
            subtotal = invoice.subtotal,
            taxTotal = invoice.taxTotal,
            total = invoice.total,
            items = items,
        )
    }

    private fun completeSubmission(
        tenantId: UUID,
        work: FiscalSubmissionWork,
        result: FiscalSubmissionResult,
    ) {
        val response = objectMapper.writeValueAsString(result.responseMetadata)
        if (result.accepted) {
            val providerDocumentId = requireNotNull(result.providerDocumentId) {
                "Accepted fiscal response requires provider document id"
            }
            jdbcTemplate.update(
                """
                UPDATE fiscal_receipts
                SET fiscal_code = ?,
                    verification_code = ?,
                    qr_code_url = ?,
                    response_payload = ?::jsonb,
                    status = 'accepted',
                    updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'submitted'
                """.trimIndent(),
                result.fiscalCode,
                result.verificationCode,
                result.qrCodeUrl,
                response,
                tenantId,
                work.receiptId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO fiscal_document_mappings (
                    id, tenant_id, provider_config_id, invoice_id,
                    fiscal_receipt_id, provider_document_id,
                    provider_document_number, qr_code_url, metadata
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (tenant_id, provider_config_id, provider_document_id)
                DO UPDATE SET
                    provider_document_number = EXCLUDED.provider_document_number,
                    qr_code_url = EXCLUDED.qr_code_url,
                    metadata = EXCLUDED.metadata,
                    mapped_at = now()
                """.trimIndent(),
                UUID.randomUUID(),
                tenantId,
                work.providerConfigId,
                work.invoiceId,
                work.receiptId,
                providerDocumentId,
                result.receiptNumber,
                result.qrCodeUrl,
                response,
            )
            finishAttempt(tenantId, work.attemptId, "success", response, null, null)
        } else {
            jdbcTemplate.update(
                """
                UPDATE fiscal_receipts
                SET response_payload = ?::jsonb,
                    status = 'rejected',
                    updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'submitted'
                """.trimIndent(),
                response,
                tenantId,
                work.receiptId,
            )
            finishAttempt(
                tenantId,
                work.attemptId,
                "failed",
                response,
                result.errorCode,
                result.errorMessage,
            )
        }
    }

    private fun failAttempt(
        tenantId: UUID,
        work: FiscalSubmissionWork,
        exception: Exception,
    ) {
        jdbcTemplate.update(
            """
            UPDATE fiscal_receipts
            SET status = 'pending', updated_at = now()
            WHERE tenant_id = ? AND id = ? AND status = 'submitted'
            """.trimIndent(),
            tenantId,
            work.receiptId,
        )
        finishAttempt(
            tenantId = tenantId,
            attemptId = work.attemptId,
            status = "failed",
            response = null,
            errorCode = exception::class.simpleName,
            errorMessage = exception.message?.take(MAX_ERROR_LENGTH),
        )
    }

    private fun finishAttempt(
        tenantId: UUID,
        attemptId: UUID,
        status: String,
        response: String?,
        errorCode: String?,
        errorMessage: String?,
    ) {
        jdbcTemplate.update(
            """
            UPDATE fiscal_submission_attempts
            SET status = ?,
                response_payload = ?::jsonb,
                error_code = ?,
                error_message = ?,
                completed_at = now()
            WHERE tenant_id = ? AND id = ?
            """.trimIndent(),
            status,
            response,
            errorCode,
            errorMessage,
            tenantId,
            attemptId,
        )
    }

    private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

    private data class FiscalConfig(
        val id: UUID,
        val secretRef: String,
        val endpointUrl: String,
        val deviceSerial: String?,
        val taxpayerIdentifier: String,
        val providerCode: String,
        val fiscalMode: String,
    )

    private data class FiscalSubmissionWork(
        val receiptId: UUID,
        val attemptId: UUID,
        val providerConfigId: UUID,
        val providerCode: String,
        val secretRef: String,
        val endpointUrl: String,
        val deviceSerial: String?,
        val taxpayerIdentifier: String,
        val invoiceId: UUID,
        val invoiceNumber: String,
        val currency: String,
        val subtotal: BigDecimal,
        val taxTotal: BigDecimal,
        val total: BigDecimal,
        val items: List<FiscalInvoiceItem>,
    )

    private companion object {
        const val MAX_ERROR_LENGTH = 500
        val SUPPORTED_EVENTS = setOf(
            "billing.invoice.issued",
            "fiscal.receipt.retry.requested",
        )
    }
}
