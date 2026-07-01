package com.mwombeki.peak.fiscal.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface FiscalPort {
    fun submitInvoice(request: FiscalSubmissionRequest)
    fun getReceiptForInvoice(invoiceId: UUID): FiscalReceiptResponse?
    fun overrideFiscalization(invoiceId: UUID, request: FiscalOverrideRequest)
}
