package com.mwombeki.peak.reporting.internal

import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.reporting.api.AddReportRecipientRequest
import com.mwombeki.peak.reporting.api.CreateReportRunRequest
import com.mwombeki.peak.reporting.api.CreateReportSubscriptionRequest
import com.mwombeki.peak.reporting.api.ReportCatalogResponse
import com.mwombeki.peak.reporting.api.ReportDeliveryAttemptResponse
import com.mwombeki.peak.reporting.api.ReportDeliveryResponse
import com.mwombeki.peak.reporting.api.ReportDeliveryState
import com.mwombeki.peak.reporting.api.ReportDownloadLinkResponse
import com.mwombeki.peak.reporting.api.ReportRecipientResponse
import com.mwombeki.peak.reporting.api.ReportingConflictException
import com.mwombeki.peak.reporting.api.ReportingNotFoundException
import com.mwombeki.peak.reporting.api.ReportingPort
import com.mwombeki.peak.reporting.api.ReportingSettingsResponse
import com.mwombeki.peak.reporting.api.ReportRunResponse
import com.mwombeki.peak.reporting.api.ReportRunState
import com.mwombeki.peak.reporting.api.ReportSubscriptionResponse
import com.mwombeki.peak.reporting.api.ReportSubscriptionState
import com.mwombeki.peak.reporting.api.UpdateReportingSettingsRequest
import com.mwombeki.peak.reporting.api.UpdateReportSubscriptionRequest
import com.mwombeki.peak.shared.context.TenantActor
import com.mwombeki.peak.shared.context.TenantRequestContext
import com.mwombeki.peak.shared.outbound.ObjectStoragePort
import java.sql.ResultSet
import java.sql.Time
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class ReportingService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val tenantRequestContext: TenantRequestContext,
    private val outboxPort: OutboxPort,
    private val objectStoragePort: ObjectStoragePort,
) : ReportingPort {
    override fun tenantSettings(tenantId: UUID): ReportingSettingsResponse =
        readTenant(tenantId) { resolvedSettings(it.tenantId, null) }

    override fun updateTenantSettings(
        tenantId: UUID,
        request: UpdateReportingSettingsRequest,
    ): ReportingSettingsResponse = writeTenant(tenantId) { actor ->
        upsertSettings(actor, null, request.retentionDays)
    }

    override fun propertySettings(
        propertyId: UUID,
    ): ReportingSettingsResponse = readProperty(propertyId) { actor ->
        resolvedSettings(actor.tenantId, propertyId)
    }

    override fun updatePropertySettings(
        propertyId: UUID,
        request: UpdateReportingSettingsRequest,
    ): ReportingSettingsResponse = writeProperty(propertyId) { actor ->
        upsertSettings(actor, propertyId, request.retentionDays)
    }

    override fun catalog(tenantId: UUID): List<ReportCatalogResponse> =
        readTenant(tenantId) {
            jdbcTemplate.query(
                """
                SELECT report_code, name, description, scope,
                       sensitivity_level, generator_available,
                       supports_email, supports_whatsapp
                FROM report_catalog
                WHERE is_active = true
                ORDER BY display_order, report_code
                """.trimIndent(),
                { rs, _ ->
                    ReportCatalogResponse(
                        reportCode = rs.getString("report_code"),
                        name = rs.getString("name"),
                        description = rs.getString("description"),
                        scope = rs.getString("scope"),
                        sensitivity = rs.getString("sensitivity_level"),
                        generatorAvailable = rs.getBoolean(
                            "generator_available",
                        ),
                        supportsEmail = rs.getBoolean("supports_email"),
                        supportsWhatsApp = rs.getBoolean(
                            "supports_whatsapp",
                        ),
                    )
                },
            )
        }

    override fun listSubscriptions(
        tenantId: UUID?,
        propertyId: UUID?,
    ): List<ReportSubscriptionResponse> {
        require((tenantId == null) xor (propertyId == null)) {
            "Exactly one reporting scope is required"
        }
        return if (tenantId != null) {
            readTenant(tenantId) { actor ->
                subscriptions(actor.tenantId, propertyId = null)
            }
        } else {
            readProperty(requireNotNull(propertyId)) { actor ->
                subscriptions(actor.tenantId, propertyId)
            }
        }
    }

    override fun listAllSubscriptionsForTenant(
        tenantId: UUID,
    ): List<ReportSubscriptionResponse> = readTenant(tenantId) { actor ->
        jdbcTemplate.query(
            """
            $SUBSCRIPTION_SELECT
            WHERE subscription.tenant_id = ?
              AND subscription.deleted_at IS NULL
            ORDER BY subscription.subscription_name
            """.trimIndent(),
            { rs, _ -> mapSubscription(rs) },
            actor.tenantId,
        )
    }

    override fun createSubscription(
        tenantId: UUID?,
        propertyId: UUID?,
        request: CreateReportSubscriptionRequest,
    ): ReportSubscriptionResponse {
        require((tenantId == null) xor (propertyId == null)) {
            "Exactly one reporting scope is required"
        }
        return if (tenantId != null) {
            writeTenant(tenantId) { actor ->
                createSubscription(actor, null, request)
            }
        } else {
            writeProperty(requireNotNull(propertyId)) { actor ->
                createSubscription(actor, propertyId, request)
            }
        }
    }

    override fun updateSubscription(
        subscriptionId: UUID,
        request: UpdateReportSubscriptionRequest,
        propertyId: UUID?,
    ): ReportSubscriptionResponse = writeSubscriptionScope(propertyId) { actor ->
        val changed = jdbcTemplate.update(
            """
            UPDATE report_subscriptions
            SET subscription_name = ?,
                frequency = ?,
                schedule_time = ?,
                timezone = ?,
                language_code = ?,
                updated_at = now()
            WHERE tenant_id = ?
              AND id = ?
              ${propertyPredicate(propertyId)}
              AND deleted_at IS NULL
              AND status <> 'archived'
            """.trimIndent(),
            request.subscriptionName.normalizedName(),
            request.frequency.normalizedFrequency(),
            request.scheduleTime?.let(Time::valueOf),
            request.timezone.normalizedTimezone(),
            request.languageCode.normalizedLanguage(),
            actor.tenantId,
            subscriptionId,
            *propertyArgs(propertyId),
        )
        if (changed != 1) {
            throw ReportingNotFoundException(
                "Active report subscription was not found",
            )
        }
        requireSubscription(actor.tenantId, subscriptionId, propertyId = propertyId)
    }

    override fun transitionSubscription(
        subscriptionId: UUID,
        action: String,
        propertyId: UUID?,
    ): ReportSubscriptionResponse = writeSubscriptionScope(propertyId) { actor ->
        val target = when (action.lowercase()) {
            "pause" -> "paused"
            "resume" -> "active"
            "archive" -> "archived"
            else -> throw IllegalArgumentException(
                "Unsupported report subscription action",
            )
        }
        val changed = jdbcTemplate.update(
            """
            UPDATE report_subscriptions
            SET status = ?,
                deleted_at = CASE WHEN ? = 'archived' THEN now()
                                  ELSE deleted_at END,
                updated_at = now()
            WHERE tenant_id = ?
              AND id = ?
              ${propertyPredicate(propertyId)}
              AND deleted_at IS NULL
              AND status <> 'archived'
            """.trimIndent(),
            target,
            target,
            actor.tenantId,
            subscriptionId,
            *propertyArgs(propertyId),
        )
        if (changed != 1) {
            throw ReportingNotFoundException(
                "Active report subscription was not found",
            )
        }
        requireSubscription(
            actor.tenantId,
            subscriptionId,
            includeArchived = true,
            propertyId = propertyId,
        )
    }

    override fun addRecipient(
        subscriptionId: UUID,
        request: AddReportRecipientRequest,
        propertyId: UUID?,
    ): ReportSubscriptionResponse = writeSubscriptionScope(propertyId) { actor ->
        requireSubscription(actor.tenantId, subscriptionId, propertyId = propertyId)
        try {
            jdbcTemplate.update(
                """
                INSERT INTO report_subscription_recipients (
                    id, tenant_id, subscription_id, contact_id,
                    contact_channel_id, delivery_format, is_enabled
                ) VALUES (?, ?, ?, ?, ?, 'pdf', true)
                ON CONFLICT (
                    tenant_id, subscription_id, contact_id, contact_channel_id
                ) WHERE is_enabled DO UPDATE SET
                    is_enabled = true,
                    updated_at = now()
                """.trimIndent(),
                UUID.randomUUID(),
                actor.tenantId,
                subscriptionId,
                request.contactId,
                request.contactChannelId,
            )
        } catch (ex: DataIntegrityViolationException) {
            throw ReportingConflictException(
                "Recipient must use a verified email or WhatsApp channel with active operational_reports consent",
            )
        }
        requireSubscription(actor.tenantId, subscriptionId, propertyId = propertyId)
    }

    override fun disableRecipient(
        subscriptionId: UUID,
        recipientId: UUID,
        propertyId: UUID?,
    ): ReportSubscriptionResponse = writeSubscriptionScope(propertyId) { actor ->
        requireSubscription(actor.tenantId, subscriptionId, propertyId = propertyId)
        val changed = jdbcTemplate.update(
            """
            UPDATE report_subscription_recipients
            SET is_enabled = false, updated_at = now()
            WHERE tenant_id = ?
              AND subscription_id = ?
              AND id = ?
              AND is_enabled = true
            """.trimIndent(),
            actor.tenantId,
            subscriptionId,
            recipientId,
        )
        if (changed != 1) {
            throw ReportingNotFoundException(
                "Enabled report recipient was not found",
            )
        }
        requireSubscription(actor.tenantId, subscriptionId, propertyId = propertyId)
    }

    override fun createRun(
        propertyId: UUID,
        reportCode: String,
        request: CreateReportRunRequest,
    ): ReportRunResponse = writeProperty(propertyId) { actor ->
        val normalizedCode = reportCode.normalizedCode()
        requireGenerator(normalizedCode)
        val snapshot = jdbcTemplate.query(
            """
            SELECT id, business_date
            FROM night_audit_close_snapshots
            WHERE tenant_id = ?
              AND property_id = ?
              AND business_date = COALESCE(
                    ?,
                    (
                        SELECT max(business_date)
                        FROM night_audit_close_snapshots
                        WHERE tenant_id = ? AND property_id = ?
                    )
                  )
            """.trimIndent(),
            { rs, _ ->
                rs.getObject("id", UUID::class.java) to
                    rs.getObject("business_date", LocalDate::class.java)
            },
            actor.tenantId,
            propertyId,
            request.businessDate,
            actor.tenantId,
            propertyId,
        ).singleOrNull() ?: throw ReportingConflictException(
            "A completed night-audit close snapshot is required",
        )
        val generationKey =
            "manual:$propertyId:${snapshot.second}:$normalizedCode"
        val runId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO report_runs (
                id, tenant_id, property_id, report_code, business_date,
                period_start, period_end, status, generation_key,
                close_snapshot_id, requested_by, run_source
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'queued', ?, ?, ?, 'manual')
            ON CONFLICT (generation_key) DO NOTHING
            """.trimIndent(),
            runId,
            actor.tenantId,
            propertyId,
            normalizedCode,
            snapshot.second,
            snapshot.second,
            snapshot.second,
            generationKey,
            snapshot.first,
            actor.tenantUserId,
        )
        val existing = jdbcTemplate.queryForObject(
            "SELECT id FROM report_runs WHERE generation_key = ?",
            UUID::class.java,
            generationKey,
        ) ?: runId
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "report_runs",
                aggregateId = existing,
                tenantId = actor.tenantId,
                propertyId = propertyId,
                eventType = "report.generation.requested",
                destination = OutboxDestination.REPORTS,
                payload = mapOf(
                    "reportRunId" to existing,
                    "closeSnapshotId" to snapshot.first,
                    "reportCode" to normalizedCode,
                    "businessDate" to snapshot.second,
                    "generationKey" to generationKey,
                ),
                priority = 2,
            ),
        )
        requireRun(actor.tenantId, existing)
    }

    override fun listRuns(tenantId: UUID): List<ReportRunResponse> =
        readTenant(tenantId) { actor ->
            jdbcTemplate.query(
                "$RUN_SELECT WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 200",
                { rs, _ -> mapRun(rs) },
                actor.tenantId,
            )
        }

    override fun getRun(tenantId: UUID, runId: UUID): ReportRunResponse =
        readTenant(tenantId) { actor -> requireRun(actor.tenantId, runId) }

    override fun downloadLink(
        tenantId: UUID,
        runId: UUID,
    ): ReportDownloadLinkResponse = readTenant(tenantId) { actor ->
        val objectKey = jdbcTemplate.query(
            """
            SELECT artifact.object_key
            FROM report_runs run
            JOIN report_artifacts artifact
              ON artifact.tenant_id = run.tenant_id
             AND artifact.report_run_id = run.id
            WHERE run.tenant_id = ?
              AND run.id = ?
              AND run.status = 'generated'
              AND artifact.object_deleted_at IS NULL
              AND artifact.expires_at > now()
            """.trimIndent(),
            { rs, _ -> rs.getString("object_key") },
            actor.tenantId,
            runId,
        ).singleOrNull() ?: throw ReportingNotFoundException(
            "Retained generated report artifact was not found",
        )
        val expiresAt = Instant.now().plus(AUTHENTICATED_LINK_EXPIRY)
        ReportDownloadLinkResponse(
            url = objectStoragePort.presignedGet(
                objectKey,
                AUTHENTICATED_LINK_EXPIRY,
            ),
            expiresAt = expiresAt,
        )
    }

    override fun deliveries(
        tenantId: UUID,
        runId: UUID,
    ): List<ReportDeliveryResponse> = readTenant(tenantId) { actor ->
        requireRun(actor.tenantId, runId)
        jdbcTemplate.query(
            """
            SELECT id, report_run_id, report_code, channel_type,
                   destination_masked, status, attempt_count, link_expires_at
            FROM report_deliveries
            WHERE tenant_id = ? AND report_run_id = ?
            ORDER BY created_at
            """.trimIndent(),
            { rs, _ -> mapDelivery(actor.tenantId, rs) },
            actor.tenantId,
            runId,
        )
    }

    override fun retryDelivery(
        tenantId: UUID,
        deliveryId: UUID,
    ): ReportDeliveryResponse = writeTenant(tenantId) { actor ->
        val changed = jdbcTemplate.update(
            """
            UPDATE report_deliveries
            SET status = 'retry_scheduled',
                next_attempt_at = now(),
                retry_requested_at = now(),
                retry_requested_by = ?,
                max_attempts = GREATEST(max_attempts, attempt_count + 1),
                updated_at = now()
            WHERE tenant_id = ?
              AND id = ?
              AND status IN ('failed', 'dead_letter')
            """.trimIndent(),
            actor.tenantUserId,
            actor.tenantId,
            deliveryId,
        )
        if (changed != 1) {
            throw ReportingConflictException(
                "Only failed or dead-letter report deliveries can be retried",
            )
        }
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "report_deliveries",
                aggregateId = deliveryId,
                tenantId = actor.tenantId,
                eventType = "report.delivery.requested",
                destination = OutboxDestination.REPORTS,
                payload = mapOf("reportDeliveryId" to deliveryId),
                priority = 3,
            ),
        )
        requireDelivery(actor.tenantId, deliveryId)
    }

    private fun createSubscription(
        actor: TenantActor,
        propertyId: UUID?,
        request: CreateReportSubscriptionRequest,
    ): ReportSubscriptionResponse {
        val reportCode = request.reportCode.normalizedCode()
        val scope = if (propertyId == null) "tenant" else "property"
        requireCatalogScope(reportCode, scope)
        val id = UUID.randomUUID()
        try {
            jdbcTemplate.update(
                """
                INSERT INTO report_subscriptions (
                    id, tenant_id, property_id, report_code,
                    subscription_name, scope, frequency, schedule_time,
                    timezone, language_code, default_format, status, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pdf', 'active', ?)
                """.trimIndent(),
                id,
                actor.tenantId,
                propertyId,
                reportCode,
                request.subscriptionName.normalizedName(),
                scope,
                request.frequency.normalizedFrequency(),
                request.scheduleTime?.let(Time::valueOf),
                request.timezone.normalizedTimezone(),
                request.languageCode.normalizedLanguage(),
                actor.tenantUserId,
            )
        } catch (ex: DataIntegrityViolationException) {
            throw ReportingConflictException(
                "An active report subscription with this name already exists",
            )
        }
        return requireSubscription(actor.tenantId, id, propertyId = propertyId)
    }

    private fun subscriptions(
        tenantId: UUID,
        propertyId: UUID?,
    ): List<ReportSubscriptionResponse> {
        return jdbcTemplate.query(
            """
            $SUBSCRIPTION_SELECT
            WHERE subscription.tenant_id = ?
              AND subscription.property_id IS NOT DISTINCT FROM ?
              AND subscription.deleted_at IS NULL
            ORDER BY subscription.subscription_name
            """.trimIndent(),
            { rs, _ -> mapSubscription(rs) },
            tenantId,
            propertyId,
        )
    }

    private fun requireSubscription(
        tenantId: UUID,
        subscriptionId: UUID,
        includeArchived: Boolean = false,
        propertyId: UUID? = null,
    ): ReportSubscriptionResponse {
        return jdbcTemplate.query(
            """
            $SUBSCRIPTION_SELECT
            WHERE subscription.tenant_id = ?
              AND subscription.id = ?
              ${propertyPredicate(propertyId, "subscription")}
              ${if (includeArchived) "" else "AND subscription.deleted_at IS NULL"}
            """.trimIndent(),
            { rs, _ -> mapSubscription(rs) },
            tenantId,
            subscriptionId,
            *propertyArgs(propertyId),
        ).singleOrNull() ?: throw ReportingNotFoundException(
            "Report subscription was not found",
        )
    }

    private fun mapSubscription(rs: ResultSet): ReportSubscriptionResponse {
        val tenantId = rs.getObject("tenant_id", UUID::class.java)
        val id = rs.getObject("id", UUID::class.java)
        return ReportSubscriptionResponse(
            id = id,
            tenantId = tenantId,
            propertyId = rs.getObject("property_id", UUID::class.java),
            reportCode = rs.getString("report_code"),
            subscriptionName = rs.getString("subscription_name"),
            frequency = rs.getString("frequency"),
            timezone = rs.getString("timezone"),
            languageCode = rs.getString("language_code"),
            state = ReportSubscriptionState.valueOf(
                rs.getString("status").uppercase(),
            ),
            recipients = recipients(tenantId, id),
        )
    }

    private fun recipients(
        tenantId: UUID,
        subscriptionId: UUID,
    ): List<ReportRecipientResponse> {
        return jdbcTemplate.query(
            """
            SELECT recipient.id, recipient.contact_id,
                   recipient.contact_channel_id,
                   channel.channel_type,
                   mask_contact_channel_address(
                       channel.channel_type, channel.address
                   ) AS destination_masked,
                   recipient.is_enabled
            FROM report_subscription_recipients recipient
            JOIN contact_channels channel
              ON channel.tenant_id = recipient.tenant_id
             AND channel.contact_id = recipient.contact_id
             AND channel.id = recipient.contact_channel_id
            WHERE recipient.tenant_id = ?
              AND recipient.subscription_id = ?
            ORDER BY recipient.created_at
            """.trimIndent(),
            { rs, _ ->
                ReportRecipientResponse(
                    id = rs.getObject("id", UUID::class.java),
                    contactId = rs.getObject("contact_id", UUID::class.java),
                    contactChannelId = rs.getObject(
                        "contact_channel_id",
                        UUID::class.java,
                    ),
                    channelType = rs.getString("channel_type"),
                    destinationMasked = rs.getString("destination_masked"),
                    enabled = rs.getBoolean("is_enabled"),
                )
            },
            tenantId,
            subscriptionId,
        )
    }

    private fun resolvedSettings(
        tenantId: UUID,
        propertyId: UUID?,
    ): ReportingSettingsResponse {
        return jdbcTemplate.query(
            """
            SELECT COALESCE(property_policy.retention_days,
                            tenant_policy.retention_days, 400) AS retention_days,
                   CASE
                     WHEN property_policy.id IS NOT NULL THEN 'property'
                     WHEN tenant_policy.id IS NOT NULL THEN 'tenant'
                     ELSE 'system'
                   END AS source
            FROM (SELECT 1) seed
            LEFT JOIN reporting_retention_policies tenant_policy
              ON tenant_policy.tenant_id = ?
             AND tenant_policy.property_id IS NULL
            LEFT JOIN reporting_retention_policies property_policy
              ON property_policy.tenant_id = ?
             AND property_policy.property_id = ?
            """.trimIndent(),
            { rs, _ ->
                ReportingSettingsResponse(
                    tenantId = tenantId,
                    propertyId = propertyId,
                    retentionDays = rs.getInt("retention_days"),
                    source = rs.getString("source"),
                )
            },
            tenantId,
            tenantId,
            propertyId,
        ).single()
    }

    private fun upsertSettings(
        actor: TenantActor,
        propertyId: UUID?,
        retentionDays: Int,
    ): ReportingSettingsResponse {
        require(retentionDays in MIN_RETENTION_DAYS..MAX_RETENTION_DAYS) {
            "retentionDays must be between 30 and 3650"
        }
        jdbcTemplate.update(
            """
            INSERT INTO reporting_retention_policies (
                id, tenant_id, property_id, retention_days, created_by
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT uq_reporting_retention_scope
            DO UPDATE SET
                retention_days = EXCLUDED.retention_days,
                updated_at = now()
            """.trimIndent(),
            UUID.randomUUID(),
            actor.tenantId,
            propertyId,
            retentionDays,
            actor.tenantUserId,
        )
        return resolvedSettings(actor.tenantId, propertyId)
    }

    private fun requireGenerator(reportCode: String) {
        val found = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM report_catalog
                WHERE report_code = ?
                  AND is_active = true
                  AND generator_available = true
            )
            """.trimIndent(),
            Boolean::class.java,
            reportCode,
        ) == true
        if (!found) {
            throw ReportingConflictException(
                "Report generator is unavailable for $reportCode",
            )
        }
    }

    private fun requireCatalogScope(reportCode: String, scope: String) {
        val valid = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM report_catalog
                WHERE report_code = ?
                  AND is_active = true
                  AND scope IN (?, 'both')
            )
            """.trimIndent(),
            Boolean::class.java,
            reportCode,
            scope,
        ) == true
        if (!valid) {
            throw ReportingConflictException(
                "Report does not support $scope subscriptions",
            )
        }
    }

    private fun requireRun(tenantId: UUID, runId: UUID): ReportRunResponse {
        return jdbcTemplate.query(
            "$RUN_SELECT WHERE tenant_id = ? AND id = ?",
            { rs, _ -> mapRun(rs) },
            tenantId,
            runId,
        ).singleOrNull() ?: throw ReportingNotFoundException(
            "Report run was not found",
        )
    }

    private fun mapRun(rs: ResultSet): ReportRunResponse =
        ReportRunResponse(
            id = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            reportCode = rs.getString("report_code"),
            businessDate = rs.getObject("business_date", LocalDate::class.java),
            state = ReportRunState.valueOf(rs.getString("status").uppercase()),
            contentHash = rs.getString("content_hash"),
            generatedAt = rs.getTimestamp("generated_at")?.toInstant(),
            failureReason = rs.getString("failure_reason"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )

    private fun requireDelivery(
        tenantId: UUID,
        deliveryId: UUID,
    ): ReportDeliveryResponse {
        return jdbcTemplate.query(
            """
            SELECT id, report_run_id, report_code, channel_type,
                   destination_masked, status, attempt_count, link_expires_at
            FROM report_deliveries
            WHERE tenant_id = ? AND id = ?
            """.trimIndent(),
            { rs, _ -> mapDelivery(tenantId, rs) },
            tenantId,
            deliveryId,
        ).singleOrNull() ?: throw ReportingNotFoundException(
            "Report delivery was not found",
        )
    }

    private fun mapDelivery(
        tenantId: UUID,
        rs: ResultSet,
    ): ReportDeliveryResponse {
        val id = rs.getObject("id", UUID::class.java)
        return ReportDeliveryResponse(
            id = id,
            reportRunId = rs.getObject("report_run_id", UUID::class.java),
            reportCode = rs.getString("report_code"),
            channelType = rs.getString("channel_type"),
            destinationMasked = rs.getString("destination_masked"),
            state = ReportDeliveryState.valueOf(
                rs.getString("status").uppercase(),
            ),
            attemptCount = rs.getInt("attempt_count"),
            linkExpiresAt = rs.getTimestamp("link_expires_at")?.toInstant(),
            attempts = jdbcTemplate.query(
                """
                SELECT id, attempt_number, channel_type, provider_code,
                       status, error_code, started_at, completed_at
                FROM report_delivery_attempts
                WHERE tenant_id = ? AND report_delivery_id = ?
                ORDER BY attempt_number
                """.trimIndent(),
                { attempt, _ ->
                    ReportDeliveryAttemptResponse(
                        id = attempt.getObject("id", UUID::class.java),
                        attemptNumber = attempt.getInt("attempt_number"),
                        channelType = attempt.getString("channel_type"),
                        providerCode = attempt.getString("provider_code"),
                        state = attempt.getString("status").uppercase(),
                        errorCode = attempt.getString("error_code"),
                        startedAt = attempt.getTimestamp(
                            "started_at",
                        ).toInstant(),
                        completedAt = attempt.getTimestamp(
                            "completed_at",
                        )?.toInstant(),
                    )
                },
                tenantId,
                id,
            ),
        )
    }

    private fun <T> readTenant(
        tenantId: UUID,
        block: (TenantActor) -> T,
    ): T = readActor { actor ->
        require(actor.tenantId == tenantId) { "Tenant scope does not match" }
        block(actor)
    }

    private fun <T> readProperty(
        propertyId: UUID,
        block: (TenantActor) -> T,
    ): T = readActor { actor ->
        tenantRequestContext.requirePropertyUsable(actor.tenantId, propertyId)
        block(actor)
    }

    private fun <T> writeTenant(
        tenantId: UUID,
        block: (TenantActor) -> T,
    ): T = writeActor { actor ->
        require(actor.tenantId == tenantId) { "Tenant scope does not match" }
        block(actor)
    }

    private fun <T> writeProperty(
        propertyId: UUID,
        block: (TenantActor) -> T,
    ): T = writeActor { actor ->
        tenantRequestContext.requirePropertyUsable(
            actor.tenantId,
            propertyId,
            lock = true,
        )
        block(actor)
    }

    private fun <T> writeSubscriptionScope(
        propertyId: UUID?,
        block: (TenantActor) -> T,
    ): T {
        return if (propertyId == null) {
            writeActor(block)
        } else {
            writeProperty(propertyId, block)
        }
    }

    private fun propertyPredicate(
        propertyId: UUID?,
        qualifier: String? = null,
    ): String {
        val prefix = qualifier?.let { "$it." } ?: ""
        return if (propertyId == null) {
            "AND ${prefix}property_id IS NULL"
        } else {
            "AND ${prefix}property_id = ?"
        }
    }

    private fun propertyArgs(propertyId: UUID?): Array<Any> =
        if (propertyId == null) emptyArray() else arrayOf(propertyId)

    private fun <T> readActor(block: (TenantActor) -> T): T =
        requireNotNull(transactionTemplate.execute {
            block(tenantRequestContext.bind())
        })

    private fun <T> writeActor(block: (TenantActor) -> T): T =
        requireNotNull(transactionTemplate.execute {
            block(tenantRequestContext.bind())
        })

    private fun String.normalizedCode(): String =
        trim().lowercase().also {
            require(REPORT_CODE.matches(it)) {
                "reportCode contains unsupported characters"
            }
        }

    private fun String.normalizedName(): String =
        trim().also {
            require(it.length in 3..160) {
                "subscriptionName must contain 3 to 160 characters"
            }
        }

    private fun String.normalizedFrequency(): String =
        trim().lowercase().also {
            require(it in SUPPORTED_FREQUENCIES) {
                "Unsupported report frequency"
            }
        }

    private fun String.normalizedTimezone(): String =
        trim().also {
            require(it.length in 3..50) { "Invalid timezone" }
            java.time.ZoneId.of(it)
        }

    private fun String.normalizedLanguage(): String =
        trim().lowercase().also {
            require(LANGUAGE.matches(it)) { "Invalid languageCode" }
        }

    private companion object {
        const val MIN_RETENTION_DAYS = 30
        const val MAX_RETENTION_DAYS = 3650
        val AUTHENTICATED_LINK_EXPIRY: Duration = Duration.ofMinutes(15)
        val REPORT_CODE = Regex("[a-z0-9_]{3,100}")
        val LANGUAGE = Regex("[a-z]{2}(-[a-z]{2})?")
        val SUPPORTED_FREQUENCIES = setOf(
            "daily", "weekly", "monthly", "after_night_audit", "event_driven",
        )
        val RUN_SELECT = """
            SELECT id, tenant_id, property_id, report_code, business_date,
                   status, content_hash, generated_at, failure_reason, created_at
            FROM report_runs
        """.trimIndent()
        val SUBSCRIPTION_SELECT = """
            SELECT subscription.id, subscription.tenant_id,
                   subscription.property_id, subscription.report_code,
                   subscription.subscription_name, subscription.frequency,
                   subscription.timezone, subscription.language_code,
                   subscription.status
            FROM report_subscriptions subscription
        """.trimIndent()
    }
}
