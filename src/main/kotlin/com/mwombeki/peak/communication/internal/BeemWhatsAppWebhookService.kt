package com.mwombeki.peak.communication.internal

import com.mwombeki.peak.communication.api.BeemWhatsAppWebhookPort
import com.mwombeki.peak.communication.api.BeemWhatsAppWebhookReceipt
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Beem Moja delivery receipts. Inbound guest chat is acknowledged and discarded.
 */
@Service
class BeemWhatsAppWebhookService(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: BeemProperties,
) : BeemWhatsAppWebhookPort {

    override fun receive(
        transactionId: UUID,
        signature: String,
        payload: String,
    ): BeemWhatsAppWebhookReceipt {
        if (properties.secretKey.isBlank() ||
            !BeemWhatsAppCallback.matches(properties.secretKey, transactionId, signature)
        ) {
            throw IllegalArgumentException("Beem WhatsApp callback signature is invalid")
        }
        val root = try {
            objectMapper.readTree(payload)
        } catch (_: Exception) {
            throw IllegalArgumentException("Beem WhatsApp callback payload is not valid JSON")
        }
        return when (BeemWhatsAppCallback.classify(root)) {
            BeemWhatsAppCallbackKind.INBOUND_MESSAGE -> {
                logger.info(
                    "Discarded inbound Beem WhatsApp body for transactionId={} without storing content",
                    transactionId,
                )
                BeemWhatsAppWebhookReceipt(accepted = true, kind = "inbound_ignored")
            }
            BeemWhatsAppCallbackKind.DELIVERY_RECEIPT -> applyReceipt(transactionId, root)
            BeemWhatsAppCallbackKind.UNKNOWN -> {
                logger.info("Ignored unrecognised Beem WhatsApp callback transactionId={}", transactionId)
                BeemWhatsAppWebhookReceipt(accepted = true, kind = "ignored")
            }
        }
    }

    private fun applyReceipt(
        transactionId: UUID,
        payload: JsonNode,
    ): BeemWhatsAppWebhookReceipt {
        val receipt = BeemWhatsAppCallback.parseDelivery(payload)
        val status = when (receipt.deliveryState) {
            GuestWhatsAppDeliveryState.DELIVERED -> "delivered"
            GuestWhatsAppDeliveryState.FAILED -> "failed"
            GuestWhatsAppDeliveryState.SENDING -> "sending"
        }
        val updated = transactionTemplate.execute {
            val scope = jdbcTemplate.query(
                """
                SELECT tenant_id, delivery_request_id
                FROM resolve_beem_whatsapp_delivery_scope(?)
                """.trimIndent(),
                { rs, _ ->
                    rs.getObject("tenant_id", UUID::class.java) to
                        rs.getObject("delivery_request_id", UUID::class.java)
                },
                transactionId,
            ).firstOrNull() ?: return@execute null

            val (tenantId, deliveryRequestId) = scope
            databaseSessionContext.bind(RequestIdentity.Public(tenantId = tenantId))
            jdbcTemplate.update(
                """
                UPDATE communication_delivery_attempts
                SET status = CASE
                        WHEN ? = 'sending' THEN status
                        ELSE ?
                    END,
                    provider_message_id = COALESCE(?, provider_message_id),
                    error_message = CASE WHEN ? = 'failed' THEN 'Beem WhatsApp delivery failed' ELSE NULL END,
                    completed_at = CASE WHEN ? IN ('delivered', 'failed') THEN now() ELSE completed_at END
                WHERE tenant_id = ?
                  AND delivery_request_id = ?
                  AND outbox_event_id = ?
                """.trimIndent(),
                status,
                status,
                receipt.messageId,
                status,
                status,
                tenantId,
                deliveryRequestId,
                transactionId,
            )
            jdbcTemplate.update(
                """
                UPDATE communication_delivery_requests
                SET status = CASE
                        WHEN ? = 'sending' THEN status
                        ELSE ?
                    END,
                    delivered_at = CASE WHEN ? = 'delivered' THEN now() ELSE delivered_at END,
                    failed_at = CASE WHEN ? = 'failed' THEN now() ELSE failed_at END,
                    last_error = CASE
                        WHEN ? = 'failed' THEN 'Beem WhatsApp delivery failed'
                        WHEN ? = 'delivered' THEN NULL
                        ELSE last_error
                    END,
                    updated_at = now()
                WHERE id = ? AND tenant_id = ?
                """.trimIndent(),
                status,
                status,
                status,
                status,
                status,
                status,
                deliveryRequestId,
                tenantId,
            )
            deliveryRequestId
        }

        return BeemWhatsAppWebhookReceipt(
            accepted = true,
            kind = "delivery_receipt",
            deliveryRequestId = updated,
            status = status,
        )
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(BeemWhatsAppWebhookService::class.java)
    }
}
