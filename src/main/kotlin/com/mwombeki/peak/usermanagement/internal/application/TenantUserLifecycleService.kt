package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.RevokeTenantUserIdentityLinkCommand
import com.mwombeki.peak.usermanagement.api.TenantUserIdentityLinkRevocationReceipt
import com.mwombeki.peak.usermanagement.api.TenantUserLifecycleAction
import com.mwombeki.peak.usermanagement.api.TenantUserLifecycleCommand
import com.mwombeki.peak.usermanagement.api.TenantUserLifecycleConflictException
import com.mwombeki.peak.usermanagement.api.TenantUserLifecycleInProgressException
import com.mwombeki.peak.usermanagement.api.TenantUserLifecycleNotFoundException
import com.mwombeki.peak.usermanagement.api.TenantUserLifecyclePort
import com.mwombeki.peak.usermanagement.api.TenantUserLifecycleReceipt
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class TenantUserLifecycleService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) : TenantUserLifecyclePort {
    override fun changeTenantUserLifecycle(
        command: TenantUserLifecycleCommand,
    ): TenantUserLifecycleReceipt {
        return requireNotNull(
            transactionTemplate.execute {
                changeLifecycleInsideTransaction(command)
            },
        )
    }

    override fun revokeTenantUserIdentityLink(
        command: RevokeTenantUserIdentityLinkCommand,
    ): TenantUserIdentityLinkRevocationReceipt {
        return requireNotNull(
            transactionTemplate.execute {
                revokeIdentityLinkInsideTransaction(command)
            },
        )
    }

    private fun changeLifecycleInsideTransaction(
        command: TenantUserLifecycleCommand,
    ): TenantUserLifecycleReceipt {
        val actorUserId = requireTenantActor(command.tenantId)
        require(actorUserId != command.userId) {
            "Tenant user cannot change own lifecycle state"
        }
        databaseSessionContext.bind(requestContextHolder.current().identity)
        lockTenantForAdministratorContinuity(command.tenantId)

        val reservation = idempotencyPort.reserve(
            IdempotencyCommand(
                operationType = "tenant.user.lifecycle.${command.action.databaseValue}",
                requestPayload = mapOf(
                    "tenantId" to command.tenantId,
                    "userId" to command.userId,
                    "action" to command.action.databaseValue,
                ),
                resourceType = "users",
            ),
        )

        return when (reservation) {
            is IdempotencyReservation.Started -> applyLifecycleChange(
                command = command,
                actorUserId = actorUserId,
                idempotencyKeyId = reservation.recordId,
            )

            is IdempotencyReservation.Replay -> replayLifecycleChange(reservation)
            is IdempotencyReservation.InProgress -> throw TenantUserLifecycleInProgressException(
                "Tenant user lifecycle change is already being processed for this idempotency key",
            )

            is IdempotencyReservation.Conflict -> throw TenantUserLifecycleConflictException(
                "Idempotency key was already used for a different tenant user lifecycle request",
            )
        }
    }

    private fun applyLifecycleChange(
        command: TenantUserLifecycleCommand,
        actorUserId: UUID,
        idempotencyKeyId: UUID,
    ): TenantUserLifecycleReceipt {
        val before = findTenantUserForUpdate(command.tenantId, command.userId)
            ?: throw TenantUserLifecycleNotFoundException("Tenant user was not found")
        requireActorCanManageTargetUser(command.tenantId, actorUserId, command.userId)
        val after = before.transition(command.action)
        val changed = before != after
        if (changed && command.action.removesOperationalAccess) {
            requireTenantAdministratorContinuity(command.tenantId, command.userId)
            lockAndRequirePropertyAdministratorContinuity(command.tenantId, command.userId)
        }

        if (changed) {
            jdbcTemplate.update(
                """
                UPDATE users
                SET status = ?,
                    is_active = ?,
                    locked_until = ?
                WHERE tenant_id = ?
                  AND id = ?
                """.trimIndent(),
                after.status,
                after.isActive,
                after.lockedUntil?.let(Timestamp::from),
                command.tenantId,
                command.userId,
            )
        }

        val receipt = after.toReceipt(command.action, changed)

        if (changed) {
            recordLifecycleSideEffects(command, before, after, idempotencyKeyId)
        }

        idempotencyPort.markSucceeded(
            recordId = idempotencyKeyId,
            responseCode = 200,
            responseBody = receipt,
            resourceId = command.userId,
        )

        return receipt
    }

    private fun revokeIdentityLinkInsideTransaction(
        command: RevokeTenantUserIdentityLinkCommand,
    ): TenantUserIdentityLinkRevocationReceipt {
        val actorUserId = requireTenantActor(command.tenantId)
        require(actorUserId != command.userId) {
            "Tenant user cannot revoke own identity link"
        }
        databaseSessionContext.bind(requestContextHolder.current().identity)
        lockTenantForAdministratorContinuity(command.tenantId)

        val reservation = idempotencyPort.reserve(
            IdempotencyCommand(
                operationType = "tenant.user.identity_link.revoke",
                requestPayload = mapOf(
                    "tenantId" to command.tenantId,
                    "userId" to command.userId,
                    "identityLinkId" to command.identityLinkId,
                ),
                resourceType = "identity_links",
            ),
        )

        return when (reservation) {
            is IdempotencyReservation.Started -> applyIdentityLinkRevocation(
                command = command,
                actorUserId = actorUserId,
                idempotencyKeyId = reservation.recordId,
            )

            is IdempotencyReservation.Replay -> replayIdentityLinkRevocation(reservation)
            is IdempotencyReservation.InProgress -> throw TenantUserLifecycleInProgressException(
                "Tenant user identity link revocation is already being processed for this idempotency key",
            )

            is IdempotencyReservation.Conflict -> throw TenantUserLifecycleConflictException(
                "Idempotency key was already used for a different identity link revocation request",
            )
        }
    }

    private fun applyIdentityLinkRevocation(
        command: RevokeTenantUserIdentityLinkCommand,
        actorUserId: UUID,
        idempotencyKeyId: UUID,
    ): TenantUserIdentityLinkRevocationReceipt {
        findTenantUserForUpdate(command.tenantId, command.userId)
            ?: throw TenantUserLifecycleNotFoundException("Tenant user was not found")
        requireActorCanManageTargetUser(command.tenantId, actorUserId, command.userId)

        val before = findIdentityLinkForUpdate(command)
            ?: throw TenantUserLifecycleNotFoundException("Tenant user identity link was not found")
        val changed = before.revokedAt == null
        if (changed && !hasAnotherActiveIdentityLink(command)) {
            requireTenantAdministratorContinuity(command.tenantId, command.userId)
            lockAndRequirePropertyAdministratorContinuity(command.tenantId, command.userId)
        }

        val revokedAt = if (changed) {
            jdbcTemplate.queryForObject(
                """
                UPDATE identity_links
                SET revoked_at = now()
                WHERE id = ?
                RETURNING revoked_at
                """.trimIndent(),
                { rs, _ -> rs.getTimestamp("revoked_at").toInstant() },
                command.identityLinkId,
            )
        } else {
            before.revokedAt
        }

        val receipt = TenantUserIdentityLinkRevocationReceipt(
            tenantId = command.tenantId,
            userId = command.userId,
            identityLinkId = command.identityLinkId,
            revokedAt = revokedAt,
            changed = changed,
            replayed = false,
        )

        if (changed) {
            recordIdentityLinkRevocationSideEffects(command, revokedAt, idempotencyKeyId)
        }

        idempotencyPort.markSucceeded(
            recordId = idempotencyKeyId,
            responseCode = 200,
            responseBody = receipt,
            resourceId = command.identityLinkId,
        )

        return receipt
    }

    private fun requireTenantActor(tenantId: UUID): UUID {
        val identity = requestContextHolder.current().identity
        require(identity is RequestIdentity.Tenant) {
            "Tenant user identity is required"
        }
        require(identity.tenantId == tenantId) {
            "Requested tenant does not match identity"
        }
        return identity.tenantUserId
    }

    private fun requireActorCanManageTargetUser(
        tenantId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
    ) {
        val tenantSuperAdmin = jdbcTemplate.queryForObject(
            "SELECT user_has_tenant_permission(?, ?, ?)",
            Boolean::class.java,
            actorUserId,
            tenantId,
            TENANT_ADMIN_ALL_PERMISSION,
        ) == true
        if (tenantSuperAdmin) {
            return
        }

        val missingTenantPermissions = targetTenantPermissionCodes(tenantId, targetUserId)
            .filterNot { permissionCode ->
                jdbcTemplate.queryForObject(
                    "SELECT user_has_tenant_permission(?, ?, ?)",
                    Boolean::class.java,
                    actorUserId,
                    tenantId,
                    permissionCode,
                ) == true
            }
        require(missingTenantPermissions.isEmpty()) {
            "Tenant user cannot manage a user with permissions the actor does not hold"
        }

        val missingPropertyPermissions = targetPropertyPermissionGrants(tenantId, targetUserId)
            .filterNot { grant ->
                jdbcTemplate.queryForObject(
                    "SELECT user_has_property_permission(?, ?, ?, ?)",
                    Boolean::class.java,
                    actorUserId,
                    tenantId,
                    grant.propertyId,
                    grant.permissionCode,
                ) == true
            }
        require(missingPropertyPermissions.isEmpty()) {
            "Tenant user cannot manage a user with permissions the actor does not hold"
        }
    }

    private fun targetTenantPermissionCodes(
        tenantId: UUID,
        targetUserId: UUID,
    ): List<String> {
        return jdbcTemplate.queryForList(
            """
            SELECT DISTINCT p.code
            FROM user_tenant_roles utr
            JOIN tenant_roles tr
              ON tr.id = utr.tenant_role_id
             AND tr.tenant_id = utr.tenant_id
            JOIN tenant_role_permissions trp
              ON trp.tenant_role_id = tr.id
            JOIN permissions p
              ON p.id = trp.permission_id
             AND p.tenant_id = utr.tenant_id
            WHERE utr.tenant_id = ?
              AND utr.user_id = ?
              AND tr.is_active = true
            ORDER BY p.code
            """.trimIndent(),
            String::class.java,
            tenantId,
            targetUserId,
        ).filterNotNull()
    }

    private fun targetPropertyPermissionGrants(
        tenantId: UUID,
        targetUserId: UUID,
    ): List<PropertyPermissionGrant> {
        return jdbcTemplate.query(
            """
            SELECT DISTINCT upr.property_id, p.code
            FROM user_property_roles upr
            JOIN roles r
              ON r.id = upr.role_id
             AND r.tenant_id = upr.tenant_id
            JOIN role_permissions rp
              ON rp.role_id = r.id
            JOIN permissions p
              ON p.id = rp.permission_id
             AND p.tenant_id = upr.tenant_id
            WHERE upr.tenant_id = ?
              AND upr.user_id = ?
              AND r.is_active = true
            ORDER BY upr.property_id, p.code
            """.trimIndent(),
            { rs, _ ->
                PropertyPermissionGrant(
                    propertyId = rs.getObject("property_id", UUID::class.java),
                    permissionCode = rs.getString("code"),
                )
            },
            tenantId,
            targetUserId,
        )
    }

    private fun findTenantUserForUpdate(
        tenantId: UUID,
        userId: UUID,
    ): TenantUserRow? {
        return jdbcTemplate.query(
            """
            SELECT tenant_id, id, COALESCE(status, '') AS status, is_active, locked_until
            FROM users
            WHERE tenant_id = ?
              AND id = ?
              AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            ::mapTenantUser,
            tenantId,
            userId,
        ).singleOrNull()
    }

    private fun findIdentityLinkForUpdate(
        command: RevokeTenantUserIdentityLinkCommand,
    ): IdentityLinkRow? {
        return jdbcTemplate.query(
            """
            SELECT id, revoked_at
            FROM identity_links
            WHERE id = ?
              AND tenant_id = ?
              AND user_id = ?
              AND identity_mode = 'tenant'
            FOR UPDATE
            """.trimIndent(),
            ::mapIdentityLink,
            command.identityLinkId,
            command.tenantId,
            command.userId,
        ).singleOrNull()
    }

    private fun hasAnotherActiveIdentityLink(
        command: RevokeTenantUserIdentityLinkCommand,
    ): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM identity_links
                WHERE tenant_id = ?
                  AND user_id = ?
                  AND identity_mode = 'tenant'
                  AND id <> ?
                  AND revoked_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            command.tenantId,
            command.userId,
            command.identityLinkId,
        ) == true
    }

    private fun lockTenantForAdministratorContinuity(tenantId: UUID) {
        val tenant = jdbcTemplate.query(
            """
            SELECT id
            FROM tenants
            WHERE id = ?
              AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            tenantId,
        ).singleOrNull()
        if (tenant == null) {
            throw TenantUserLifecycleNotFoundException("Tenant was not found")
        }
    }

    private fun requireTenantAdministratorContinuity(
        tenantId: UUID,
        targetUserId: UUID,
    ) {
        val tenantRoleId = jdbcTemplate.query(
            """
            SELECT tr.id
            FROM user_tenant_roles utr
            JOIN tenant_roles tr
              ON tr.id = utr.tenant_role_id
             AND tr.tenant_id = utr.tenant_id
            WHERE utr.tenant_id = ?
              AND utr.user_id = ?
              AND tr.code = ?
              AND tr.is_system = true
              AND tr.is_active = true
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            tenantId,
            targetUserId,
            TENANT_ADMIN_ROLE_CODE,
        ).singleOrNull() ?: return

        val anotherAdministratorExists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM user_tenant_roles utr
                JOIN users u
                  ON u.id = utr.user_id
                 AND u.tenant_id = utr.tenant_id
                WHERE utr.tenant_id = ?
                  AND utr.tenant_role_id = ?
                  AND utr.user_id <> ?
                  AND u.status = 'active'
                  AND u.is_active = true
                  AND u.deleted_at IS NULL
                  AND (u.locked_until IS NULL OR u.locked_until <= now())
                  AND EXISTS (
                      SELECT 1
                      FROM identity_links il
                      WHERE il.tenant_id = u.tenant_id
                        AND il.user_id = u.id
                        AND il.identity_mode = 'tenant'
                        AND il.revoked_at IS NULL
                  )
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            tenantRoleId,
            targetUserId,
        ) == true
        require(anotherAdministratorExists) {
            "Tenant administrator access cannot be removed without another active administrator"
        }
    }

    private fun lockAndRequirePropertyAdministratorContinuity(
        tenantId: UUID,
        targetUserId: UUID,
    ) {
        val propertyIds = jdbcTemplate.queryForList(
            """
            SELECT upr.property_id
            FROM user_property_roles upr
            JOIN roles r
              ON r.id = upr.role_id
             AND r.tenant_id = upr.tenant_id
            JOIN properties property
              ON property.id = upr.property_id
             AND property.tenant_id = upr.tenant_id
            WHERE upr.tenant_id = ?
              AND upr.user_id = ?
              AND r.name = ?
              AND r.is_system = true
              AND r.is_active = true
              AND property.deleted_at IS NULL
            ORDER BY upr.property_id
            """.trimIndent(),
            UUID::class.java,
            tenantId,
            targetUserId,
            PROPERTY_ADMIN_ROLE_NAME,
        ).filterNotNull().distinct()

        propertyIds.forEach { propertyId ->
            jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM properties
                WHERE tenant_id = ?
                  AND id = ?
                  AND deleted_at IS NULL
                FOR UPDATE
                """.trimIndent(),
                UUID::class.java,
                tenantId,
                propertyId,
            )
            val anotherAdministratorExists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM user_property_roles upr
                    JOIN roles r
                      ON r.id = upr.role_id
                     AND r.tenant_id = upr.tenant_id
                    JOIN users u
                      ON u.id = upr.user_id
                     AND u.tenant_id = upr.tenant_id
                    WHERE upr.tenant_id = ?
                      AND upr.property_id = ?
                      AND upr.user_id <> ?
                      AND r.name = ?
                      AND r.is_system = true
                      AND r.is_active = true
                      AND u.status = 'active'
                      AND u.is_active = true
                      AND u.deleted_at IS NULL
                      AND (u.locked_until IS NULL OR u.locked_until <= now())
                      AND EXISTS (
                          SELECT 1
                          FROM identity_links il
                          WHERE il.tenant_id = u.tenant_id
                            AND il.user_id = u.id
                            AND il.identity_mode = 'tenant'
                            AND il.revoked_at IS NULL
                      )
                )
                """.trimIndent(),
                Boolean::class.java,
                tenantId,
                propertyId,
                targetUserId,
                PROPERTY_ADMIN_ROLE_NAME,
            ) == true
            require(anotherAdministratorExists) {
                "Property administrator access cannot be removed without another active administrator"
            }
        }
    }

    private fun recordLifecycleSideEffects(
        command: TenantUserLifecycleCommand,
        before: TenantUserRow,
        after: TenantUserRow,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = command.tenantId,
                action = "tenant.users.${command.action.databaseValue}",
                resource = AuditResource("users", command.userId),
                before = before.auditPayload(),
                after = after.auditPayload(),
            ),
        )

        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "users",
                aggregateId = command.userId,
                tenantId = command.tenantId,
                eventType = command.action.outboxEventType,
                destination = OutboxDestination.PLATFORM,
                payload = mapOf(
                    "tenantId" to command.tenantId,
                    "userId" to command.userId,
                    "action" to command.action.databaseValue,
                    "status" to after.status,
                    "isActive" to after.isActive,
                    "lockedUntil" to after.lockedUntil?.toString(),
                ),
                idempotencyKeyId = idempotencyKeyId,
                priority = 3,
            ),
        )
    }

    private fun recordIdentityLinkRevocationSideEffects(
        command: RevokeTenantUserIdentityLinkCommand,
        revokedAt: Instant?,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = command.tenantId,
                action = "tenant.users.identity_link.revoke",
                resource = AuditResource("identity_links", command.identityLinkId),
                after = mapOf(
                    "tenantId" to command.tenantId,
                    "userId" to command.userId,
                    "revokedAt" to revokedAt?.toString(),
                ),
            ),
        )

        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "identity_links",
                aggregateId = command.identityLinkId,
                tenantId = command.tenantId,
                eventType = "tenant.user.identity_link.revoked",
                destination = OutboxDestination.PLATFORM,
                payload = mapOf(
                    "tenantId" to command.tenantId,
                    "userId" to command.userId,
                    "identityLinkId" to command.identityLinkId,
                    "revokedAt" to revokedAt?.toString(),
                ),
                idempotencyKeyId = idempotencyKeyId,
                priority = 2,
            ),
        )
    }

    private fun replayLifecycleChange(
        reservation: IdempotencyReservation.Replay,
    ): TenantUserLifecycleReceipt {
        if (reservation.responseBody.isNullOrBlank()) {
            throw TenantUserLifecycleConflictException(
                "Tenant user lifecycle replay does not contain a stored response body",
            )
        }

        return objectMapper.readValue(
            reservation.responseBody,
            TenantUserLifecycleReceipt::class.java,
        ).copy(replayed = true)
    }

    private fun replayIdentityLinkRevocation(
        reservation: IdempotencyReservation.Replay,
    ): TenantUserIdentityLinkRevocationReceipt {
        if (reservation.responseBody.isNullOrBlank()) {
            throw TenantUserLifecycleConflictException(
                "Identity link revocation replay does not contain a stored response body",
            )
        }

        return objectMapper.readValue(
            reservation.responseBody,
            TenantUserIdentityLinkRevocationReceipt::class.java,
        ).copy(replayed = true)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapTenantUser(rs: ResultSet, rowNumber: Int): TenantUserRow {
        return TenantUserRow(
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            userId = rs.getObject("id", UUID::class.java),
            status = rs.getString("status"),
            isActive = rs.getBoolean("is_active"),
            lockedUntil = rs.getTimestamp("locked_until")?.toInstant(),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapIdentityLink(rs: ResultSet, rowNumber: Int): IdentityLinkRow {
        return IdentityLinkRow(
            identityLinkId = rs.getObject("id", UUID::class.java),
            revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
        )
    }

    private fun TenantUserRow.transition(action: TenantUserLifecycleAction): TenantUserRow {
        return when (action) {
            TenantUserLifecycleAction.DISABLE -> copy(
                status = "disabled",
                isActive = false,
                lockedUntil = null,
            )

            TenantUserLifecycleAction.REACTIVATE,
            TenantUserLifecycleAction.UNLOCK,
            -> copy(
                status = "active",
                isActive = true,
                lockedUntil = null,
            )

            TenantUserLifecycleAction.LOCK -> copy(
                status = "locked",
                isActive = true,
                lockedUntil = null,
            )
        }
    }

    private fun TenantUserRow.toReceipt(
        action: TenantUserLifecycleAction,
        changed: Boolean,
    ): TenantUserLifecycleReceipt {
        return TenantUserLifecycleReceipt(
            tenantId = tenantId,
            userId = userId,
            action = action,
            status = status,
            isActive = isActive,
            lockedUntil = lockedUntil,
            changed = changed,
            replayed = false,
        )
    }

    private fun TenantUserRow.auditPayload(): Map<String, Any?> {
        return mapOf(
            "tenantId" to tenantId,
            "userId" to userId,
            "status" to status,
            "isActive" to isActive,
            "lockedUntil" to lockedUntil?.toString(),
        )
    }

    private val TenantUserLifecycleAction.outboxEventType: String
        get() = when (this) {
            TenantUserLifecycleAction.DISABLE -> "tenant.user.disabled"
            TenantUserLifecycleAction.REACTIVATE -> "tenant.user.reactivated"
            TenantUserLifecycleAction.LOCK -> "tenant.user.locked"
            TenantUserLifecycleAction.UNLOCK -> "tenant.user.unlocked"
        }

    private val TenantUserLifecycleAction.removesOperationalAccess: Boolean
        get() = this == TenantUserLifecycleAction.DISABLE || this == TenantUserLifecycleAction.LOCK

    private data class TenantUserRow(
        val tenantId: UUID,
        val userId: UUID,
        val status: String,
        val isActive: Boolean,
        val lockedUntil: Instant?,
    )

    private data class IdentityLinkRow(
        val identityLinkId: UUID,
        val revokedAt: Instant?,
    )

    private data class PropertyPermissionGrant(
        val propertyId: UUID,
        val permissionCode: String,
    )

    private companion object {
        private const val TENANT_ADMIN_ALL_PERMISSION = "tenant.admin.all"
        private const val TENANT_ADMIN_ROLE_CODE = "tenant_admin"
        private const val PROPERTY_ADMIN_ROLE_NAME = "Property Administrator"
    }
}
