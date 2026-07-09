package com.mwombeki.peak.fiscal.api

import java.time.LocalDate
import java.util.UUID

interface FiscalPort {
    fun configureProvider(
        propertyId: UUID,
        request: ConfigureFiscalProviderRequest,
    ): FiscalProviderConfigResponse

    fun listProviderConfigs(propertyId: UUID): List<FiscalProviderConfigResponse>
    fun listReceipts(propertyId: UUID, limit: Int = 100): List<FiscalReceiptResponse>
    fun getReceipt(propertyId: UUID, receiptId: UUID): FiscalReceiptResponse?
    fun retryReceipt(propertyId: UUID, receiptId: UUID): FiscalReceiptResponse
}

@org.springframework.modulith.NamedInterface("api")
interface FiscalStatusPort {
    fun hasAcceptedReceipt(
        tenantId: UUID,
        propertyId: UUID,
        invoiceId: UUID,
    ): Boolean

    fun hasFiscalActivity(
        tenantId: UUID,
        propertyId: UUID,
        invoiceId: UUID,
    ): Boolean

    fun nightAuditSummary(
        tenantId: UUID,
        propertyId: UUID,
    ): FiscalNightAuditSummary

    fun closeSnapshotSummary(
        tenantId: UUID,
        propertyId: UUID,
        businessDate: LocalDate,
    ): FiscalCloseSnapshotSummary

}
