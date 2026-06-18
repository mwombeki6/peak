package com.mwombeki.peak.integrations.api


interface WebhookPort {
    fun sendWebhook(request: WebhookTriggerRequest)
}