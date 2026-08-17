package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.PlatformAuditEvent
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.tenantmanagement.api.ChangeTenantSubscriptionCommand
import com.mwombeki.peak.tenantmanagement.api.CreateEntitlementOverrideCommand
import com.mwombeki.peak.tenantmanagement.api.CreatePlanCommand
import com.mwombeki.peak.tenantmanagement.api.EffectiveEntitlement
import com.mwombeki.peak.tenantmanagement.api.EntitlementAccessPort
import com.mwombeki.peak.tenantmanagement.api.EntitlementDefinition
import com.mwombeki.peak.tenantmanagement.api.EntitlementSummary
import com.mwombeki.peak.tenantmanagement.api.PlanSummary
import com.mwombeki.peak.tenantmanagement.api.PlatformCommercialControlPort
import com.mwombeki.peak.tenantmanagement.api.PlatformControlConflictException
import com.mwombeki.peak.tenantmanagement.api.PlatformControlInProgressException
import com.mwombeki.peak.tenantmanagement.api.PlatformControlNotFoundException
import com.mwombeki.peak.tenantmanagement.api.ReplacePlanEntitlementsCommand
import com.mwombeki.peak.tenantmanagement.api.TenantCommercialOverview
import com.mwombeki.peak.tenantmanagement.api.TenantSubscriptionSummary
import com.mwombeki.peak.tenantmanagement.api.TenantUsageSummary
import com.mwombeki.peak.tenantmanagement.api.UpdatePlanCommand
import com.mwombeki.peak.usermanagement.api.PlatformAccessPort
import com.mwombeki.peak.usermanagement.api.PlatformAccessRequest
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessPort
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessRequest
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class PlatformCommercialControlService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val platformAccessPort: PlatformAccessPort,
    private val tenantPermissionAccessPort: TenantPermissionAccessPort,
    private val requestContextHolder: RequestContextHolder,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val objectMapper: ObjectMapper,
) : PlatformCommercialControlPort, EntitlementAccessPort {

    override fun listPlans(): List<PlanSummary> {
        return requireNotNull(
            transactionTemplate.execute {
                requirePlatformBillingAccess(null, "platform.plans.list")
                jdbcTemplate.query(
                    "$PLAN_SELECT ORDER BY plan.is_active DESC, plan.name, plan.code",
                    { rs, _ -> mapPlan(rs) },
                ).map { plan -> plan.copy(entitlements = planEntitlements(plan.planId)) }
            },
        )
    }

    override fun createPlan(command: CreatePlanCommand): PlanSummary {
        validatePlanLimits(
            command.maxProperties, command.maxRooms, command.maxUsers, command.maxOutlets,
            command.monthlyUsd, command.annualUsd,
        )
        return platformMutation(
            operation = "platform.plan.create",
            tenantId = null,
            payload = command,
            resourceType = "plans",
            responseType = PlanSummary::class.java,
        ) { reservationId ->
            val id = UUID.randomUUID()
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO plans (
                        id, name, code, max_properties, max_rooms, max_users,
                        max_outlets, monthly_usd, annual_usd, features, is_active
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, true)
                    """.trimIndent(),
                    id,
                    command.name.normalizedRequired("name"),
                    command.code.normalizedCode(),
                    command.maxProperties,
                    command.maxRooms,
                    command.maxUsers,
                    command.maxOutlets,
                    command.monthlyUsd,
                    command.annualUsd,
                )
            } catch (ex: DuplicateKeyException) {
                throw PlatformControlConflictException("Plan code is already in use")
            }
            seedLimitEntitlements(id, command)
            plan(id).also {
                recordCommercialSideEffects(
                    "platform.plans.created", null, "plans", id,
                    mapOf("planId" to id, "code" to it.code), reservationId,
                )
            }
        }
    }

    override fun updatePlan(command: UpdatePlanCommand): PlanSummary {
        return platformMutation(
            operation = "platform.plan.update",
            tenantId = null,
            payload = command,
            resourceType = "plans",
            responseType = PlanSummary::class.java,
        ) { reservationId ->
            val current = plan(command.planId)
            val target = current.copy(
                name = command.name?.normalizedRequired("name") ?: current.name,
                maxProperties = command.maxProperties ?: current.maxProperties,
                maxRooms = command.maxRooms ?: current.maxRooms,
                maxUsers = command.maxUsers ?: current.maxUsers,
                maxOutlets = command.maxOutlets ?: current.maxOutlets,
                monthlyUsd = command.monthlyUsd ?: current.monthlyUsd,
                annualUsd = command.annualUsd ?: current.annualUsd,
                isActive = command.isActive ?: current.isActive,
            )
            validatePlanLimits(
                target.maxProperties, target.maxRooms, target.maxUsers, target.maxOutlets,
                target.monthlyUsd, target.annualUsd,
            )
            jdbcTemplate.update(
                """
                UPDATE plans SET name = ?, max_properties = ?, max_rooms = ?,
                    max_users = ?, max_outlets = ?, monthly_usd = ?, annual_usd = ?,
                    is_active = ?, updated_at = now()
                WHERE id = ?
                """.trimIndent(),
                target.name, target.maxProperties, target.maxRooms, target.maxUsers,
                target.maxOutlets, target.monthlyUsd, target.annualUsd, target.isActive,
                command.planId,
            )
            synchronizeLimitEntitlements(target)
            plan(command.planId).also {
                recordCommercialSideEffects(
                    "platform.plans.updated", null, "plans", command.planId,
                    mapOf("planId" to command.planId, "code" to it.code), reservationId,
                )
            }
        }
    }

    override fun replacePlanEntitlements(
        command: ReplacePlanEntitlementsCommand,
    ): List<EntitlementSummary> {
        require(command.entitlements.size <= 500) { "A plan supports at most 500 entitlements" }
        val normalized = command.entitlements.map(::validatedEntitlement)
        require(normalized.map { it.code }.distinct().size == normalized.size) {
            "Plan entitlement codes must be unique"
        }
        return platformMutation(
            operation = "platform.plan.entitlements.replace",
            tenantId = null,
            payload = command,
            resourceType = "plan_entitlements",
            responseType = EntitlementListReplay::class.java,
        ) { reservationId ->
            plan(command.planId)
            jdbcTemplate.update(
                "UPDATE plan_entitlements SET is_enabled = false, updated_at = now() WHERE plan_id = ?",
                command.planId,
            )
            normalized.forEach { entitlement ->
                jdbcTemplate.update(
                    """
                    INSERT INTO plan_entitlements (
                        plan_id, entitlement_code, entitlement_value, is_enabled
                    ) VALUES (?, ?, ?::jsonb, ?)
                    ON CONFLICT (plan_id, entitlement_code) DO UPDATE SET
                        entitlement_value = EXCLUDED.entitlement_value,
                        is_enabled = EXCLUDED.is_enabled,
                        updated_at = now()
                    """.trimIndent(),
                    command.planId,
                    entitlement.code,
                    objectMapper.writeValueAsString(entitlement.value),
                    entitlement.enabled,
                )
            }
            planEntitlements(command.planId).also {
                recordCommercialSideEffects(
                    "platform.plans.entitlements.replaced", null,
                    "plans", command.planId,
                    mapOf("planId" to command.planId, "entitlementCount" to normalized.size),
                    reservationId,
                )
            }
        }
    }

    override fun commercialOverview(tenantId: UUID, tenantView: Boolean): TenantCommercialOverview {
        return requireNotNull(
            transactionTemplate.execute {
                if (tenantView) {
                    tenantPermissionAccessPort.requireAuthorized(
                        TenantPermissionAccessRequest(tenantId, "tenant.subscription.view"),
                    )
                } else {
                    requirePlatformBillingAccess(tenantId, "platform.tenant.commercial.view")
                }
                TenantCommercialOverview(
                    tenantId = tenantId,
                    subscription = currentSubscription(tenantId),
                    entitlements = effectiveEntitlements(tenantId),
                    latestUsage = latestUsage(tenantId),
                )
            },
        )
    }

    override fun changeSubscription(
        command: ChangeTenantSubscriptionCommand,
    ): TenantSubscriptionSummary {
        require(command.reason.isNotBlank()) { "Subscription change reason is required" }
        val status = command.status.normalizedSubscriptionStatus()
        val cycle = command.billingCycle.normalizedBillingCycle()
        require(command.billingCurrency.matches(Regex("[A-Z]{3}"))) {
            "billingCurrency must be an ISO 4217 code"
        }
        return platformMutation(
            operation = "platform.tenant.subscription.change",
            tenantId = command.tenantId,
            payload = command,
            resourceType = "tenant_subscriptions",
            responseType = TenantSubscriptionSummary::class.java,
        ) { reservationId ->
            val targetPlan = plan(command.planId)
            require(targetPlan.isActive) { "Subscription plan must be active" }
            val current = lockedCurrentSubscription(command.tenantId)
            if (command.expectedVersion != null && current != null &&
                current.version != command.expectedVersion
            ) {
                throw PlatformControlConflictException(
                    "Subscription changed; expected version ${command.expectedVersion} but found ${current.version}",
                )
            }
            val subscriptionId = current?.subscriptionId ?: UUID.randomUUID()
            if (current == null) {
                jdbcTemplate.update(
                    """
                    INSERT INTO tenant_subscriptions (
                        id, tenant_id, plan_id, status, billing_cycle, billing_currency,
                        provider, provider_customer_id, provider_subscription_id,
                        current_period_starts_at, current_period_ends_at, trial_ends_at,
                        grace_period_ends_at, cancel_at_period_end, created_by_platform_user_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    subscriptionId, command.tenantId, command.planId, status, cycle,
                    command.billingCurrency, command.provider.normalizedProvider(),
                    command.providerCustomerId?.trim(), command.providerSubscriptionId?.trim(),
                    command.currentPeriodEndsAt?.let(Timestamp::from),
                    command.trialEndsAt?.let(Timestamp::from),
                    command.gracePeriodEndsAt?.let(Timestamp::from),
                    command.cancelAtPeriodEnd, currentPlatformActorId(),
                )
            } else {
                val updated = jdbcTemplate.update(
                    """
                    UPDATE tenant_subscriptions
                    SET plan_id = ?, status = ?, billing_cycle = ?, billing_currency = ?,
                        provider = ?, provider_customer_id = ?, provider_subscription_id = ?,
                        current_period_ends_at = ?, trial_ends_at = ?, grace_period_ends_at = ?,
                        cancel_at_period_end = ?, version = version + 1, updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND version = ?
                    """.trimIndent(),
                    command.planId, status, cycle, command.billingCurrency,
                    command.provider.normalizedProvider(), command.providerCustomerId?.trim(),
                    command.providerSubscriptionId?.trim(),
                    command.currentPeriodEndsAt?.let(Timestamp::from),
                    command.trialEndsAt?.let(Timestamp::from),
                    command.gracePeriodEndsAt?.let(Timestamp::from),
                    command.cancelAtPeriodEnd, current.subscriptionId, command.tenantId,
                    current.version,
                )
                if (updated != 1) {
                    throw PlatformControlConflictException("Concurrent subscription change detected")
                }
            }
            jdbcTemplate.update(
                """
                UPDATE tenants SET plan_id = ?, billing_cycle = ?,
                    subscription_ends_at = ?, trial_ends_at = ?, updated_at = now()
                WHERE id = ? AND deleted_at IS NULL
                """.trimIndent(),
                command.planId,
                if (cycle == "annually") "annually" else "monthly",
                command.currentPeriodEndsAt?.let(Timestamp::from),
                command.trialEndsAt?.let(Timestamp::from),
                command.tenantId,
            )
            jdbcTemplate.update(
                """
                UPDATE tenant_control_states SET subscription_status = ?, version = version + 1,
                    updated_by_platform_user_id = ? WHERE tenant_id = ?
                """.trimIndent(),
                status, currentPlatformActorId(), command.tenantId,
            )
            val result = subscriptionById(command.tenantId, subscriptionId)
                ?: throw PlatformControlNotFoundException("Updated subscription was not found")
            recordCommercialSideEffects(
                "platform.tenants.subscription.changed", command.tenantId,
                "tenant_subscriptions", result.subscriptionId,
                mapOf(
                    "tenantId" to command.tenantId,
                    "planId" to command.planId,
                    "status" to status,
                    "reason" to command.reason.trim(),
                ),
                reservationId,
            )
            result
        }
    }

    override fun createEntitlementOverride(
        command: CreateEntitlementOverrideCommand,
    ): EntitlementSummary {
        require(command.reason.isNotBlank()) { "Entitlement override reason is required" }
        command.expiresAt?.let { require(it > Instant.now()) { "Override expiry must be in the future" } }
        val code = command.code.normalizedEntitlementCode()
        return platformMutation(
            operation = "platform.tenant.entitlement.override.create",
            tenantId = command.tenantId,
            payload = command,
            resourceType = "tenant_entitlement_overrides",
            responseType = EntitlementSummary::class.java,
        ) { reservationId ->
            currentSubscription(command.tenantId)
                ?: throw PlatformControlNotFoundException("Tenant subscription was not found")
            val id = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO tenant_entitlement_overrides (
                    id, tenant_id, entitlement_code, entitlement_value, is_enabled,
                    reason, expires_at, approved_by_platform_user_id
                ) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """.trimIndent(),
                id, command.tenantId, code, objectMapper.writeValueAsString(command.value),
                command.enabled, command.reason.trim(),
                command.expiresAt?.let(Timestamp::from), currentPlatformActorId(),
            )
            EntitlementSummary(
                code, command.enabled, command.value, "override", Instant.now(),
                command.expiresAt, id,
            ).also {
                recordCommercialSideEffects(
                    "platform.tenants.entitlement.override.created", command.tenantId,
                    "tenant_entitlement_overrides", id,
                    mapOf("tenantId" to command.tenantId, "code" to code,
                        "enabled" to command.enabled, "reason" to command.reason.trim()),
                    reservationId,
                )
            }
        }
    }

    override fun revokeEntitlementOverride(
        tenantId: UUID,
        overrideId: UUID,
        reason: String,
    ): EntitlementSummary {
        require(reason.isNotBlank()) { "Entitlement revocation reason is required" }
        return platformMutation(
            operation = "platform.tenant.entitlement.override.revoke",
            tenantId = tenantId,
            payload = mapOf("tenantId" to tenantId, "overrideId" to overrideId, "reason" to reason),
            resourceType = "tenant_entitlement_overrides",
            responseType = EntitlementSummary::class.java,
        ) { reservationId ->
            val before = override(tenantId, overrideId)
            jdbcTemplate.update(
                """
                UPDATE tenant_entitlement_overrides SET revoked_at = now(),
                    revoked_by_platform_user_id = ?
                WHERE id = ? AND tenant_id = ? AND revoked_at IS NULL
                """.trimIndent(),
                currentPlatformActorId(), overrideId, tenantId,
            )
            before.copy(enabled = false, source = "revoked_override").also {
                recordCommercialSideEffects(
                    "platform.tenants.entitlement.override.revoked", tenantId,
                    "tenant_entitlement_overrides", overrideId,
                    mapOf("tenantId" to tenantId, "code" to before.code, "reason" to reason.trim()),
                    reservationId,
                )
            }
        }
    }

    override fun captureUsageSnapshot(tenantId: UUID, snapshotDate: LocalDate): TenantUsageSummary {
        return requireNotNull(
            transactionTemplate.execute {
                requirePlatformBillingAccess(tenantId, "platform.tenant.usage.capture")
                val counts = jdbcTemplate.queryForMap(
                    """
                    SELECT
                        (SELECT count(*) FROM properties
                         WHERE tenant_id = ? AND deleted_at IS NULL) AS property_count,
                        (SELECT count(*) FROM rooms
                         WHERE tenant_id = ? AND deleted_at IS NULL) AS room_count,
                        (SELECT count(*) FROM users
                         WHERE tenant_id = ? AND deleted_at IS NULL) AS user_count,
                        (SELECT count(*) FROM outlets
                         WHERE tenant_id = ? AND deleted_at IS NULL) AS outlet_count,
                        (SELECT count(*) FROM audit_logs
                         WHERE tenant_id = ? AND created_at::date = ?) AS activity_events
                    """.trimIndent(),
                    tenantId, tenantId, tenantId, tenantId, tenantId, snapshotDate,
                )
                val properties = (counts["property_count"] as Number).toInt()
                val rooms = (counts["room_count"] as Number).toInt()
                val users = (counts["user_count"] as Number).toInt()
                val outlets = (counts["outlet_count"] as Number).toInt()
                val activityEvents = (counts["activity_events"] as Number).toLong()
                val metrics = mapOf(
                    "activityEvents" to activityEvents,
                    "capturedAt" to Instant.now().toString(),
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO tenant_usage_snapshots (
                        tenant_id, snapshot_date, property_count, room_count, user_count,
                        outlet_count, storage_bytes, api_calls, metrics
                    ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?::jsonb)
                    ON CONFLICT (tenant_id, snapshot_date) DO UPDATE SET
                        property_count = EXCLUDED.property_count,
                        room_count = EXCLUDED.room_count,
                        user_count = EXCLUDED.user_count,
                        outlet_count = EXCLUDED.outlet_count,
                        api_calls = EXCLUDED.api_calls,
                        metrics = EXCLUDED.metrics
                    """.trimIndent(),
                    tenantId, snapshotDate, properties, rooms, users, outlets,
                    activityEvents, objectMapper.writeValueAsString(metrics),
                )
                TenantUsageSummary(
                    tenantId, snapshotDate, properties, rooms, users, outlets,
                    0, activityEvents, metrics,
                )
            },
        )
    }

    /**
     * Delegates to `effective_tenant_entitlement` rather than resolving entitlements a
     * second way.
     *
     * This method used to carry its own copy of the resolution SQL, and the two had
     * drifted: the function knows about purchased product grants, synthesises `limit.*`
     * from the plan's capacity columns, and is the version that
     * `assert_tenant_entitlement_enabled` and `assert_tenant_capacity` actually enforce.
     * A tenant could therefore be told here that a limit was unset while the database
     * refused the write, or be shown as unentitled to something they had bought. One
     * resolver, and it is the one the enforcement path uses.
     */
    override fun effectiveEntitlement(tenantId: UUID, entitlementCode: String): EffectiveEntitlement {
        val code = entitlementCode.normalizedEntitlementCode()
        val row = jdbcTemplate.query(
            """
            SELECT is_enabled, entitlement_value::text AS entitlement_value, source
            FROM effective_tenant_entitlement(?, ?)
            """.trimIndent(),
            { rs, _ ->
                EffectiveEntitlement(
                    tenantId, code, rs.getBoolean("is_enabled"),
                    jsonMap(rs.getString("entitlement_value")), rs.getString("source"), Instant.now(),
                )
            },
            tenantId, code,
        ).singleOrNull()
        return row ?: EffectiveEntitlement(
            tenantId, code, enabled = false, value = emptyMap(), source = "none", resolvedAt = Instant.now(),
        )
    }

    override fun requireEnabled(tenantId: UUID, entitlementCode: String) {
        val entitlement = effectiveEntitlement(tenantId, entitlementCode)
        if (!entitlement.enabled) {
            throw PlatformControlConflictException(
                "Tenant is not entitled to ${entitlement.code}",
            )
        }
    }

    override fun requireWithinLimit(
        tenantId: UUID,
        entitlementCode: String,
        currentUsage: Long,
        increment: Long,
    ) {
        require(increment > 0) { "Limit increment must be positive" }
        val entitlement = effectiveEntitlement(tenantId, entitlementCode)
        if (!entitlement.enabled) {
            throw PlatformControlConflictException("Tenant limit ${entitlement.code} is disabled")
        }
        val limit = (entitlement.value["limit"] as? Number)?.toLong()
            ?: throw PlatformControlConflictException("Tenant limit ${entitlement.code} is not configured")
        if (currentUsage + increment > limit) {
            throw PlatformControlConflictException(
                "Tenant limit ${entitlement.code} would be exceeded ($currentUsage + $increment > $limit)",
            )
        }
    }

    private fun effectiveEntitlements(tenantId: UUID): List<EntitlementSummary> {
        val subscription = currentSubscription(tenantId) ?: return emptyList()
        val codes = jdbcTemplate.queryForList(
            """
            SELECT entitlement_code FROM plan_entitlements WHERE plan_id = ?
            UNION
            SELECT entitlement_code FROM tenant_entitlement_overrides
            WHERE tenant_id = ? AND revoked_at IS NULL
              AND starts_at <= now() AND (expires_at IS NULL OR expires_at > now())
            ORDER BY entitlement_code
            """.trimIndent(),
            String::class.java,
            subscription.planId, tenantId,
        ).filterNotNull()
        return codes.map { code ->
            val effective = effectiveEntitlement(tenantId, code)
            EntitlementSummary(
                effective.code, effective.enabled, effective.value, effective.source,
                null, null, null,
            )
        }
    }

    private fun <T : Any> platformMutation(
        operation: String,
        tenantId: UUID?,
        payload: Any,
        resourceType: String,
        responseType: Class<*>,
        block: (UUID) -> T,
    ): T {
        return requireNotNull(
            transactionTemplate.execute {
                requirePlatformBillingAccess(tenantId, operation)
                when (
                    val reservation = idempotencyPort.reserve(
                        IdempotencyCommand(operation, payload, resourceType),
                    )
                ) {
                    is IdempotencyReservation.Started -> {
                        val result = block(reservation.recordId)
                        idempotencyPort.markSucceeded(
                            reservation.recordId, 200, result, resourceId(result),
                        )
                        result
                    }
                    is IdempotencyReservation.Replay -> {
                        if (reservation.responseBody.isNullOrBlank()) {
                            throw PlatformControlConflictException("Commercial replay response is missing")
                        }
                        @Suppress("UNCHECKED_CAST")
                        when (responseType) {
                            EntitlementListReplay::class.java -> objectMapper.readValue(
                                reservation.responseBody,
                                objectMapper.typeFactory.constructCollectionType(
                                    List::class.java, EntitlementSummary::class.java,
                                ),
                            ) as T
                            else -> objectMapper.readValue(reservation.responseBody, responseType) as T
                        }
                    }
                    is IdempotencyReservation.InProgress -> throw PlatformControlInProgressException(
                        "Commercial control command is already in progress",
                    )
                    is IdempotencyReservation.Conflict -> throw PlatformControlConflictException(
                        "Idempotency key was used for a different commercial command",
                    )
                }
            },
        )
    }

    private fun resourceId(value: Any): UUID? = when (value) {
        is PlanSummary -> value.planId
        is TenantSubscriptionSummary -> value.subscriptionId
        is EntitlementSummary -> value.overrideId
        else -> null
    }

    private fun recordCommercialSideEffects(
        action: String,
        tenantId: UUID?,
        resourceType: String,
        resourceId: UUID,
        payload: Map<String, Any?>,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordPlatformEvent(
            PlatformAuditEvent(
                action = action,
                targetTenantId = tenantId,
                resource = AuditResource(resourceType, resourceId),
                after = payload,
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = resourceType,
                aggregateId = resourceId,
                tenantId = null,
                eventType = action,
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                idempotencyKeyId = idempotencyKeyId,
                priority = 3,
            ),
        )
    }

    private fun requirePlatformBillingAccess(tenantId: UUID?, operation: String) {
        platformAccessPort.requireAuthorized(
            PlatformAccessRequest(tenantId, "platform.billing.manage", operation),
        )
    }

    private fun currentPlatformActorId(): UUID = when (
        val identity = requestContextHolder.current().identity
    ) {
        is RequestIdentity.Platform -> identity.platformUserId
        is RequestIdentity.Support -> identity.platformUserId
        else -> throw IllegalStateException("Platform identity is required")
    }

    private fun plan(planId: UUID): PlanSummary {
        return jdbcTemplate.query(
            "$PLAN_SELECT WHERE plan.id = ?",
            { rs, _ -> mapPlan(rs) },
            planId,
        ).singleOrNull()?.let { it.copy(entitlements = planEntitlements(planId)) }
            ?: throw PlatformControlNotFoundException("Plan was not found")
    }

    private fun mapPlan(rs: ResultSet): PlanSummary = PlanSummary(
        planId = rs.getObject("id", UUID::class.java),
        code = rs.getString("code"),
        name = rs.getString("name"),
        maxProperties = rs.getInt("max_properties"),
        maxRooms = rs.getInt("max_rooms"),
        maxUsers = rs.getInt("max_users"),
        maxOutlets = rs.getInt("max_outlets"),
        monthlyUsd = rs.getBigDecimal("monthly_usd"),
        annualUsd = rs.getBigDecimal("annual_usd"),
        isActive = rs.getBoolean("is_active"),
        entitlements = emptyList(),
    )

    private fun planEntitlements(planId: UUID): List<EntitlementSummary> {
        return jdbcTemplate.query(
            """
            SELECT entitlement_code, entitlement_value::text AS entitlement_value, is_enabled
            FROM plan_entitlements WHERE plan_id = ? ORDER BY entitlement_code
            """.trimIndent(),
            { rs, _ ->
                EntitlementSummary(
                    code = rs.getString("entitlement_code"),
                    enabled = rs.getBoolean("is_enabled"),
                    value = jsonMap(rs.getString("entitlement_value")),
                    source = "plan",
                    startsAt = null,
                    expiresAt = null,
                )
            },
            planId,
        )
    }

    private fun override(tenantId: UUID, overrideId: UUID): EntitlementSummary {
        return jdbcTemplate.query(
            """
            SELECT id, entitlement_code, entitlement_value::text AS entitlement_value,
                   is_enabled, starts_at, expires_at, revoked_at
            FROM tenant_entitlement_overrides WHERE tenant_id = ? AND id = ? FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                EntitlementSummary(
                    rs.getString("entitlement_code"),
                    rs.getBoolean("is_enabled") && rs.getTimestamp("revoked_at") == null,
                    jsonMap(rs.getString("entitlement_value")),
                    if (rs.getTimestamp("revoked_at") == null) "override" else "revoked_override",
                    rs.getTimestamp("starts_at").toInstant(),
                    rs.getTimestamp("expires_at")?.toInstant(),
                    rs.getObject("id", UUID::class.java),
                )
            },
            tenantId, overrideId,
        ).singleOrNull() ?: throw PlatformControlNotFoundException("Entitlement override was not found")
    }

    private fun currentSubscription(tenantId: UUID): TenantSubscriptionSummary? {
        return subscriptionQuery(tenantId, forUpdate = false)
    }

    private fun lockedCurrentSubscription(tenantId: UUID): TenantSubscriptionSummary? {
        return subscriptionQuery(tenantId, forUpdate = true)
    }

    private fun subscriptionById(
        tenantId: UUID,
        subscriptionId: UUID,
    ): TenantSubscriptionSummary? = jdbcTemplate.query(
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
        WHERE subscription.tenant_id = ? AND subscription.id = ?
        """.trimIndent(),
        { rs, _ -> mapSubscription(rs) }, tenantId, subscriptionId,
    ).singleOrNull()

    private fun subscriptionQuery(tenantId: UUID, forUpdate: Boolean): TenantSubscriptionSummary? {
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
            ${if (forUpdate) "FOR UPDATE OF subscription" else ""}
            """.trimIndent(),
            { rs, _ -> mapSubscription(rs) },
            tenantId,
        ).singleOrNull()
    }

    private fun mapSubscription(rs: ResultSet): TenantSubscriptionSummary = TenantSubscriptionSummary(
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

    private fun latestUsage(tenantId: UUID): TenantUsageSummary? {
        return jdbcTemplate.query(
            """
            SELECT tenant_id, snapshot_date, property_count, room_count, user_count,
                   outlet_count, storage_bytes, api_calls, metrics::text AS metrics
            FROM tenant_usage_snapshots WHERE tenant_id = ?
            ORDER BY snapshot_date DESC LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                TenantUsageSummary(
                    rs.getObject("tenant_id", UUID::class.java),
                    rs.getObject("snapshot_date", LocalDate::class.java),
                    rs.getInt("property_count"), rs.getInt("room_count"),
                    rs.getInt("user_count"), rs.getInt("outlet_count"),
                    rs.getLong("storage_bytes"), rs.getLong("api_calls"),
                    jsonMap(rs.getString("metrics")),
                )
            },
            tenantId,
        ).singleOrNull()
    }

    private fun seedLimitEntitlements(planId: UUID, command: CreatePlanCommand) {
        listOf(
            "limit.properties" to command.maxProperties,
            "limit.rooms" to command.maxRooms,
            "limit.users" to command.maxUsers,
            "limit.outlets" to command.maxOutlets,
        ).forEach { (code, limit) -> upsertLimit(planId, code, limit) }
    }

    private fun synchronizeLimitEntitlements(plan: PlanSummary) {
        listOf(
            "limit.properties" to plan.maxProperties,
            "limit.rooms" to plan.maxRooms,
            "limit.users" to plan.maxUsers,
            "limit.outlets" to plan.maxOutlets,
        ).forEach { (code, limit) -> upsertLimit(plan.planId, code, limit) }
    }

    private fun upsertLimit(planId: UUID, code: String, limit: Int) {
        jdbcTemplate.update(
            """
            INSERT INTO plan_entitlements (plan_id, entitlement_code, entitlement_value, is_enabled)
            VALUES (?, ?, jsonb_build_object('limit', ?), true)
            ON CONFLICT (plan_id, entitlement_code) DO UPDATE SET
                entitlement_value = EXCLUDED.entitlement_value,
                is_enabled = true, updated_at = now()
            """.trimIndent(),
            planId, code, limit,
        )
    }

    private fun validatePlanLimits(
        properties: Int,
        rooms: Int,
        users: Int,
        outlets: Int,
        monthly: BigDecimal,
        annual: BigDecimal,
    ) {
        require(properties in 1..100_000) { "maxProperties must be between 1 and 100000" }
        require(rooms in 1..10_000_000) { "maxRooms must be between 1 and 10000000" }
        require(users in 1..10_000_000) { "maxUsers must be between 1 and 10000000" }
        require(outlets in 0..1_000_000) { "maxOutlets must be between 0 and 1000000" }
        require(monthly >= BigDecimal.ZERO && annual >= BigDecimal.ZERO) {
            "Plan prices cannot be negative"
        }
    }

    private fun validatedEntitlement(value: EntitlementDefinition): EntitlementDefinition {
        return value.copy(code = value.code.normalizedEntitlementCode()).also {
            require(it.value.size <= 50) { "Entitlement value supports at most 50 fields" }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonMap(raw: String): Map<String, Any?> =
        objectMapper.readValue(raw, Map::class.java) as Map<String, Any?>

    private fun String.normalizedRequired(field: String): String = trim().takeIf(String::isNotEmpty)
        ?: throw IllegalArgumentException("$field is required")

    private fun String.normalizedCode(): String = trim().lowercase().also {
        require(it.matches(Regex("[a-z][a-z0-9_-]{1,49}"))) { "Invalid plan code" }
    }

    private fun String.normalizedEntitlementCode(): String = trim().lowercase().also {
        require(it.matches(Regex("[a-z][a-z0-9_.-]{1,99}"))) { "Invalid entitlement code" }
    }

    private fun String.normalizedSubscriptionStatus(): String = trim().lowercase().also {
        require(it in setOf("trialing", "active", "past_due", "paused", "cancelled", "expired")) {
            "Unsupported subscription status"
        }
    }

    private fun String.normalizedBillingCycle(): String = trim().lowercase().also {
        require(it in setOf("monthly", "annually", "contract")) { "Unsupported billing cycle" }
    }

    private fun String.normalizedProvider(): String = trim().lowercase().also {
        require(it.matches(Regex("[a-z][a-z0-9_-]{1,29}"))) { "Invalid billing provider" }
    }

    private class EntitlementListReplay

    private companion object {
        val PLAN_SELECT = """
            SELECT plan.id, plan.code, plan.name, plan.max_properties, plan.max_rooms,
                   plan.max_users, plan.max_outlets, plan.monthly_usd, plan.annual_usd,
                   plan.is_active
            FROM plans plan
        """.trimIndent()
    }
}
