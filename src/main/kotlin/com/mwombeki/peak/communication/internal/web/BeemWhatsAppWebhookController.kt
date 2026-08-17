package com.mwombeki.peak.communication.internal.web

import com.mwombeki.peak.communication.api.BeemWhatsAppWebhookPort
import com.mwombeki.peak.communication.api.BeemWhatsAppWebhookReceipt
import java.util.UUID
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Beem Moja `callback_url`. Auth is the HMAC Peak minted into the path, not a
 * tenant header. The body is taken raw so classification sees what Beem sent.
 */
@RestController
@RequestMapping("/api/v1/communication/webhooks/beem/whatsapp")
class BeemWhatsAppWebhookController(
    private val beemWhatsAppWebhookPort: BeemWhatsAppWebhookPort,
) {
    @PostMapping(
        "/{transactionId}/{signature}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun receive(
        @PathVariable transactionId: UUID,
        @PathVariable signature: String,
        @RequestBody payload: String,
    ): BeemWhatsAppWebhookReceipt = beemWhatsAppWebhookPort.receive(
        transactionId = transactionId,
        signature = signature,
        payload = payload,
    )
}
