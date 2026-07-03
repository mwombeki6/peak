package com.mwombeki.peak.fiscal.internal

import com.mwombeki.peak.billing.api.BillingSnapshotPort
import com.mwombeki.peak.fiscal.api.FiscalInvoiceItem
import com.mwombeki.peak.fiscal.api.FiscalProvider
import com.mwombeki.peak.fiscal.api.FiscalSubmissionCommand
import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.secrets.SecretReferenceResolver
import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class FiscalCorrectionOutboxHandler(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val transactionTemplate: TransactionTemplate,
    private val billingSnapshotPort: BillingSnapshotPort,
    private val secretResolver: SecretReferenceResolver,
    private val meterRegistry: MeterRegistry,
    providers: List<FiscalProvider>,
) : OutboxEventHandler {
    override val destination = OutboxDestination.FISCAL
    private val providersByCode = providers.associateBy(FiscalProvider::providerCode)

    override fun supports(event: ClaimedOutboxEvent): Boolean {
        return event.destination == destination &&
                event.eventType == CREDIT_NOTE_ISSUED
    }

    override suspend fun handle(event: ClaimedOutboxEvent) {
        val tenantId = requireNotNull(event.tenantId)
        val propertyId = requireNotNull(event.propertyId)
        val creditNoteId = requireNotNull(event.aggregateId)
        val identity = RequestIdentity.Public(
            tenantId = tenantId,
            propertyId = propertyId,
            correlationId = event.correlationId.toString(),
        )
        val work = requireNotNull(
            transactionTemplate.execute {
                databaseSessionContext.bind(identity)
                prepare(tenantId, propertyId, creditNoteId)
            },
        )
        val provider = providersByCode[work.providerCode]
            ?: error("No fiscal provider for ${work.providerCode}")
        try {
            val result = provider.submit(
                FiscalSubmissionCommand(
                    receiptId = work.correctionId,
                    invoiceId = work.creditNote.invoiceId,
                    invoiceNumber = work.creditNote.number,
                    taxpayerIdentifier = work.taxpayerIdentifier,
                    deviceSerial = work.deviceSerial,
                    endpointUrl = work.endpointUrl,
                    credential = secretResolver.resolve(work.secretRef),
                    currency = "TZS",
                    subtotal = work.creditNote.subtotal,
                    taxTotal = work.creditNote.taxTotal,
                    total = work.creditNote.total,
                    items = work.creditNote.lines.map {
                        FiscalInvoiceItem(
                            description = it.description,
                            amount = it.amount,
                            taxAmount = it.taxAmount,
                        )
                    },
                    correctionOfReceiptId = work.originalReceiptId,
                ),
            )
            transactionTemplate.executeWithoutResult {
                databaseSessionContext.bind(identity)
                val status = if (result.accepted) "accepted" else "rejected"
                jdbcTemplate.update(
                    """
                    UPDATE fiscal_corrections
                    SET status = ?,
                        submitted_at = COALESCE(submitted_at, now()),
                        accepted_at = CASE WHEN ? = 'accepted' THEN now() ELSE NULL END,
                        last_error_code = ?,
                        last_error_message = ?,
                        updated_at = now()
                    WHERE tenant_id = ? AND id = ?
                    """.trimIndent(),
                    status,
                    status,
                    result.errorCode,
                    result.errorMessage,
                    tenantId,
                    work.correctionId,
                )
                billingSnapshotPort.markCreditNoteFiscalStatus(
                    tenantId,
                    propertyId,
                    creditNoteId,
                    status,
                )
            }
            meterRegistry.counter(
                "peak.fiscal.correction",
                "provider",
                work.providerCode,
                "result",
                if (result.accepted) "accepted" else "rejected",
            ).increment()
        } catch (ex: Exception) {
            transactionTemplate.executeWithoutResult {
                databaseSessionContext.bind(identity)
                jdbcTemplate.update(
                    """
                    UPDATE fiscal_corrections
                    SET status = 'retry',
                        last_error_code = ?,
                        last_error_message = ?,
                        updated_at = now()
                    WHERE tenant_id = ? AND id = ?
                    """.trimIndent(),
                    ex::class.simpleName,
                    ex.message?.take(500),
                    tenantId,
                    work.correctionId,
                )
                billingSnapshotPort.markCreditNoteFiscalStatus(
                    tenantId,
                    propertyId,
                    creditNoteId,
                    "pending",
                )
            }
            throw ex
        }
    }

    private fun prepare(
        tenantId: UUID,
        propertyId: UUID,
        creditNoteId: UUID,
    ): CorrectionWork {
        val creditNote = billingSnapshotPort.fiscalCreditNoteSnapshot(
            tenantId,
            propertyId,
            creditNoteId,
        )
        require(creditNote.status == "issued" && creditNote.lines.isNotEmpty()) {
            "Only issued line-linked credit notes can be fiscalized"
        }
        val receiptId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM fiscal_receipts
            WHERE tenant_id = ?
              AND property_id = ?
              AND invoice_id = ?
              AND status = 'accepted'
            """.trimIndent(),
            UUID::class.java,
            tenantId,
            propertyId,
            creditNote.invoiceId,
        ) ?: error("Accepted original fiscal receipt was not found")
        val config = jdbcTemplate.query(
            """
            SELECT fpc.id, fpc.secret_ref, fpc.endpoint_url, fpc.device_serial,
                   fpc.taxpayer_identifier, fp.provider_code
            FROM fiscal_provider_configs fpc
            JOIN fiscal_providers fp
              ON fp.id = fpc.provider_id
             AND fp.is_active = true
            WHERE fpc.tenant_id = ?
              AND fpc.property_id = ?
              AND fpc.is_active = true
            ORDER BY fpc.is_default DESC, fpc.created_at
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                CorrectionConfig(
                    providerCode = rs.getString("provider_code"),
                    secretRef = rs.getString("secret_ref"),
                    endpointUrl = rs.getString("endpoint_url"),
                    deviceSerial = rs.getString("device_serial"),
                    taxpayerIdentifier = rs.getString("taxpayer_identifier"),
                )
            },
            tenantId,
            propertyId,
        ).singleOrNull() ?: error("Active fiscal provider configuration was not found")
        val correctionId = jdbcTemplate.queryForObject(
            """
            INSERT INTO fiscal_corrections (
                id, tenant_id, property_id, invoice_id, credit_note_id,
                fiscal_receipt_id, status, attempt_count
            )
            VALUES (?, ?, ?, ?, ?, ?, 'submitted', 1)
            ON CONFLICT (tenant_id, credit_note_id)
            DO UPDATE SET
                status = 'submitted',
                attempt_count = fiscal_corrections.attempt_count + 1,
                submitted_at = now(),
                updated_at = now()
            RETURNING id
            """.trimIndent(),
            UUID::class.java,
            UUID.randomUUID(),
            tenantId,
            propertyId,
            creditNote.invoiceId,
            creditNoteId,
            receiptId,
        ) ?: error("Fiscal correction id was not returned")
        billingSnapshotPort.markCreditNoteFiscalStatus(
            tenantId,
            propertyId,
            creditNoteId,
            "submitted",
        )
        return CorrectionWork(
            correctionId = correctionId,
            originalReceiptId = receiptId,
            creditNote = creditNote,
            providerCode = config.providerCode,
            secretRef = config.secretRef,
            endpointUrl = config.endpointUrl,
            deviceSerial = config.deviceSerial,
            taxpayerIdentifier = config.taxpayerIdentifier,
        )
    }

    private data class CorrectionConfig(
        val providerCode: String,
        val secretRef: String,
        val endpointUrl: String,
        val deviceSerial: String?,
        val taxpayerIdentifier: String,
    )

    private data class CorrectionWork(
        val correctionId: UUID,
        val originalReceiptId: UUID,
        val creditNote: com.mwombeki.peak.billing.api.FiscalCreditNoteSnapshot,
        val providerCode: String,
        val secretRef: String,
        val endpointUrl: String,
        val deviceSerial: String?,
        val taxpayerIdentifier: String,
    )

    private companion object {
        const val CREDIT_NOTE_ISSUED = "billing.credit_note.issued"
    }
}
