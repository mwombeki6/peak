package com.mwombeki.peak.communication.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
data class BeemWhatsAppWebhookReceipt(
    val accepted: Boolean,
    val kind: String,
    val deliveryRequestId: UUID? = null,
    val status: String? = null,
)

@NamedInterface("api")
interface BeemWhatsAppWebhookPort {
    fun receive(
        transactionId: UUID,
        signature: String,
        payload: String,
    ): BeemWhatsAppWebhookReceipt
}
