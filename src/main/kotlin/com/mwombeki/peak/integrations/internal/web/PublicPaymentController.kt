package com.mwombeki.peak.integrations.internal.web

import com.mwombeki.peak.integrations.api.InitiatePaymentRequest
import com.mwombeki.peak.integrations.api.PaymentPort
import com.mwombeki.peak.integrations.api.PaymentStatusResponse
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/public/properties/{propertyId}/booking-engine/payments")
class PublicPaymentController(
    private val paymentPort: PaymentPort
) {

    @PostMapping("/initiate")
    fun initiatePayment(
        @PathVariable propertyId: UUID,
        @RequestBody request: InitiatePaymentRequest
    ): ResponseEntity<PaymentStatusResponse> {
        val response = paymentPort.initiatePayment(propertyId, request)
        return ResponseEntity.ok(response)
    }
}
