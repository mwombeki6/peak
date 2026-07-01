package com.mwombeki.peak.fiscal.internal.provider

import com.mwombeki.peak.billing.api.InvoiceResponse
import java.util.Base64
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["peak.fiscal.simulator.enabled"], havingValue = "true", matchIfMissing = true)
class FiscalSimulatorProvider : FiscalProvider {
    override fun name(): String = "SIMULATOR"

    override fun submit(invoice: InvoiceResponse): FiscalProviderResult {
        // Simulate a deterministic failure for specific amounts if needed for testing
        if (invoice.total.toInt() == 666) {
            return FiscalProviderResult.Failure("Simulated fiscal provider error", retryable = true)
        }

        val dummyRef = "SIM-${invoice.invoiceNumber ?: invoice.id.toString().take(8)}"
        val dummyPayload = Base64.getEncoder().encodeToString(
            "SIGNED-BY-SIMULATOR-${invoice.id}-${invoice.total}".toByteArray()
        )

        return FiscalProviderResult.Success(
            fiscalReference = dummyRef,
            signedPayload = dummyPayload
        )
    }
}
