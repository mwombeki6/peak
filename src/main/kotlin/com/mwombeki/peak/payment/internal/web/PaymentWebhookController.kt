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
    /**
     * The original ClickPesa path, kept because it is registered with the provider and a
     * callback URL cannot be changed unilaterally.
     */
    @PostMapping(
        "/clickpesa/{providerAccountId}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun receiveClickPesa(
        @PathVariable providerAccountId: UUID,
        @RequestHeader headers: Map<String, String>,
        @RequestBody payload: String,
    ): PaymentWebhookReceipt = receive(providerAccountId, headers, payload)

    /**
     * Any provider, addressed by the property's own account.
     *
     * The account is what identifies the hotel and carries its credentials, so the path
     * needs nothing else: the provider is looked up from it rather than trusted from the
     * URL. `providerCode` is present only so a provider that insists on a distinct callback
     * URL per integration can be given one.
     *
     * The body is taken raw because a signature covers the exact bytes; letting Jackson
     * round-trip it first would verify a re-serialisation rather than what was sent.
     */
    @PostMapping(
        "/{providerCode}/accounts/{providerAccountId}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun receiveForProvider(
        @PathVariable providerCode: String,
        @PathVariable providerAccountId: UUID,
        @RequestHeader headers: Map<String, String>,
        @RequestBody payload: String,
    ): PaymentWebhookReceipt = receive(providerAccountId, headers, payload)

    private fun receive(
        providerAccountId: UUID,
        headers: Map<String, String>,
        payload: String,
    ): PaymentWebhookReceipt = paymentWebhookPort.receive(
        providerAccountId = providerAccountId,
        payload = payload,
        headers = headers,
    )
}
