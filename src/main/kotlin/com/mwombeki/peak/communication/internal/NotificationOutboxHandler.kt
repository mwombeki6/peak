package com.mwombeki.peak.communication.internal

import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import io.micrometer.core.instrument.MeterRegistry
import java.security.MessageDigest
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class NotificationOutboxHandler(
    private val deliveryProcessor: NotificationDeliveryProcessor,
) : OutboxEventHandler {
    override val destination = OutboxDestination.NOTIFICATION

    override suspend fun handle(event: ClaimedOutboxEvent) {
        deliveryProcessor.deliver(event)
    }
}

@Service
class NotificationDeliveryProcessor(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val providers: List<NotificationDeliveryProvider>,
    private val meterRegistry: ObjectProvider<MeterRegistry>,
) {
    fun deliver(event: ClaimedOutboxEvent) {
        val tenantId = requireNotNull(event.tenantId) {
            "Notification outbox events must be tenant scoped"
        }
        val payload = event.notificationPayload()
        val provider = providers.firstOrNull { it.supports(payload.channel) }
        val providerCode = provider?.providerCode ?: "unavailable"
        val work = startAttempt(
            tenantId = tenantId,
            event = event,
            payload = payload,
            providerCode = providerCode,
        )

        if (provider == null) {
            val ex = IllegalStateException("No communication provider registered for ${payload.channel}")
            finishFailed(tenantId, event, work, ex)
            throw ex
        }

        try {
            val result = provider.send(
                NotificationDeliveryCommand(
                    deliveryRequestId = work.deliveryRequestId,
                    outboxEventId = event.id,
                    tenantId = tenantId,
                    propertyId = event.propertyId,
                    channel = payload.channel,
                    recipient = payload.recipient,
                    subject = payload.subject,
                    content = payload.content,
                ),
            )
            finishDelivered(tenantId, event, work, result)
        } catch (ex: Exception) {
            finishFailed(tenantId, event, work, ex)
            throw ex
        }
    }

    private fun startAttempt(
        tenantId: UUID,
        event: ClaimedOutboxEvent,
        payload: NotificationPayload,
        providerCode: String,
    ): DeliveryWork {
        return requireNotNull(
            transactionTemplate.execute {
                bindTenant(tenantId)
                val deliveryRequestId = findOrCreateDeliveryRequest(tenantId, event, payload)
                jdbcTemplate.update(
                    """
                    INSERT INTO communication_delivery_attempts (
                        tenant_id,
                        delivery_request_id,
                        outbox_event_id,
                        attempt_number,
                        provider,
                        status
                    )
                    VALUES (?, ?, ?, ?, ?, 'sending')
                    ON CONFLICT (delivery_request_id, outbox_event_id, attempt_number)
                    DO UPDATE SET
                        provider = EXCLUDED.provider,
                        status = 'sending',
                        error_message = NULL,
                        completed_at = NULL
                    """.trimIndent(),
                    tenantId,
                    deliveryRequestId,
                    event.id,
                    event.attemptCount,
                    providerCode,
                )
                jdbcTemplate.update(
                    """
                    UPDATE communication_delivery_requests
                    SET status = 'sending',
                        attempt_count = GREATEST(attempt_count, ?),
                        max_attempts = ?,
                        failed_at = NULL,
                        last_error = NULL,
                        updated_at = now()
                    WHERE id = ? AND tenant_id = ?
                    """.trimIndent(),
                    event.attemptCount,
                    event.maxAttempts,
                    deliveryRequestId,
                    tenantId,
                )
                counter(
                    "peak.communication.delivery.attempts.started",
                    "channel",
                    payload.channel,
                    "provider",
                    providerCode,
                ).increment()
                DeliveryWork(
                    deliveryRequestId = deliveryRequestId,
                    channel = payload.channel,
                    recipientFingerprint = sha256Hex(payload.recipient),
                    provider = providerCode,
                )
            },
        )
    }

    private fun findOrCreateDeliveryRequest(
        tenantId: UUID,
        event: ClaimedOutboxEvent,
        payload: NotificationPayload,
    ): UUID {
        val existing = jdbcTemplate.query(
            """
            SELECT id
            FROM communication_delivery_requests
            WHERE tenant_id = ?
              AND deleted_at IS NULL
              AND (current_outbox_event_id = ? OR original_outbox_event_id = ?)
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            tenantId,
            event.id,
            event.id,
        ).firstOrNull()

        if (existing != null) {
            return existing
        }

        val deliveryRequestId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO communication_delivery_requests (
                id,
                tenant_id,
                property_id,
                original_outbox_event_id,
                current_outbox_event_id,
                channel_type,
                recipient,
                recipient_fingerprint,
                subject,
                content_fingerprint,
                max_attempts
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            deliveryRequestId,
            tenantId,
            event.propertyId,
            event.id,
            event.id,
            payload.channel,
            payload.recipient,
            sha256Hex(payload.recipient),
            payload.subject?.takeIf { it.isNotBlank() },
            sha256Hex(payload.content),
            event.maxAttempts,
        )
        return deliveryRequestId
    }

    private fun finishDelivered(
        tenantId: UUID,
        event: ClaimedOutboxEvent,
        work: DeliveryWork,
        result: NotificationDeliveryResult,
    ) {
        transactionTemplate.executeWithoutResult {
            bindTenant(tenantId)
            jdbcTemplate.update(
                """
                UPDATE communication_delivery_attempts
                SET status = 'delivered',
                    provider_message_id = ?,
                    error_message = NULL,
                    completed_at = now()
                WHERE tenant_id = ?
                  AND delivery_request_id = ?
                  AND outbox_event_id = ?
                  AND attempt_number = ?
                """.trimIndent(),
                result.providerMessageId,
                tenantId,
                work.deliveryRequestId,
                event.id,
                event.attemptCount,
            )
            jdbcTemplate.update(
                """
                UPDATE communication_delivery_requests
                SET status = 'delivered',
                    attempt_count = GREATEST(attempt_count, ?),
                    delivered_at = now(),
                    failed_at = NULL,
                    last_error = NULL,
                    updated_at = now()
                WHERE id = ? AND tenant_id = ?
                """.trimIndent(),
                event.attemptCount,
                work.deliveryRequestId,
                tenantId,
            )
        }
        counter(
            "peak.communication.delivery.attempts.delivered",
            "channel",
            work.channel,
            "provider",
            work.provider,
        ).increment()
        logger.info(
            "Delivered communication request {} outboxEventId={} channel={} provider={} recipientFingerprint={}",
            work.deliveryRequestId,
            event.id,
            work.channel,
            work.provider,
            work.recipientFingerprint,
        )
    }

    private fun finishFailed(
        tenantId: UUID,
        event: ClaimedOutboxEvent,
        work: DeliveryWork,
        ex: Exception,
    ) {
        val status = if (event.attemptCount >= event.maxAttempts) {
            "dead_letter"
        } else {
            "failed"
        }
        val message = (ex.message ?: ex::class.simpleName ?: "Provider delivery failed")
            .take(MAX_ERROR_MESSAGE_LENGTH)

        transactionTemplate.executeWithoutResult {
            bindTenant(tenantId)
            jdbcTemplate.update(
                """
                UPDATE communication_delivery_attempts
                SET status = ?,
                    error_message = ?,
                    completed_at = now()
                WHERE tenant_id = ?
                  AND delivery_request_id = ?
                  AND outbox_event_id = ?
                  AND attempt_number = ?
                """.trimIndent(),
                status,
                message,
                tenantId,
                work.deliveryRequestId,
                event.id,
                event.attemptCount,
            )
            jdbcTemplate.update(
                """
                UPDATE communication_delivery_requests
                SET status = ?,
                    attempt_count = GREATEST(attempt_count, ?),
                    failed_at = now(),
                    last_error = ?,
                    updated_at = now()
                WHERE id = ? AND tenant_id = ?
                """.trimIndent(),
                status,
                event.attemptCount,
                message,
                work.deliveryRequestId,
                tenantId,
            )
        }

        counter(
            "peak.communication.delivery.attempts.failed",
            "channel",
            work.channel,
            "provider",
            work.provider,
            "status",
            status,
        ).increment()
        logger.warn(
            "Communication delivery failed request={} outboxEventId={} channel={} provider={} status={} reason={}",
            work.deliveryRequestId,
            event.id,
            work.channel,
            work.provider,
            status,
            message,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun ClaimedOutboxEvent.notificationPayload(): NotificationPayload {
        val payload = objectMapper.readValue(this.payload, Map::class.java) as Map<String, Any?>
        val channel = payload["channel"]?.toString()?.canonicalChannel()
            ?: eventType.substringAfterLast('.').canonicalChannel()
        return NotificationPayload(
            channel = channel,
            recipient = payload["recipient"]?.toString()?.normalizedRequired("recipient")
                ?: throw IllegalArgumentException("Notification recipient is required"),
            subject = payload["subject"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            content = payload["content"]?.toString()?.normalizedRequired("content")
                ?: throw IllegalArgumentException("Notification content is required"),
        )
    }

    private fun bindTenant(tenantId: UUID) {
        databaseSessionContext.bind(RequestIdentity.Public(tenantId = tenantId))
    }

    private fun counter(
        name: String,
        vararg tags: String,
    ) = meterRegistry.getIfAvailable()?.counter(name, *tags)
        ?: fallbackMeterRegistry.counter(name, *tags)

    private fun String.canonicalChannel(): String {
        val value = trim().lowercase()
        require(value in ALLOWED_CHANNELS) {
            "Unsupported communication channel: $this"
        }
        return value
    }

    private fun String.normalizedRequired(fieldName: String): String {
        return trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("$fieldName is required")
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") {
            "%02x".format(it.toInt() and 0xff)
        }
    }

    private data class NotificationPayload(
        val channel: String,
        val recipient: String,
        val subject: String?,
        val content: String,
    )

    private data class DeliveryWork(
        val deliveryRequestId: UUID,
        val channel: String,
        val recipientFingerprint: String,
        val provider: String,
    )

    private companion object {
        private val logger = LoggerFactory.getLogger(NotificationDeliveryProcessor::class.java)
        private val fallbackMeterRegistry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        private val ALLOWED_CHANNELS = setOf("email", "sms", "whatsapp", "voice_phone")
        private const val MAX_ERROR_MESSAGE_LENGTH = 1000
    }
}

interface NotificationDeliveryProvider {
    val providerCode: String

    fun supports(channel: String): Boolean

    fun send(command: NotificationDeliveryCommand): NotificationDeliveryResult
}

data class NotificationDeliveryCommand(
    val deliveryRequestId: UUID,
    val outboxEventId: UUID,
    val tenantId: UUID,
    val propertyId: UUID?,
    val channel: String,
    val recipient: String,
    val subject: String?,
    val content: String,
)

data class NotificationDeliveryResult(
    val providerMessageId: String,
)

@Component
@ConditionalOnProperty(
    prefix = "peak.communication.delivery.local-provider",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class LocalNotificationDeliveryProvider : NotificationDeliveryProvider {
    override val providerCode = "local"

    override fun supports(channel: String): Boolean {
        return channel in SUPPORTED_CHANNELS
    }

    override fun send(command: NotificationDeliveryCommand): NotificationDeliveryResult {
        logger.info(
            "Locally accepted communication delivery request={} outboxEventId={} channel={} contentLength={}",
            command.deliveryRequestId,
            command.outboxEventId,
            command.channel,
            command.content.length,
        )
        return NotificationDeliveryResult(
            providerMessageId = "local-${UUID.randomUUID()}",
        )
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(LocalNotificationDeliveryProvider::class.java)
        private val SUPPORTED_CHANNELS = setOf("email", "sms", "whatsapp", "voice_phone")
    }
}
