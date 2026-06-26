package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.integrations.api.WebhookPort
import com.mwombeki.peak.integrations.api.WebhookTriggerRequest
import org.slf4j.LoggerFactory
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

        try {
            logger.info(
                "Dispatching webhook eventType={} propertyId={} logId={}",
                request.eventType,
                request.propertyId,
                logId,
            )

            jdbcTemplate.update(
                "UPDATE webhook_logs SET status = 'DELIVERED' WHERE id = ?",
                logId
            )
        } catch (e: Exception) {
            logger.warn(
                "Webhook dispatch failed eventType={} propertyId={} logId={}",
                request.eventType,
                request.propertyId,
                logId,
                e,
            )
            jdbcTemplate.update(
                "UPDATE webhook_logs SET status = 'FAILED' WHERE id = ?",
                logId
            )
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(WebhookDeliveryService::class.java)
    }
}
