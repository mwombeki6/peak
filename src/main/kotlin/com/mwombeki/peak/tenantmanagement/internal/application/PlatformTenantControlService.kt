package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.PlatformAuditEvent
import com.mwombeki.peak.audit.api.PlatformAuditQueryPort
import com.mwombeki.peak.audit.api.PlatformAuditTimelineEntry
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.tenantmanagement.api.PlatformControlConflictException
import com.mwombeki.peak.tenantmanagement.api.PlatformControlInProgressException
import com.mwombeki.peak.tenantmanagement.api.PlatformControlNotFoundException
import com.mwombeki.peak.tenantmanagement.api.PlatformTenantControlPort
import com.mwombeki.peak.tenantmanagement.api.TenantCatalogItem
import com.mwombeki.peak.tenantmanagement.api.TenantCatalogPage
import com.mwombeki.peak.tenantmanagement.api.TenantCatalogQuery
import com.mwombeki.peak.tenantmanagement.api.TenantControlAction
import com.mwombeki.peak.tenantmanagement.api.TenantControlMutationReceipt
import com.mwombeki.peak.tenantmanagement.api.TenantControlOverview
import com.mwombeki.peak.tenantmanagement.api.TenantControlTransitionCommand
import com.mwombeki.peak.tenantmanagement.api.TenantSubscriptionSummary
import com.mwombeki.peak.tenantmanagement.api.TenantUsageSummary
import com.mwombeki.peak.tenantmanagement.api.TenantWorkflowStepSummary
import com.mwombeki.peak.tenantmanagement.api.TenantWorkflowSummary
import com.mwombeki.peak.usermanagement.api.PlatformAccessPort
import com.mwombeki.peak.usermanagement.api.PlatformAccessRequest
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class PlatformTenantControlService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val platformAccessPort: PlatformAccessPort,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val auditQueryPort: PlatformAuditQueryPort,
    private val outboxPort: OutboxPort,
    private val requestContextHolder: RequestContextHolder,
    private val objectMapper: ObjectMapper,
) : PlatformTenantControlPort {

    override fun listTenants(query: TenantCatalogQuery): TenantCatalogPage {
        require(query.limit in 1..100) { "limit must be between 1 and 100" }
        return requireNotNull(
            transactionTemplate.execute {
                requirePlatformAccess(null, VIEW, "platform.tenants.catalog")
                val conditions = mutableListOf("tenant.deleted_at IS NULL")
                val arguments = mutableListOf<Any>()

                query.search?.trim()?.takeIf(String::isNotEmpty)?.let { search ->
                    conditions += "(tenant.name ILIKE ? OR tenant.slug ILIKE ? OR profile.legal_name ILIKE ?)"
                    val pattern = "%${search.take(100)}%"
                    arguments += pattern
                    arguments += pattern
                    arguments += pattern
                }
                query.lifecycleStatus?.normalizedStatus()?.let {
                    conditions += "control.lifecycle_status = ?"
                    arguments += it
                }
                query.subscriptionStatus?.normalizedStatus()?.let {
                    conditions += "control.subscription_status = ?"
                    arguments += it
                }
                query.serviceStatus?.normalizedStatus()?.let {
                    conditions += "control.service_status = ?"
                    arguments += it
                }
                query.cursor?.let {
                    conditions += """
                        (tenant.created_at, tenant.id) < COALESCE(
                            (SELECT (cursor_tenant.created_at, cursor_tenant.id)
                             FROM tenants cursor_tenant WHERE cursor_tenant.id = ?),
                            ('infinity'::timestamptz, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)
                        )
                    """.trimIndent()
                    arguments += it
                }

                val rows = jdbcTemplate.query(
                    """
                    $CATALOG_SELECT
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY tenant.created_at DESC, tenant.id DESC
                    LIMIT ?
                    """.trimIndent(),
                    ::mapCatalogItem,
                    *(arguments + (query.limit + 1)).toTypedArray(),
                )
                TenantCatalogPage(
                    items = rows.take(query.limit),
                    nextCursor = rows.getOrNull(query.limit)?.tenantId,
                    limit = query.limit,
                )
            },
        )
    }

    override fun tenantOverview(tenantId: UUID): TenantControlOverview {
        return requireNotNull(
            transactionTemplate.execute {
                requirePlatformAccess(tenantId, VIEW, "platform.tenants.control.overview")
                val tenant = jdbcTemplate.query(
                    "$CATALOG_SELECT WHERE tenant.id = ? AND tenant.deleted_at IS NULL",
                    ::mapCatalogItem,
                    tenantId,
                ).singleOrNull() ?: throw PlatformControlNotFoundException("Tenant was not found")

                val profile = jdbcTemplate.queryForMap(
                    """
                    SELECT legal_name, business_email, business_phone
                    FROM tenant_profiles WHERE tenant_id = ?
                    """.trimIndent(),
                    tenantId,
                )
                val usage = latestUsage(tenantId)
                val subscription = currentSubscription(tenantId)
                val counts = jdbcTemplate.queryForMap(
                    """
                    SELECT
                        (SELECT count(*) FROM tenant_modules
                         WHERE tenant_id = ? AND is_enabled = true) AS enabled_modules,
                        (SELECT count(*) FROM tenant_workflows
                         WHERE tenant_id = ? AND status NOT IN ('succeeded', 'cancelled')) AS open_workflows,
                        (SELECT count(*) FROM support_tickets
                         WHERE tenant_id = ? AND status NOT IN ('resolved', 'closed')) AS open_support_tickets,
                        (SELECT count(*) FROM platform_alerts
                         WHERE tenant_id = ? AND status IN ('open', 'acknowledged')) AS unresolved_alerts
                    """.trimIndent(),
                    tenantId,
                    tenantId,
                    tenantId,
                    tenantId,
                )
                val configuration = jdbcTemplate.queryForMap(
                    """
                    SELECT desired_configuration_version, actual_configuration_version
                    FROM tenant_control_states WHERE tenant_id = ?
                    """.trimIndent(),
                    tenantId,
                )

                TenantControlOverview(
                    tenant = tenant,
                    legalName = profile["legal_name"].toString(),
                    businessEmail = profile["business_email"].toString(),
                    businessPhone = profile["business_phone"].toString(),
                    subscription = subscription,
                    latestUsage = usage,
                    enabledModules = (counts["enabled_modules"] as Number).toInt(),
                    openWorkflows = (counts["open_workflows"] as Number).toInt(),
                    openSupportTickets = (counts["open_support_tickets"] as Number).toInt(),
                    unresolvedAlerts = (counts["unresolved_alerts"] as Number).toInt(),
                    configurationDrift = configuration["desired_configuration_version"] !=
                        configuration["actual_configuration_version"],
                )
            },
        )
    }

    override fun listWorkflows(tenantId: UUID, limit: Int): List<TenantWorkflowSummary> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return requireNotNull(
            transactionTemplate.execute {
                requirePlatformAccess(tenantId, VIEW, "platform.tenants.control.workflows")
                val workflows = jdbcTemplate.query(
                    """
                    SELECT id, workflow_type, status, current_step, completed_steps,
                           total_steps, reason, error_code, created_at, updated_at
                    FROM tenant_workflows
                    WHERE tenant_id = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT ?
                    """.trimIndent(),
                    { rs, _ -> workflowWithoutSteps(rs) },
                    tenantId,
                    limit,
                )
                workflows.map { workflow ->
                    workflow.copy(steps = workflowSteps(tenantId, workflow.workflowId))
                }
            },
        )
    }

    override fun auditTimeline(tenantId: UUID, limit: Int): List<PlatformAuditTimelineEntry> {
        return requireNotNull(
            transactionTemplate.execute {
                requirePlatformAccess(tenantId, "platform.audit.view", "platform.tenants.control.timeline")
                auditQueryPort.tenantTimeline(tenantId, limit)
            },
        )
    }

    override fun transitionLifecycle(
        command: TenantControlTransitionCommand,
    ): TenantControlMutationReceipt {
        return mutate(
            operation = "platform.tenant.control.${command.action.name.lowercase()}",
            tenantId = command.tenantId,
            payload = command,
        ) { reservationId ->
            val state = lockedState(command.tenantId)
            if (state.version != command.expectedVersion) {
                throw PlatformControlConflictException(
                    "Tenant control state changed; expected version ${command.expectedVersion} but found ${state.version}",
                )
            }
            require(command.reason.isNotBlank()) { "Lifecycle reason is required" }
            requireSafeLifecycleTransition(command, state)

            val target = targetState(command.action, state)
            val changed = target != state
            if (!changed) {
                return@mutate receipt(state, null, changed = false)
            }

            val legacyStatus = legacyStatus(command.action, target.lifecycleStatus)
            val updated = jdbcTemplate.update(
                """
                UPDATE tenant_control_states
                SET lifecycle_status = ?, provisioning_status = ?,
                    subscription_status = ?, service_status = ?, offboarding_status = ?,
                    version = version + 1, updated_by_platform_user_id = ?
                WHERE tenant_id = ? AND version = ?
                """.trimIndent(),
                target.lifecycleStatus,
                target.provisioningStatus,
                target.subscriptionStatus,
                target.serviceStatus,
                target.offboardingStatus,
                currentPlatformActorId(),
                command.tenantId,
                command.expectedVersion,
            )
            if (updated != 1) {
                throw PlatformControlConflictException("Concurrent tenant lifecycle change detected")
            }
            jdbcTemplate.update(
                "UPDATE tenants SET status = ?, updated_at = now() WHERE id = ? AND deleted_at IS NULL",
                legacyStatus,
                command.tenantId,
            )

            val workflowId = createLifecycleWorkflow(command, target)
            val after = lockedState(command.tenantId)
            recordSideEffects(
                action = "platform.tenants.lifecycle.${command.action.name.lowercase()}",
                tenantId = command.tenantId,
                resourceId = workflowId,
                before = state.asMap(),
                after = after.asMap() + mapOf("reason" to command.reason.trim()),
                idempotencyKeyId = reservationId,
            )
            receipt(after, workflowId, changed = true)
        }
    }

    override fun reconcile(tenantId: UUID, expectedVersion: Long): TenantControlMutationReceipt {
        return mutate(
            operation = "platform.tenant.control.reconcile",
            tenantId = tenantId,
            payload = mapOf("tenantId" to tenantId, "expectedVersion" to expectedVersion),
        ) { reservationId ->
            val before = lockedState(tenantId)
            if (before.version != expectedVersion) {
                throw PlatformControlConflictException("Concurrent tenant control reconciliation detected")
            }
            val changed = before.desiredConfigurationVersion != before.actualConfigurationVersion
            val workflowId = if (changed) {
                jdbcTemplate.update(
                    """
                    UPDATE tenant_control_states
                    SET actual_configuration_version = desired_configuration_version,
                        last_reconciled_at = now(), version = version + 1,
                        updated_by_platform_user_id = ?
                    WHERE tenant_id = ? AND version = ?
                    """.trimIndent(),
                    currentPlatformActorId(), tenantId, expectedVersion,
                )
                createCompletedWorkflow(
                    tenantId = tenantId,
                    workflowType = "configuration_rollout",
                    reason = "Desired and actual configuration reconciled",
                    stepKeys = listOf("validate", "apply", "verify"),
                )
            } else {
                jdbcTemplate.update(
                    "UPDATE tenant_control_states SET last_reconciled_at = now() WHERE tenant_id = ?",
                    tenantId,
                )
                null
            }
            val after = lockedState(tenantId)
            if (changed) {
                recordSideEffects(
                    action = "platform.tenants.control.reconciled",
                    tenantId = tenantId,
                    resourceId = workflowId!!,
                    before = before.asMap(),
                    after = after.asMap(),
                    idempotencyKeyId = reservationId,
                )
            }
            receipt(after, workflowId, changed)
        }
    }

    private fun mutate(
        operation: String,
        tenantId: UUID,
        payload: Any,
        block: (UUID) -> TenantControlMutationReceipt,
    ): TenantControlMutationReceipt {
        return requireNotNull(
            transactionTemplate.execute {
                requirePlatformAccess(tenantId, MANAGE, operation)
                when (
                    val reservation = idempotencyPort.reserve(
                        IdempotencyCommand(operation, payload, "tenant_control_states"),
                    )
                ) {
                    is IdempotencyReservation.Started -> {
                        val receipt = block(reservation.recordId)
                        idempotencyPort.markSucceeded(
                            reservation.recordId, 200, receipt, receipt.workflowId ?: tenantId,
                        )
                        receipt
                    }
                    is IdempotencyReservation.Replay -> {
                        if (reservation.responseBody.isNullOrBlank()) {
                            throw PlatformControlConflictException("Tenant control replay response is missing")
                        }
                        objectMapper.readValue(
                            reservation.responseBody,
                            TenantControlMutationReceipt::class.java,
                        ).copy(replayed = true)
                    }
                    is IdempotencyReservation.InProgress -> throw PlatformControlInProgressException(
                        "Tenant control command is already in progress",
                    )
                    is IdempotencyReservation.Conflict -> throw PlatformControlConflictException(
                        "Idempotency key was used for a different tenant control command",
                    )
                }
            },
        )
    }

    private fun requireSafeLifecycleTransition(
        command: TenantControlTransitionCommand,
        state: ControlState,
    ) {
        val allowed = when (command.action) {
            TenantControlAction.ACTIVATE -> setOf("trial", "restricted", "frozen", "suspended")
            TenantControlAction.RESTRICT -> setOf("trial", "active")
            TenantControlAction.FREEZE -> setOf("trial", "active", "restricted", "suspended")
            TenantControlAction.SUSPEND -> setOf("trial", "active", "restricted", "frozen")
            TenantControlAction.REACTIVATE -> setOf("restricted", "frozen", "suspended")
            TenantControlAction.ARCHIVE -> setOf("trial", "restricted", "frozen", "suspended")
            TenantControlAction.START_OFFBOARDING -> setOf("trial", "active", "restricted", "frozen", "suspended")
            TenantControlAction.COMPLETE_OFFBOARDING -> setOf("offboarding")
            TenantControlAction.CANCEL_OFFBOARDING -> setOf("offboarding")
            TenantControlAction.RESTORE -> setOf("archived", "terminated", "cancelled")
        }
        if (state.lifecycleStatus !in allowed) {
            throw PlatformControlConflictException(
                "Tenant cannot perform ${command.action.name.lowercase()} from ${state.lifecycleStatus}",
            )
        }

        if (command.action in setOf(TenantControlAction.ARCHIVE, TenantControlAction.COMPLETE_OFFBOARDING)) {
            val inHouse = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM stays
                    WHERE tenant_id = ? AND status IN ('checked_in', 'in_house')
                )
                """.trimIndent(),
                Boolean::class.java,
                command.tenantId,
            ) == true
            if (inHouse) {
                throw PlatformControlConflictException(
                    "Tenant cannot be archived or terminated while guests are in house",
                )
            }
        }
        if (command.action == TenantControlAction.COMPLETE_OFFBOARDING) {
            val hold = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM tenant_legal_holds
                    WHERE tenant_id = ? AND status = 'active'
                      AND (expires_at IS NULL OR expires_at > now())
                )
                """.trimIndent(),
                Boolean::class.java,
                command.tenantId,
            ) == true
            if (hold) {
                throw PlatformControlConflictException(
                    "Tenant offboarding is blocked by an active legal hold",
                )
            }
        }
    }

    private fun targetState(action: TenantControlAction, current: ControlState): ControlState {
        return when (action) {
            TenantControlAction.ACTIVATE,
            TenantControlAction.REACTIVATE,
            TenantControlAction.RESTORE -> current.copy(
                lifecycleStatus = "active",
                provisioningStatus = "ready",
                serviceStatus = "operational",
                offboardingStatus = "none",
            )
            TenantControlAction.RESTRICT -> current.copy(lifecycleStatus = "restricted")
            TenantControlAction.FREEZE -> current.copy(lifecycleStatus = "frozen")
            TenantControlAction.SUSPEND -> current.copy(lifecycleStatus = "suspended")
            TenantControlAction.ARCHIVE -> current.copy(
                lifecycleStatus = "archived",
                provisioningStatus = "deprovisioned",
                offboardingStatus = "completed",
            )
            TenantControlAction.START_OFFBOARDING -> current.copy(
                lifecycleStatus = "offboarding",
                provisioningStatus = "deprovisioning",
                offboardingStatus = "exporting",
            )
            TenantControlAction.COMPLETE_OFFBOARDING -> current.copy(
                lifecycleStatus = "terminated",
                provisioningStatus = "deprovisioned",
                subscriptionStatus = "cancelled",
                offboardingStatus = "completed",
            )
            TenantControlAction.CANCEL_OFFBOARDING -> current.copy(
                lifecycleStatus = "active",
                provisioningStatus = "ready",
                offboardingStatus = "cancelled",
            )
        }
    }

    private fun legacyStatus(action: TenantControlAction, lifecycle: String): String {
        return when (lifecycle) {
            "restricted", "offboarding" -> "active"
            "active" -> "active"
            "trial" -> "trial"
            "frozen" -> "frozen"
            "suspended" -> "suspended"
            "archived" -> "archived"
            "terminated" -> "terminated"
            "cancelled" -> "cancelled"
            else -> if (action == TenantControlAction.RESTORE) "active" else lifecycle
        }
    }

    private fun createLifecycleWorkflow(
        command: TenantControlTransitionCommand,
        target: ControlState,
    ): UUID {
        val type = when (command.action) {
            TenantControlAction.START_OFFBOARDING,
            TenantControlAction.COMPLETE_OFFBOARDING,
            TenantControlAction.CANCEL_OFFBOARDING -> "offboarding"
            TenantControlAction.ARCHIVE -> "archive"
            TenantControlAction.RESTORE -> "restore"
            TenantControlAction.REACTIVATE, TenantControlAction.ACTIVATE -> "reactivation"
            TenantControlAction.FREEZE, TenantControlAction.SUSPEND, TenantControlAction.RESTRICT -> "freeze"
        }
        if (command.action == TenantControlAction.START_OFFBOARDING) {
            return createWorkflow(
                tenantId = command.tenantId,
                workflowType = type,
                reason = command.reason,
                status = "running",
                currentStep = "export_data",
                stepStates = listOf(
                    "export_data" to "running",
                    "settle_financial" to "pending",
                    "revoke_access" to "pending",
                    "apply_retention" to "pending",
                    "terminate" to "pending",
                ),
            )
        }
        if (command.action == TenantControlAction.COMPLETE_OFFBOARDING) {
            completeOpenOffboardingWorkflow(command.tenantId)
        }
        return createCompletedWorkflow(
            tenantId = command.tenantId,
            workflowType = type,
            reason = command.reason,
            stepKeys = listOf("validate", "apply", "verify_${target.lifecycleStatus}"),
        )
    }

    private fun createCompletedWorkflow(
        tenantId: UUID,
        workflowType: String,
        reason: String,
        stepKeys: List<String>,
    ): UUID {
        return createWorkflow(
            tenantId = tenantId,
            workflowType = workflowType,
            reason = reason,
            status = "succeeded",
            currentStep = stepKeys.lastOrNull(),
            stepStates = stepKeys.map { it to "succeeded" },
        )
    }

    private fun createWorkflow(
        tenantId: UUID,
        workflowType: String,
        reason: String,
        status: String,
        currentStep: String?,
        stepStates: List<Pair<String, String>>,
    ): UUID {
        val id = UUID.randomUUID()
        val completed = stepStates.count { it.second == "succeeded" }
        jdbcTemplate.update(
            """
            INSERT INTO tenant_workflows (
                id, tenant_id, workflow_type, status, requested_by_platform_user_id,
                reason, current_step, total_steps, completed_steps, started_at,
                completed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(),
                      CASE WHEN ? = 'succeeded' THEN now() ELSE NULL END)
            """.trimIndent(),
            id, tenantId, workflowType, status, currentPlatformActorId(), reason.trim(),
            currentStep, stepStates.size, completed, status,
        )
        stepStates.forEachIndexed { index, (stepKey, stepStatus) ->
            jdbcTemplate.update(
                """
                INSERT INTO tenant_workflow_steps (
                    tenant_id, workflow_id, step_key, sequence, status, attempt_count,
                    started_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?,
                          CASE WHEN ? IN ('running', 'succeeded') THEN now() ELSE NULL END,
                          CASE WHEN ? = 'succeeded' THEN now() ELSE NULL END)
                """.trimIndent(),
                tenantId, id, stepKey, index + 1, stepStatus,
                if (stepStatus in setOf("running", "succeeded")) 1 else 0,
                stepStatus, stepStatus,
            )
        }
        return id
    }

    private fun completeOpenOffboardingWorkflow(tenantId: UUID) {
        val id = jdbcTemplate.query(
            """
            SELECT id FROM tenant_workflows
            WHERE tenant_id = ? AND workflow_type = 'offboarding'
              AND status IN ('running', 'waiting_for_approval', 'waiting_for_customer', 'retrying')
            ORDER BY created_at DESC LIMIT 1 FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            tenantId,
        ).singleOrNull() ?: return
        jdbcTemplate.update(
            """
            UPDATE tenant_workflow_steps SET status = 'succeeded',
                attempt_count = GREATEST(attempt_count, 1),
                started_at = COALESCE(started_at, now()), completed_at = now(), error_code = NULL
            WHERE tenant_id = ? AND workflow_id = ? AND status <> 'succeeded'
            """.trimIndent(),
            tenantId, id,
        )
        jdbcTemplate.update(
            """
            UPDATE tenant_workflows SET status = 'succeeded', current_step = 'terminate',
                completed_steps = total_steps, completed_at = now(), error_code = NULL
            WHERE tenant_id = ? AND id = ?
            """.trimIndent(),
            tenantId, id,
        )
    }

    private fun lockedState(tenantId: UUID): ControlState {
        return jdbcTemplate.query(
            """
            SELECT tenant_id, lifecycle_status, verification_status, provisioning_status,
                   subscription_status, service_status, offboarding_status,
                   desired_configuration_version, actual_configuration_version, version
            FROM tenant_control_states WHERE tenant_id = ? FOR UPDATE
            """.trimIndent(),
            { rs, _ -> mapControlState(rs) },
            tenantId,
        ).singleOrNull() ?: throw PlatformControlNotFoundException("Tenant control state was not found")
    }

    private fun receipt(
        state: ControlState,
        workflowId: UUID?,
        changed: Boolean,
    ): TenantControlMutationReceipt {
        return TenantControlMutationReceipt(
            tenantId = state.tenantId,
            lifecycleStatus = state.lifecycleStatus,
            provisioningStatus = state.provisioningStatus,
            subscriptionStatus = state.subscriptionStatus,
            serviceStatus = state.serviceStatus,
            offboardingStatus = state.offboardingStatus,
            version = state.version,
            workflowId = workflowId,
            changed = changed,
            replayed = false,
        )
    }

    private fun recordSideEffects(
        action: String,
        tenantId: UUID,
        resourceId: UUID,
        before: Map<String, Any?>,
        after: Map<String, Any?>,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordPlatformEvent(
            PlatformAuditEvent(
                action = action,
                targetTenantId = tenantId,
                resource = AuditResource("tenant_workflows", resourceId),
                before = before,
                after = after,
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "tenant_workflows",
                aggregateId = resourceId,
                tenantId = null,
                eventType = action,
                destination = OutboxDestination.PLATFORM,
                payload = after,
                idempotencyKeyId = idempotencyKeyId,
                priority = 2,
            ),
        )
    }

    private fun currentPlatformActorId(): UUID {
        return when (val identity = requestContextHolder.current().identity) {
            is RequestIdentity.Platform -> identity.platformUserId
            is RequestIdentity.Support -> identity.platformUserId
            else -> throw IllegalStateException("Platform identity is required")
        }
    }

    private fun requirePlatformAccess(tenantId: UUID?, permission: String, operation: String) {
        platformAccessPort.requireAuthorized(
            PlatformAccessRequest(tenantId, permission, operation),
        )
    }

    private fun mapCatalogItem(rs: ResultSet, ignored: Int): TenantCatalogItem {
        return TenantCatalogItem(
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            name = rs.getString("name"),
            slug = rs.getString("slug"),
            countryCode = rs.getString("country_code"),
            currencyCode = rs.getString("currency_code"),
            planCode = rs.getString("plan_code"),
            lifecycleStatus = rs.getString("lifecycle_status"),
            verificationStatus = rs.getString("verification_status"),
            provisioningStatus = rs.getString("provisioning_status"),
            subscriptionStatus = rs.getString("subscription_status"),
            serviceStatus = rs.getString("service_status"),
            offboardingStatus = rs.getString("offboarding_status"),
            releaseChannel = rs.getString("release_channel"),
            version = rs.getLong("version"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }

    private fun mapControlState(rs: ResultSet): ControlState {
        return ControlState(
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            lifecycleStatus = rs.getString("lifecycle_status"),
            verificationStatus = rs.getString("verification_status"),
            provisioningStatus = rs.getString("provisioning_status"),
            subscriptionStatus = rs.getString("subscription_status"),
            serviceStatus = rs.getString("service_status"),
            offboardingStatus = rs.getString("offboarding_status"),
            desiredConfigurationVersion = rs.getLong("desired_configuration_version"),
            actualConfigurationVersion = rs.getLong("actual_configuration_version"),
            version = rs.getLong("version"),
        )
    }

    private fun workflowWithoutSteps(rs: ResultSet): TenantWorkflowSummary {
        return TenantWorkflowSummary(
            workflowId = rs.getObject("id", UUID::class.java),
            workflowType = rs.getString("workflow_type"),
            status = rs.getString("status"),
            currentStep = rs.getString("current_step"),
            completedSteps = rs.getInt("completed_steps"),
            totalSteps = rs.getInt("total_steps"),
            reason = rs.getString("reason"),
            errorCode = rs.getString("error_code"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            steps = emptyList(),
        )
    }

    private fun workflowSteps(tenantId: UUID, workflowId: UUID): List<TenantWorkflowStepSummary> {
        return jdbcTemplate.query(
            """
            SELECT step_key, sequence, status, attempt_count, error_code, completed_at
            FROM tenant_workflow_steps
            WHERE tenant_id = ? AND workflow_id = ? ORDER BY sequence
            """.trimIndent(),
            { rs, _ ->
                TenantWorkflowStepSummary(
                    stepKey = rs.getString("step_key"),
                    sequence = rs.getInt("sequence"),
                    status = rs.getString("status"),
                    attemptCount = rs.getInt("attempt_count"),
                    errorCode = rs.getString("error_code"),
                    completedAt = rs.getTimestamp("completed_at")?.toInstant(),
                )
            },
            tenantId, workflowId,
        )
    }

    private fun latestUsage(tenantId: UUID): TenantUsageSummary? {
        return jdbcTemplate.query(
            """
            SELECT tenant_id, snapshot_date, property_count, room_count, user_count,
                   outlet_count, storage_bytes, api_calls, metrics::text AS metrics
            FROM tenant_usage_snapshots
            WHERE tenant_id = ? ORDER BY snapshot_date DESC LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                TenantUsageSummary(
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    snapshotDate = rs.getObject("snapshot_date", LocalDate::class.java),
                    propertyCount = rs.getInt("property_count"),
                    roomCount = rs.getInt("room_count"),
                    userCount = rs.getInt("user_count"),
                    outletCount = rs.getInt("outlet_count"),
                    storageBytes = rs.getLong("storage_bytes"),
                    apiCalls = rs.getLong("api_calls"),
                    metrics = jsonMap(rs.getString("metrics")),
                )
            },
            tenantId,
        ).singleOrNull()
    }

    private fun currentSubscription(tenantId: UUID): TenantSubscriptionSummary? {
        return jdbcTemplate.query(
            """
            SELECT subscription.id, subscription.tenant_id, subscription.plan_id,
                   plan.code AS plan_code, subscription.status, subscription.billing_cycle,
                   subscription.billing_currency, subscription.provider,
                   subscription.provider_customer_id, subscription.provider_subscription_id,
                   subscription.current_period_starts_at, subscription.current_period_ends_at,
                   subscription.trial_ends_at, subscription.grace_period_ends_at,
                   subscription.cancel_at_period_end, subscription.version
            FROM tenant_subscriptions subscription
            JOIN plans plan ON plan.id = subscription.plan_id
            WHERE subscription.tenant_id = ?
              AND subscription.status IN ('trialing', 'active', 'past_due', 'paused')
            ORDER BY subscription.created_at DESC LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                TenantSubscriptionSummary(
                    subscriptionId = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    planId = rs.getObject("plan_id", UUID::class.java),
                    planCode = rs.getString("plan_code"),
                    status = rs.getString("status"),
                    billingCycle = rs.getString("billing_cycle"),
                    billingCurrency = rs.getString("billing_currency"),
                    provider = rs.getString("provider"),
                    providerCustomerId = rs.getString("provider_customer_id"),
                    providerSubscriptionId = rs.getString("provider_subscription_id"),
                    currentPeriodStartsAt = rs.getTimestamp("current_period_starts_at").toInstant(),
                    currentPeriodEndsAt = rs.getTimestamp("current_period_ends_at")?.toInstant(),
                    trialEndsAt = rs.getTimestamp("trial_ends_at")?.toInstant(),
                    gracePeriodEndsAt = rs.getTimestamp("grace_period_ends_at")?.toInstant(),
                    cancelAtPeriodEnd = rs.getBoolean("cancel_at_period_end"),
                    version = rs.getLong("version"),
                )
            },
            tenantId,
        ).singleOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonMap(raw: String): Map<String, Any?> {
        return objectMapper.readValue(raw, Map::class.java) as Map<String, Any?>
    }

    private fun String.normalizedStatus(): String = trim().lowercase().also {
        require(it.matches(Regex("[a-z_]{2,30}"))) { "Invalid status filter" }
    }

    private data class ControlState(
        val tenantId: UUID = UUID(0, 0),
        val lifecycleStatus: String,
        val verificationStatus: String,
        val provisioningStatus: String,
        val subscriptionStatus: String,
        val serviceStatus: String,
        val offboardingStatus: String,
        val desiredConfigurationVersion: Long,
        val actualConfigurationVersion: Long,
        val version: Long,
    ) {
        fun asMap(): Map<String, Any?> = mapOf(
            "tenantId" to tenantId,
            "lifecycleStatus" to lifecycleStatus,
            "verificationStatus" to verificationStatus,
            "provisioningStatus" to provisioningStatus,
            "subscriptionStatus" to subscriptionStatus,
            "serviceStatus" to serviceStatus,
            "offboardingStatus" to offboardingStatus,
            "desiredConfigurationVersion" to desiredConfigurationVersion,
            "actualConfigurationVersion" to actualConfigurationVersion,
            "version" to version,
        )
    }

    private companion object {
        const val VIEW = "platform.tenants.view"
        const val MANAGE = "platform.tenants.manage"

        val CATALOG_SELECT = """
            SELECT tenant.id AS tenant_id, tenant.name, tenant.slug,
                   tenant.country_code, tenant.currency_code, plan.code AS plan_code,
                   control.lifecycle_status, control.verification_status,
                   control.provisioning_status, control.subscription_status,
                   control.service_status, control.offboarding_status,
                   control.release_channel, control.version,
                   tenant.created_at, control.updated_at
            FROM tenants tenant
            JOIN tenant_profiles profile ON profile.tenant_id = tenant.id
            JOIN plans plan ON plan.id = tenant.plan_id
            JOIN tenant_control_states control ON control.tenant_id = tenant.id
        """.trimIndent()
    }
}
