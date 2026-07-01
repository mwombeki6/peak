package com.mwombeki.peak.fiscal.api

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
