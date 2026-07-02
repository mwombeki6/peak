package com.mwombeki.peak.fiscal.internal.web

import com.mwombeki.peak.fiscal.api.ConfigureFiscalProviderRequest
import com.mwombeki.peak.fiscal.api.FiscalNotFoundException
import com.mwombeki.peak.fiscal.api.FiscalPort
import com.mwombeki.peak.fiscal.api.FiscalProviderConfigResponse
import com.mwombeki.peak.fiscal.api.FiscalReceiptResponse
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/fiscal")
class FiscalController(
    private val fiscalPort: FiscalPort,
) {
    @PostMapping("/provider-configs")
    fun configureProvider(
        @PathVariable propertyId: UUID,
        @RequestBody request: ConfigureFiscalProviderRequest,
    ): FiscalProviderConfigResponse = fiscalPort.configureProvider(propertyId, request)

    @GetMapping("/provider-configs")
    fun listProviderConfigs(
        @PathVariable propertyId: UUID,
    ): List<FiscalProviderConfigResponse> = fiscalPort.listProviderConfigs(propertyId)

    @GetMapping("/receipts")
    fun listReceipts(
        @PathVariable propertyId: UUID,
        @RequestParam(defaultValue = "100") limit: Int,
    ): List<FiscalReceiptResponse> = fiscalPort.listReceipts(propertyId, limit)

    @GetMapping("/receipts/{receiptId}")
    fun getReceipt(
        @PathVariable propertyId: UUID,
        @PathVariable receiptId: UUID,
    ): FiscalReceiptResponse {
        return fiscalPort.getReceipt(propertyId, receiptId)
            ?: throw FiscalNotFoundException("Fiscal receipt was not found")
    }

    @PostMapping("/receipts/{receiptId}/retry")
    fun retryReceipt(
        @PathVariable propertyId: UUID,
        @PathVariable receiptId: UUID,
    ): FiscalReceiptResponse = fiscalPort.retryReceipt(propertyId, receiptId)
}
