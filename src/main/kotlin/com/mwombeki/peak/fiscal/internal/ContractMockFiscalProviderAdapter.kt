package com.mwombeki.peak.fiscal.internal

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "peak.fiscal.providers.contract-mock",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ContractMockFiscalProviderAdapter : FiscalProviderAdapter {
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
