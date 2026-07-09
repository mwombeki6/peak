package com.mwombeki.peak.reporting.internal

import com.mwombeki.peak.nightaudit.api.NightAuditCloseSnapshotPort
import com.mwombeki.peak.nightaudit.api.NightAuditCloseSnapshotResponse
import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import com.mwombeki.peak.reporting.api.ObjectStoragePort
import com.mwombeki.peak.reporting.api.StoreReportObject
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import io.micrometer.core.instrument.MeterRegistry
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class ReportGenerationOutboxHandler(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val requestContextHolder: RequestContextHolder,
    private val objectMapper: ObjectMapper,
    private val closeSnapshotPort: NightAuditCloseSnapshotPort,
    private val renderer: DeterministicPdfRenderer,
    private val objectStoragePort: ObjectStoragePort,
    private val meterRegistry: MeterRegistry,
) : OutboxEventHandler {
    override val destination = OutboxDestination.REPORTS

    override fun supports(event: ClaimedOutboxEvent): Boolean =
        event.destination == destination &&
            event.eventType == GENERATION_REQUESTED

    override suspend fun handle(event: ClaimedOutboxEvent) {
        val tenantId = requireNotNull(event.tenantId)
        val propertyId = requireNotNull(event.propertyId)
        val identity = RequestIdentity.Public(
            tenantId = tenantId,
            propertyId = propertyId,
            correlationId = event.correlationId,
        )
        bindWorkerContext(identity, event)
        val work = try {
            transactionTemplate.execute {
                databaseSessionContext.bind(identity)
                prepare(event, tenantId, propertyId)
            } ?: return
        } finally {
            requestContextHolder.clear()
        }
        try {
            val bytes = renderer.render(work.reportCode, work.snapshot)
            check(bytes.size >= PDF_MAGIC.size)
            check(bytes.copyOf(PDF_MAGIC.size).contentEquals(PDF_MAGIC)) {
                "Generated report is not a PDF"
            }
            val contentHash = sha256Hex(bytes)
            val objectKey = objectKey(work)
            val stored = try {
                objectStoragePort.putIfAbsent(
                    StoreReportObject(
                        objectKey = objectKey,
                        bytes = bytes,
                        contentType = PDF_CONTENT_TYPE,
                        sha256 = contentHash,
                    ),
                )
            } catch (ex: Exception) {
                meterRegistry.counter(
                    "peak.reporting.storage.errors",
                    "operation",
                    "put",
                ).increment()
                throw ex
            }
            bindWorkerContext(identity, event)
            try {
                transactionTemplate.executeWithoutResult {
                    databaseSessionContext.bind(identity)
                    completeGeneration(
                        work = work,
                        objectKey = objectKey,
                        contentHash = contentHash,
                        contentLength = stored.contentLength,
                        etag = stored.etag,
                        event = event,
                    )
                }
            } finally {
                requestContextHolder.clear()
            }
            meterRegistry.counter(
                "peak.reporting.generation",
                "report_code",
                work.reportCode,
                "result",
                "generated",
            ).increment()
        } catch (ex: Exception) {
            bindWorkerContext(identity, event)
            try {
                transactionTemplate.executeWithoutResult {
                    databaseSessionContext.bind(identity)
                    jdbcTemplate.update(
                        """
                        UPDATE report_runs
                        SET status = 'failed',
                            failed_at = now(),
                            failure_reason = ?,
                            updated_at = now()
                        WHERE tenant_id = ? AND id = ?
                          AND status = 'running'
                        """.trimIndent(),
                        ex::class.simpleName ?: "ReportGenerationFailure",
                        tenantId,
                        work.runId,
                    )
                }
            } finally {
                requestContextHolder.clear()
            }
            meterRegistry.counter(
                "peak.reporting.generation",
                "report_code",
                work.reportCode,
                "result",
                "failed",
            ).increment()
            throw ex
        }
    }

    private fun prepare(
        event: ClaimedOutboxEvent,
        tenantId: UUID,
        propertyId: UUID,
    ): GenerationWork? {
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(
            event.payload,
            Map::class.java,
        ) as Map<String, Any?>
        val reportCode = payload.required("reportCode")
        require(reportCode in CORE_REPORT_CODES) {
            "Unsupported core report code"
        }
        val snapshotId = UUID.fromString(payload.required("closeSnapshotId"))
        val businessDate = LocalDate.parse(payload.required("businessDate"))
        val generationKey = payload.required("generationKey")
        val requestedRunId = payload["reportRunId"]?.toString()?.let(
            UUID::fromString,
        )
        val runId = requestedRunId ?: UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO report_runs (
                id, tenant_id, property_id, report_code, business_date,
                period_start, period_end, status, generation_key,
                close_snapshot_id, run_source
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'queued', ?, ?, 'night_audit')
            ON CONFLICT (generation_key) DO NOTHING
            """.trimIndent(),
            runId,
            tenantId,
            propertyId,
            reportCode,
            businessDate,
            businessDate,
            businessDate,
            generationKey,
            snapshotId,
        )
        val resolvedRun = jdbcTemplate.query(
            """
            SELECT id, status, run_source
            FROM report_runs
            WHERE tenant_id = ? AND generation_key = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                Triple(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("status"),
                    rs.getString("run_source"),
                )
            },
            tenantId,
            generationKey,
        ).single()
        if (resolvedRun.second == "generated") {
            return null
        }
        check(resolvedRun.second in setOf("queued", "failed", "running")) {
            "Report run cannot be generated from ${resolvedRun.second}"
        }
        jdbcTemplate.update(
            """
            UPDATE report_runs
            SET status = 'running',
                generation_attempts = generation_attempts + 1,
                last_attempt_at = now(),
                failed_at = NULL,
                failure_reason = NULL,
                updated_at = now()
            WHERE tenant_id = ? AND id = ?
            """.trimIndent(),
            tenantId,
            resolvedRun.first,
        )
        val snapshot = closeSnapshotPort.getCloseSnapshotById(
            tenantId,
            propertyId,
            snapshotId,
        ) ?: error("Immutable close snapshot was not found")
        check(snapshot.businessDate == businessDate) {
            "Report run business date does not match close snapshot"
        }
        return GenerationWork(
            tenantId = tenantId,
            propertyId = propertyId,
            runId = resolvedRun.first,
            reportCode = reportCode,
            businessDate = businessDate,
            snapshot = snapshot,
            runSource = resolvedRun.third,
        )
    }

    private fun completeGeneration(
        work: GenerationWork,
        objectKey: String,
        contentHash: String,
        contentLength: Long,
        etag: String?,
        event: ClaimedOutboxEvent,
    ) {
        val retentionDays = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(property_policy.retention_days,
                            tenant_policy.retention_days, 400)
            FROM (SELECT 1) seed
            LEFT JOIN reporting_retention_policies tenant_policy
              ON tenant_policy.tenant_id = ?
             AND tenant_policy.property_id IS NULL
            LEFT JOIN reporting_retention_policies property_policy
              ON property_policy.tenant_id = ?
             AND property_policy.property_id = ?
            """.trimIndent(),
            Int::class.java,
            work.tenantId,
            work.tenantId,
            work.propertyId,
        ) ?: SYSTEM_RETENTION_DAYS
        jdbcTemplate.update(
            """
            INSERT INTO report_artifacts (
                id, tenant_id, property_id, report_run_id, report_code,
                business_date, object_key, bucket_name, content_type,
                content_length, content_hash, storage_etag, retention_days,
                expires_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                now() + (? || ' days')::interval
            )
            ON CONFLICT (tenant_id, report_run_id) DO NOTHING
            """.trimIndent(),
            UUID.randomUUID(),
            work.tenantId,
            work.propertyId,
            work.runId,
            work.reportCode,
            work.businessDate,
            objectKey,
            objectStoragePort.bucketName,
            PDF_CONTENT_TYPE,
            contentLength,
            contentHash,
            etag,
            retentionDays,
            retentionDays,
        )
        val changed = jdbcTemplate.update(
            """
            UPDATE report_runs
            SET status = 'generated',
                storage_object_key = ?,
                content_hash = ?,
                generated_at = now(),
                failed_at = NULL,
                failure_reason = NULL,
                updated_at = now()
            WHERE tenant_id = ?
              AND id = ?
              AND status = 'running'
            """.trimIndent(),
            objectKey,
            contentHash,
            work.tenantId,
            work.runId,
        )
        check(changed == 1) { "Report generation completion raced" }
        if (work.runSource == "night_audit") {
            createDeliveries(work, event)
        }
    }

    private fun createDeliveries(
        work: GenerationWork,
        event: ClaimedOutboxEvent,
    ) {
        val recipientIds = jdbcTemplate.query(
            """
            SELECT recipient.id, recipient.contact_id,
                   recipient.contact_channel_id, channel.channel_type,
                   mask_contact_channel_address(
                       channel.channel_type, channel.address
                   ) AS destination_masked
            FROM report_subscriptions subscription
            JOIN report_subscription_recipients recipient
              ON recipient.tenant_id = subscription.tenant_id
             AND recipient.subscription_id = subscription.id
             AND recipient.is_enabled = true
            JOIN contact_channels channel
              ON channel.tenant_id = recipient.tenant_id
             AND channel.contact_id = recipient.contact_id
             AND channel.id = recipient.contact_channel_id
             AND channel.is_active = true
             AND channel.deleted_at IS NULL
             AND channel.verification_status = 'verified'
             AND channel.channel_type IN ('email', 'whatsapp')
            WHERE subscription.tenant_id = ?
              AND subscription.status = 'active'
              AND subscription.deleted_at IS NULL
              AND subscription.frequency = 'after_night_audit'
              AND subscription.report_code = ?
              AND (
                    subscription.property_id = ?
                    OR subscription.property_id IS NULL
                  )
              AND contact_channel_has_active_consent(
                    recipient.tenant_id,
                    recipient.contact_id,
                    recipient.contact_channel_id,
                    'operational_reports'
                  )
            ORDER BY recipient.id
            """.trimIndent(),
            { rs, _ ->
                DeliveryRecipient(
                    recipientId = rs.getObject("id", UUID::class.java),
                    contactId = rs.getObject(
                        "contact_id",
                        UUID::class.java,
                    ),
                    channelId = rs.getObject(
                        "contact_channel_id",
                        UUID::class.java,
                    ),
                    channel = rs.getString("channel_type"),
                    masked = rs.getString("destination_masked"),
                )
            },
            work.tenantId,
            work.reportCode,
            work.propertyId,
        )
        recipientIds.forEach { recipient ->
            val deliveryId = UUID.randomUUID()
            val deduplicationKey =
                "${work.runId}:${recipient.recipientId}"
            val inserted = jdbcTemplate.update(
                """
                INSERT INTO report_deliveries (
                    id, tenant_id, property_id, report_run_id,
                    subscription_recipient_id, contact_id,
                    contact_channel_id, channel_type, destination_masked,
                    status, deduplication_key, report_code,
                    link_expires_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, 'queued', ?, ?,
                    now() + interval '7 days'
                )
                ON CONFLICT (deduplication_key) DO NOTHING
                """.trimIndent(),
                deliveryId,
                work.tenantId,
                work.propertyId,
                work.runId,
                recipient.recipientId,
                recipient.contactId,
                recipient.channelId,
                recipient.channel,
                recipient.masked,
                deduplicationKey,
                work.reportCode,
            )
            if (inserted == 1) {
                enqueueDeliveryRequestedEvent(work, deliveryId, event)
            }
        }
    }

    private fun enqueueDeliveryRequestedEvent(
        work: GenerationWork,
        deliveryId: UUID,
        event: ClaimedOutboxEvent,
    ) {
        val correlationId = event.correlationId ?: event.id.toString()
        val headers = objectMapper.writeValueAsString(
            mapOf(
                "correlation_id" to correlationId,
                "request_method" to "WORKER",
                "request_path" to "/workers/reports",
            ),
        )
        jdbcTemplate.queryForObject(
            """
            SELECT enqueue_report_delivery_outbox_event(
                ?, ?, ?, ?, ?::jsonb, ?
            )
            """.trimIndent(),
            UUID::class.java,
            UUID.randomUUID(),
            work.tenantId,
            work.propertyId,
            deliveryId,
            headers,
            correlationId,
        )
    }

    private fun bindWorkerContext(
        identity: RequestIdentity.Public,
        event: ClaimedOutboxEvent,
    ) {
        requestContextHolder.set(
            RequestContext(
                identity = identity,
                correlationId = event.correlationId
                    ?: event.id.toString(),
                idempotencyKey = null,
                httpMethod = "WORKER",
                requestPath = "/workers/reports",
            ),
        )
    }

    private fun objectKey(work: GenerationWork): String =
        "tenant/${work.tenantId}/property/${work.propertyId}/reports/" +
            "${work.reportCode}/${work.businessDate}/${work.runId}.pdf"

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun Map<String, Any?>.required(key: String): String =
        get(key)?.toString()?.takeIf { it.isNotBlank() }
            ?: error("$key is required")

    private data class GenerationWork(
        val tenantId: UUID,
        val propertyId: UUID,
        val runId: UUID,
        val reportCode: String,
        val businessDate: LocalDate,
        val snapshot: NightAuditCloseSnapshotResponse,
        val runSource: String,
    )

    private data class DeliveryRecipient(
        val recipientId: UUID,
        val contactId: UUID,
        val channelId: UUID,
        val channel: String,
        val masked: String,
    )

    private companion object {
        const val GENERATION_REQUESTED = "report.generation.requested"
        const val DELIVERY_REQUESTED = "report.delivery.requested"
        const val PDF_CONTENT_TYPE = "application/pdf"
        const val SYSTEM_RETENTION_DAYS = 400
        val PDF_MAGIC = "%PDF".toByteArray()
        val CORE_REPORT_CODES = setOf(
            "daily_management_summary",
            "night_audit_close",
        )
    }
}
