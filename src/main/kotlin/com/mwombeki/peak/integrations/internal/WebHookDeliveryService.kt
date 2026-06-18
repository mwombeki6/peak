package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.integrations.api.WebhookPort
import com.mwombeki.peak.integrations.api.WebhookTriggerRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class WebhookDeliveryService(
    private val jdbcTemplate: JdbcTemplate
) : WebhookPort {

    @Transactional
    override fun sendWebhook(request: WebhookTriggerRequest) {
        val logId = UUID.randomUUID()

        // 1. Log the outbound webhook into your DB using raw JDBC
        jdbcTemplate.update(
            """
            INSERT INTO webhook_logs (id, property_id, event_type, payload, status)
            VALUES (?, ?, ?, ?, 'PENDING')
            """.trimIndent(),
            logId,
            request.propertyId,
            request.eventType,
            request.payload
        )

        // 2. Dummy execution (In a full setup, you'd use a RestTemplate/WebClient here)
        try {
            println("🚀 [Webhook] Sending ${request.eventType} for property ${request.propertyId}...")

            // If send succeeds:
            jdbcTemplate.update(
                "UPDATE webhook_logs SET status = 'DELIVERED' WHERE id = ?",
                logId
            )
        } catch (e: Exception) {
            // If send fails:
            jdbcTemplate.update(
                "UPDATE webhook_logs SET status = 'FAILED' WHERE id = ?",
                logId
            )
        }
    }
}