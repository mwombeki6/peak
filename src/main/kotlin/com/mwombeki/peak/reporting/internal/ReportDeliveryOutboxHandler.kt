package com.mwombeki.peak.reporting.internal

import com.mwombeki.peak.communication.api.DeliverReportLinkCommand
import com.mwombeki.peak.communication.api.ReportLinkDeliveryPort
import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import com.mwombeki.peak.reporting.api.ObjectStoragePort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class ReportDeliveryOutboxHandler(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val objectStoragePort: ObjectStoragePort,
    private val reportLinkDeliveryPort: ReportLinkDeliveryPort,
    private val meterRegistry: MeterRegistry,
) : OutboxEventHandler {
    override val destination = OutboxDestination.REPORTS

    override fun supports(event: ClaimedOutboxEvent): Boolean =
        event.destination == destination &&
            event.eventType == DELIVERY_REQUESTED

    override suspend fun handle(event: ClaimedOutboxEvent) {
        val tenantId = requireNotNull(event.tenantId)
        val propertyId = requireNotNull(event.propertyId)
        val deliveryId = requireNotNull(event.aggregateId)
        val identity = RequestIdentity.Public(
            tenantId = tenantId,
            propertyId = propertyId,
            correlationId = event.correlationId,
        )
        val work = transactionTemplate.execute {
            databaseSessionContext.bind(identity)
            prepare(tenantId, propertyId, deliveryId)
        } ?: return
        val expiresAt = Instant.now().plus(DELIVERY_LINK_EXPIRY)
        val expiresAtForJdbc = Timestamp.from(expiresAt)
        try {
            val signedUrl = objectStoragePort.presignedGet(
                work.objectKey,
                DELIVERY_LINK_EXPIRY,
            )
            val result = reportLinkDeliveryPort.deliver(
                DeliverReportLinkCommand(
                    tenantId = tenantId,
                    propertyId = propertyId,
                    reportDeliveryId = deliveryId,
                    contactId = work.contactId,
                    contactChannelId = work.channelId,
                    reportCode = work.reportCode,
                    businessDate = work.businessDate,
                    signedUrl = signedUrl,
                    expiresAt = expiresAt,
                ),
            )
            transactionTemplate.executeWithoutResult {
                databaseSessionContext.bind(identity)
                jdbcTemplate.update(
                    """
                    INSERT INTO report_delivery_attempts (
                        id, tenant_id, property_id, report_delivery_id,
                        attempt_number, channel_type, provider_code,
                        provider_message_id, status, link_expires_at,
                        completed_at
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?, 'sent', ?, now()
                    )
                    """.trimIndent(),
                    UUID.randomUUID(),
                    tenantId,
                    propertyId,
                    deliveryId,
                    work.attemptNumber,
                    result.channelType,
                    result.providerCode,
                    result.providerMessageId,
                    expiresAtForJdbc,
                )
                jdbcTemplate.update(
                    """
                    UPDATE report_deliveries
                    SET status = 'sent',
                        provider_code = ?,
                        provider_message_id = ?,
                        destination_masked = ?,
                        link_expires_at = ?,
                        sent_at = now(),
                        failed_at = NULL,
                        last_error_code = NULL,
                        last_error_message = NULL,
                        updated_at = now()
                    WHERE tenant_id = ? AND id = ?
                    """.trimIndent(),
                    result.providerCode,
                    result.providerMessageId,
                    result.destinationMasked,
                    expiresAtForJdbc,
                    tenantId,
                    deliveryId,
                )
            }
            meterRegistry.counter(
                "peak.reporting.delivery",
                "channel",
                result.channelType,
                "result",
                "sent",
            ).increment()
        } catch (ex: Exception) {
            val attemptRecordingFailure = runCatching {
                transactionTemplate.executeWithoutResult {
                    databaseSessionContext.bind(identity)
                    val terminal = work.attemptNumber >= work.maxAttempts
                    val state = if (terminal) "dead_letter" else "failed"
                    val errorCode = ex::class.simpleName
                        ?.take(100)
                        ?: "ReportDeliveryFailure"
                    jdbcTemplate.update(
                        """
                        INSERT INTO report_delivery_attempts (
                            id, tenant_id, property_id, report_delivery_id,
                            attempt_number, channel_type, status, error_code,
                            error_message, link_expires_at, completed_at
                        ) VALUES (
                            ?, ?, ?, ?, ?, ?, 'failed', ?,
                            'Report link delivery failed', ?, now()
                        )
                        """.trimIndent(),
                        UUID.randomUUID(),
                        tenantId,
                        propertyId,
                        deliveryId,
                        work.attemptNumber,
                        work.channel,
                        errorCode,
                        expiresAtForJdbc,
                    )
                    jdbcTemplate.update(
                        """
                        UPDATE report_deliveries
                        SET status = ?,
                            link_expires_at = ?,
                            failed_at = now(),
                            last_error_code = ?,
                            last_error_message = 'Report link delivery failed',
                            updated_at = now()
                        WHERE tenant_id = ? AND id = ?
                        """.trimIndent(),
                        state,
                        expiresAtForJdbc,
                        errorCode,
                        tenantId,
                        deliveryId,
                    )
                }
            }.exceptionOrNull()
            if (attemptRecordingFailure != null) {
                ex.addSuppressed(attemptRecordingFailure)
            }
            meterRegistry.counter(
                "peak.reporting.delivery",
                "channel",
                work.channel,
                "result",
                "failed",
            ).increment()
            throw ex
        }
    }

    private fun prepare(
        tenantId: UUID,
        propertyId: UUID,
        deliveryId: UUID,
    ): DeliveryWork? {
        val work = jdbcTemplate.query(
            """
            SELECT delivery.status, delivery.attempt_count,
                   delivery.max_attempts, delivery.contact_id,
                   delivery.contact_channel_id, delivery.channel_type,
                   delivery.report_code, run.business_date,
                   artifact.object_key
            FROM report_deliveries delivery
            JOIN report_runs run
              ON run.tenant_id = delivery.tenant_id
             AND run.id = delivery.report_run_id
            JOIN report_artifacts artifact
              ON artifact.tenant_id = run.tenant_id
             AND artifact.report_run_id = run.id
            WHERE delivery.tenant_id = ?
              AND delivery.property_id = ?
              AND delivery.id = ?
              AND artifact.object_deleted_at IS NULL
              AND artifact.expires_at > now()
            FOR UPDATE OF delivery
            """.trimIndent(),
            { rs, _ ->
                DeliveryWork(
                    status = rs.getString("status"),
                    attemptNumber = rs.getInt("attempt_count") + 1,
                    maxAttempts = rs.getInt("max_attempts"),
                    contactId = rs.getObject(
                        "contact_id",
                        UUID::class.java,
                    ),
                    channelId = rs.getObject(
                        "contact_channel_id",
                        UUID::class.java,
                    ),
                    channel = rs.getString("channel_type"),
                    reportCode = rs.getString("report_code"),
                    businessDate = rs.getObject(
                        "business_date",
                        LocalDate::class.java,
                    ),
                    objectKey = rs.getString("object_key"),
                )
            },
            tenantId,
            propertyId,
            deliveryId,
        ).singleOrNull() ?: error(
            "Retained report delivery artifact was not found",
        )
        if (work.status in setOf("sent", "delivered", "cancelled")) {
            return null
        }
        check(
            work.status in setOf(
                "queued", "failed", "retry_scheduled", "sending",
            ),
        ) {
            "Report delivery cannot be attempted from ${work.status}"
        }
        check(work.attemptNumber <= work.maxAttempts) {
            "Report delivery exhausted its controlled retries"
        }
        jdbcTemplate.update(
            """
            UPDATE report_deliveries
            SET status = 'sending',
                attempt_count = ?,
                updated_at = now()
            WHERE tenant_id = ? AND id = ?
            """.trimIndent(),
            work.attemptNumber,
            tenantId,
            deliveryId,
        )
        return work
    }

    private data class DeliveryWork(
        val status: String,
        val attemptNumber: Int,
        val maxAttempts: Int,
        val contactId: UUID,
        val channelId: UUID,
        val channel: String,
        val reportCode: String,
        val businessDate: LocalDate,
        val objectKey: String,
    )

    private companion object {
        const val DELIVERY_REQUESTED = "report.delivery.requested"
        val DELIVERY_LINK_EXPIRY: Duration = Duration.ofDays(7)
    }
}
