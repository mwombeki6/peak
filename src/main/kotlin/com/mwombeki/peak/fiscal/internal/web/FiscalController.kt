package com.mwombeki.peak.fiscal.internal.web

import com.mwombeki.peak.fiscal.api.FiscalOverrideRequest
import com.mwombeki.peak.fiscal.api.FiscalPort
import com.mwombeki.peak.fiscal.api.FiscalReceiptResponse
import java.util.UUID
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/fiscal")
class FiscalController(private val fiscalPort: FiscalPort) {

    @GetMapping("/invoices/{invoiceId}/receipt")
    fun getReceipt(@PathVariable invoiceId: UUID): FiscalReceiptResponse {
        return fiscalPort.getReceiptForInvoice(invoiceId)
            ?: throw NoSuchElementException("No fiscal receipt found for invoice $invoiceId")
    }

    @PostMapping("/invoices/{invoiceId}/override")
    fun overrideFiscalization(
        @PathVariable invoiceId: UUID,
        @RequestBody request: FiscalOverrideRequest
    ) {
        fiscalPort.overrideFiscalization(invoiceId, request)
    }
}
