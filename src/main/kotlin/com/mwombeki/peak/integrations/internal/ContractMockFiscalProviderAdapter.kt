package com.mwombeki.peak.integrations.internal

// Non-production contract adapter retained for deterministic local acceptance.
import com.mwombeki.peak.fiscal.api.FiscalProvider
import com.mwombeki.peak.fiscal.api.FiscalSubmissionCommand
import com.mwombeki.peak.fiscal.api.FiscalSubmissionResult
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "peak.fiscal.providers.contract-mock",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ContractMockFiscalProviderAdapter : FiscalProvider {
    override val providerCode = "contract_mock"

    override fun submit(command: FiscalSubmissionCommand): FiscalSubmissionResult {
        val suffix = command.receiptId.toString().replace("-", "").take(16).uppercase()
        return FiscalSubmissionResult(
            accepted = true,
            providerDocumentId = "MOCK-DOC-$suffix",
            receiptNumber = "MOCK-$suffix",
            fiscalCode = "FISC-$suffix",
            verificationCode = suffix,
            qrCodeUrl = "https://fiscal.invalid/verify/$suffix",
            responseMetadata = mapOf("contractVersion" to "1"),
        )
    }
}
