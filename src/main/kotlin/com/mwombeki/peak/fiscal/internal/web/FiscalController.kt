package com.mwombeki.peak.fiscal.internal.web

import com.mwombeki.peak.fiscal.api.FiscalReceiptResponse
import com.mwombeki.peak.fiscal.api.FiscalSubmissionRequest
import com.mwombeki.peak.fiscal.internal.FiscalService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/fiscal")
class FiscalController(
    private val fiscalService: FiscalService
) {
    @PostMapping("/submit")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER')")
    fun submit(
        @PathVariable propertyId: UUID,
        @RequestBody request: FiscalSubmissionRequest
    ): ResponseEntity<Map<String, UUID>> {
        val receiptId = fiscalService.submitInvoice(request.copy(propertyId = propertyId))
        return ResponseEntity.ok(mapOf("receiptId" to receiptId))
    }

    @GetMapping("/receipts/{id}")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER', 'ROLE_CASHIER')")
    fun getReceipt(
        @PathVariable propertyId: UUID,
        @PathVariable id: UUID
    ): ResponseEntity<FiscalReceiptResponse> {
        return ResponseEntity.ok(fiscalService.getReceipt(id))
    }
}
