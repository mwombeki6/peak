package com.mwombeki.peak.fiscal.internal.web

import com.mwombeki.peak.billing.api.CreateCreditNoteRequest
import com.mwombeki.peak.billing.api.CreditNoteResponse
import com.mwombeki.peak.billing.api.InvoiceResponse
import com.mwombeki.peak.billing.api.VoidInvoiceRequest
import com.mwombeki.peak.fiscal.internal.FiscalInvoiceCorrectionService
import java.util.UUID
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/invoices/{invoiceId}")
class FiscalInvoiceCorrectionController(
    private val correctionService: FiscalInvoiceCorrectionService,
) {
    @PostMapping("/void")
    fun voidInvoice(
        @PathVariable propertyId: UUID,
        @PathVariable invoiceId: UUID,
        @RequestBody request: VoidInvoiceRequest,
    ): InvoiceResponse {
        return correctionService.voidInvoice(
            propertyId,
            invoiceId,
            request,
        )
    }

    @PostMapping("/credit-notes")
    fun createCreditNote(
        @PathVariable propertyId: UUID,
        @PathVariable invoiceId: UUID,
        @RequestBody request: CreateCreditNoteRequest,
    ): CreditNoteResponse {
        return correctionService.createCreditNote(
            propertyId,
            invoiceId,
            request,
        )
    }
}
