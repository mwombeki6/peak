package com.mwombeki.peak.billing.internal.web

import com.mwombeki.peak.billing.api.BillingNotFoundException
import com.mwombeki.peak.billing.api.BillingPort
import com.mwombeki.peak.billing.api.BillingMutationReceipt
import com.mwombeki.peak.billing.api.FolioResponse
import com.mwombeki.peak.billing.api.InvoiceResponse
import com.mwombeki.peak.billing.api.IssueInvoiceRequest
import com.mwombeki.peak.billing.api.PostChargeRequest
import com.mwombeki.peak.billing.api.PostPaymentRequest
import com.mwombeki.peak.billing.api.ReverseChargeRequest
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}")
class BillingController(
    private val billingPort: BillingPort,
) {
    @GetMapping("/folios")
    fun listFolios(@PathVariable propertyId: UUID): List<FolioResponse> {
        return billingPort.listFolios(propertyId)
    }

    @GetMapping("/folios/{folioId}")
    fun getFolio(
        @PathVariable propertyId: UUID,
        @PathVariable folioId: UUID,
    ): FolioResponse {
        return billingPort.getFolio(propertyId, folioId)
            ?: throw BillingNotFoundException("Folio was not found")
    }

    @PostMapping("/folios/{folioId}/charges")
    fun postCharge(
        @PathVariable propertyId: UUID,
        @PathVariable folioId: UUID,
        @RequestBody request: PostChargeRequest,
    ): BillingMutationReceipt {
        return billingPort.postCharge(propertyId, folioId, request)
    }

    @PostMapping("/folios/{folioId}/payments")
    fun postPayment(
        @PathVariable propertyId: UUID,
        @PathVariable folioId: UUID,
        @RequestBody request: PostPaymentRequest,
    ): BillingMutationReceipt {
        return billingPort.postPayment(propertyId, folioId, request)
    }

    @PostMapping("/folios/{folioId}/charges/{chargeId}/reverse")
    fun reverseCharge(
        @PathVariable propertyId: UUID,
        @PathVariable folioId: UUID,
        @PathVariable chargeId: UUID,
        @RequestBody request: ReverseChargeRequest,
    ): BillingMutationReceipt {
        return billingPort.reverseCharge(propertyId, folioId, chargeId, request)
    }

    @PostMapping("/folios/{folioId}/invoice")
    fun issueInvoice(
        @PathVariable propertyId: UUID,
        @PathVariable folioId: UUID,
        @RequestBody request: IssueInvoiceRequest,
    ): InvoiceResponse {
        return billingPort.issueInvoice(propertyId, folioId, request)
    }

    @GetMapping("/invoices")
    fun listInvoices(@PathVariable propertyId: UUID): List<InvoiceResponse> {
        return billingPort.listInvoices(propertyId)
    }

    @GetMapping("/invoices/{invoiceId}")
    fun getInvoice(
        @PathVariable propertyId: UUID,
        @PathVariable invoiceId: UUID,
    ): InvoiceResponse {
        return billingPort.getInvoice(propertyId, invoiceId)
            ?: throw BillingNotFoundException("Invoice was not found")
    }
}
