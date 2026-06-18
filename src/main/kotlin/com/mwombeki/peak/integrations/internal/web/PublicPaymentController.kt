package com.mwombeki.peak.integrations.internal.web

import com.mwombeki.peak.integrations.api.InitiatePaymentRequest
import com.mwombeki.peak.integrations.api.PaymentPort
import com.mwombeki.peak.integrations.api.PaymentStatusResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/public/payments")
class PublicPaymentController(
    private val paymentPort: PaymentPort
) {

    @PostMapping("/initiate")
    fun initiatePayment(
        @RequestBody request: InitiatePaymentRequest
    ): ResponseEntity<PaymentStatusResponse> {
        val response = paymentPort.initiatePayment(request)
        return ResponseEntity.ok(response)
    }
}
