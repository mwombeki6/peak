package com.mwombeki.peak.fiscal.internal.provider

import com.mwombeki.peak.billing.api.InvoiceResponse
import java.time.Instant

interface FiscalProvider {
    fun name(): String
    fun submit(invoice: InvoiceResponse): FiscalProviderResult
}

sealed class FiscalProviderResult {
    data class Success(
        val fiscalReference: String,
        val signedPayload: String,
        val verifiedAt: Instant = Instant.now()
    ) : FiscalProviderResult()

    data class Failure(
        val errorMessage: String,
        val retryable: Boolean = true
    ) : FiscalProviderResult()
}
