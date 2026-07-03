package com.mwombeki.peak.fiscal.internal

import com.mwombeki.peak.billing.api.BillingPort
import com.mwombeki.peak.billing.api.BillingSnapshotPort
import com.mwombeki.peak.billing.api.CreateCreditNoteRequest
import com.mwombeki.peak.billing.api.CreditNoteResponse
import com.mwombeki.peak.billing.api.InvoiceResponse
import com.mwombeki.peak.billing.api.VoidInvoiceRequest
import com.mwombeki.peak.fiscal.api.FiscalStatusPort
import com.mwombeki.peak.shared.context.TenantRequestContext
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class FiscalInvoiceCorrectionService(
    private val billingPort: BillingPort,
    private val billingSnapshotPort: BillingSnapshotPort,
    private val fiscalStatusPort: FiscalStatusPort,
    private val tenantRequestContext: TenantRequestContext,
    private val transactionTemplate: TransactionTemplate,
) {
    fun voidInvoice(
        propertyId: UUID,
        invoiceId: UUID,
        request: VoidInvoiceRequest,
    ): InvoiceResponse {
        return requireNotNull(
            transactionTemplate.execute {
                val actor = tenantRequestContext.bind()
                tenantRequestContext.requirePropertyUsable(
                    actor.tenantId,
                    propertyId,
                )
                billingSnapshotPort.fiscalInvoiceSnapshot(
                    actor.tenantId,
                    propertyId,
                    invoiceId,
                )
                billingPort.voidInvoice(
                    propertyId = propertyId,
                    invoiceId = invoiceId,
                    request = request,
                    hasFiscalAcceptance = fiscalStatusPort.hasFiscalActivity(
                        actor.tenantId,
                        propertyId,
                        invoiceId,
                    ),
                )
            },
        )
    }

    fun createCreditNote(
        propertyId: UUID,
        invoiceId: UUID,
        request: CreateCreditNoteRequest,
    ): CreditNoteResponse {
        return requireNotNull(
            transactionTemplate.execute {
                val actor = tenantRequestContext.bind()
                tenantRequestContext.requirePropertyUsable(
                    actor.tenantId,
                    propertyId,
                )
                billingSnapshotPort.fiscalInvoiceSnapshot(
                    actor.tenantId,
                    propertyId,
                    invoiceId,
                )
                billingPort.createCreditNote(
                    propertyId = propertyId,
                    invoiceId = invoiceId,
                    request = request,
                    fiscalCorrectionRequired = fiscalStatusPort.hasFiscalActivity(
                        actor.tenantId,
                        propertyId,
                        invoiceId,
                    ),
                )
            },
        )
    }
}
