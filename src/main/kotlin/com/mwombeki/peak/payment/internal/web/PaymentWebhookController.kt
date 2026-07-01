package com.mwombeki.peak.payment.internal.web

import com.mwombeki.peak.payment.api.PaymentWebhookPort
import com.mwombeki.peak.payment.api.PaymentWebhookReceipt
import java.util.UUID
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/payments/webhooks")
class PaymentWebhookController(
    private val paymentWebhookPort: PaymentWebhookPort,
) {
    @PostMapping(
        "/{providerAccountId}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun receive(
        @PathVariable providerAccountId: UUID,
        @RequestHeader("X-Peak-Provider-Event-Id") providerEventId: String,
        @RequestHeader("X-Peak-Provider-Timestamp") timestamp: String,
        @RequestHeader("X-Peak-Provider-Signature") signature: String,
        @RequestBody payload: String,
    ): PaymentWebhookReceipt {
        return paymentWebhookPort.receive(
            providerAccountId = providerAccountId,
            providerEventId = providerEventId,
            timestamp = timestamp,
            signature = signature,
            payload = payload,
        )
    }
}
