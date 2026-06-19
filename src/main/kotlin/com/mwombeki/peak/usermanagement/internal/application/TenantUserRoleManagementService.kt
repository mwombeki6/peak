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
import com.mwombeki.peak.usermanagement.api.AssignTenantUserRoleCommand
import com.mwombeki.peak.usermanagement.api.ListTenantPermissionsQuery
import com.mwombeki.peak.usermanagement.api.ListTenantRolesQuery
import com.mwombeki.peak.usermanagement.api.RevokeTenantUserRoleCommand
import com.mwombeki.peak.usermanagement.api.TenantPermissionSummary
import com.mwombeki.peak.usermanagement.api.TenantRoleSummary
import com.mwombeki.peak.usermanagement.api.TenantUserRoleAssignmentReceipt
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementConflictException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementInProgressException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementNotFoundException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementPort
import java.sql.Array
import java.sql.ResultSet
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class TenantUserRoleManagementService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) : TenantUserRoleManagementPort {
    override fun listTenantRoles(query: ListTenantRolesQuery): List<TenantRoleSummary> {
        return requireNotNull(
            transactionTemplate.execute {
                requireTenantActor(query.tenantId)
                databaseSessionContext.bind(requestContextHolder.current().identity)
                jdbcTemplate.query(
                    """
                    SELECT
                        tr.id,
                        tr.tenant_id,
                        tr.code,
                        tr.name,
                        tr.description,
                        tr.is_system,
                        tr.is_active,
                        COALESCE(
                            array_agg(p.code ORDER BY p.code) FILTER (WHERE p.code IS NOT NULL),
                            ARRAY[]::text[]
                        ) AS permission_codes
                    FROM tenant_roles tr
                    LEFT JOIN tenant_role_permissions trp
                        ON trp.tenant_role_id = tr.id
                    LEFT JOIN permissions p
                        ON p.id = trp.permission_id
                       AND p.tenant_id = tr.tenant_id
                    WHERE tr.tenant_id = ?
                    GROUP BY tr.id, tr.tenant_id, tr.code, tr.name, tr.description,
                             tr.is_system, tr.is_active
                    ORDER BY tr.is_system DESC, tr.name
                    """.trimIndent(),
                    ::mapTenantRole,
                    query.tenantId,
                )
            },
        )
    }

    override fun listTenantPermissions(
        query: ListTenantPermissionsQuery,
    ): List<TenantPermissionSummary> {
        return requireNotNull(
            transactionTemplate.execute {
                requireTenantActor(query.tenantId)
                databaseSessionContext.bind(requestContextHolder.current().identity)
                jdbcTemplate.query(
                    """
                    SELECT id, tenant_id, code, description
                    FROM permissions
                    WHERE tenant_id = ?
                    ORDER BY code
                    """.trimIndent(),
                    ::mapTenantPermission,
                    query.tenantId,
                )
            },
        )
    }

    override fun assignTenantUserRole(
        command: AssignTenantUserRoleCommand,
    ): TenantUserRoleAssignmentReceipt {
        return requireNotNull(
            transactionTemplate.execute {
                assignInsideTransaction(command)
            },
        )
    }

    override fun revokeTenantUserRole(
        command: RevokeTenantUserRoleCommand,
    ): TenantUserRoleAssignmentReceipt {
        return requireNotNull(
            transactionTemplate.execute {
                revokeInsideTransaction(command)
            },
        )
    }

    private fun assignInsideTransaction(
        command: AssignTenantUserRoleCommand,
    ): TenantUserRoleAssignmentReceipt {
        val actorUserId = requireTenantActor(command.tenantId)
        require(actorUserId != command.userId) {
            "Tenant user cannot assign own tenant roles"
        }
        databaseSessionContext.bind(requestContextHolder.current().identity)

        val reservation = idempotencyPort.reserve(
            IdempotencyCommand(
                operationType = "tenant.user.role.assign",
                requestPayload = mapOf(
                    "tenantId" to command.tenantId,
                    "userId" to command.userId,
                    "tenantRoleId" to command.tenantRoleId,
                ),
                resourceType = "user_tenant_roles",
            ),
        )

        return when (reservation) {
            is IdempotencyReservation.Started -> applyAssignment(
                command = command,
                actorUserId = actorUserId,
                idempotencyKeyId = reservation.recordId,
            )

            is IdempotencyReservation.Replay -> replayAssignment(reservation)
            is IdempotencyReservation.InProgress -> throw TenantUserRoleManagementInProgressException(
                "Tenant user role assignment is already being processed for this idempotency key",
            )

            is IdempotencyReservation.Conflict -> throw TenantUserRoleManagementConflictException(
                "Idempotency key was already used for a different tenant user role assignment request",
            )
        }
    }

    private fun revokeInsideTransaction(
        command: RevokeTenantUserRoleCommand,
    ): TenantUserRoleAssignmentReceipt {
        val actorUserId = requireTenantActor(command.tenantId)
        require(actorUserId != command.userId) {
            "Tenant user cannot revoke own tenant roles"
        }
        databaseSessionContext.bind(requestContextHolder.current().identity)

        val reservation = idempotencyPort.reserve(
            IdempotencyCommand(
                operationType = "tenant.user.role.revoke",
                requestPayload = mapOf(
                    "tenantId" to command.tenantId,
                    "userId" to command.userId,
                    "tenantRoleId" to command.tenantRoleId,
                ),
                resourceType = "user_tenant_roles",
            ),
        )

        return when (reservation) {
            is IdempotencyReservation.Started -> applyRevocation(
                command = command,
                idempotencyKeyId = reservation.recordId,
            )

            is IdempotencyReservation.Replay -> replayAssignment(reservation)
            is IdempotencyReservation.InProgress -> throw TenantUserRoleManagementInProgressException(
                "Tenant user role revocation is already being processed for this idempotency key",
            )

            is IdempotencyReservation.Conflict -> throw TenantUserRoleManagementConflictException(
                "Idempotency key was already used for a different tenant user role revocation request",
            )
        }
    }

    private fun applyAssignment(
        command: AssignTenantUserRoleCommand,
        actorUserId: UUID,
        idempotencyKeyId: UUID,
    ): TenantUserRoleAssignmentReceipt {
        requireActiveTenantUser(command.tenantId, command.userId)
        requireActiveTenantRole(command.tenantId, command.tenantRoleId)

        val inserted = jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (
                user_id,
                tenant_id,
                tenant_role_id,
                assigned_by
            )
            VALUES (?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT user_tenant_roles_pkey DO NOTHING
            """.trimIndent(),
            command.userId,
            command.tenantId,
            command.tenantRoleId,
            actorUserId,
        ) == 1

        val receipt = TenantUserRoleAssignmentReceipt(
            tenantId = command.tenantId,
            userId = command.userId,
            tenantRoleId = command.tenantRoleId,
            assigned = true,
            changed = inserted,
            replayed = false,
        )

        if (inserted) {
            recordSideEffects(
                command = RoleChangeCommand(
                    tenantId = command.tenantId,
                    userId = command.userId,
                    tenantRoleId = command.tenantRoleId,
                    action = "assign",
                    eventType = "tenant.user.role.assigned",
                ),
                idempotencyKeyId = idempotencyKeyId,
            )
        }

        idempotencyPort.markSucceeded(
            recordId = idempotencyKeyId,
            responseCode = 200,
            responseBody = receipt,
            resourceId = command.userId,
        )

        return receipt
    }

    private fun applyRevocation(
        command: RevokeTenantUserRoleCommand,
        idempotencyKeyId: UUID,
    ): TenantUserRoleAssignmentReceipt {
        requireTenantUser(command.tenantId, command.userId)
        requireTenantRole(command.tenantId, command.tenantRoleId)

        val deleted = jdbcTemplate.update(
            """
            DELETE FROM user_tenant_roles
            WHERE tenant_id = ?
              AND user_id = ?
              AND tenant_role_id = ?
            """.trimIndent(),
            command.tenantId,
            command.userId,
            command.tenantRoleId,
        ) == 1

        val receipt = TenantUserRoleAssignmentReceipt(
            tenantId = command.tenantId,
            userId = command.userId,
            tenantRoleId = command.tenantRoleId,
            assigned = false,
            changed = deleted,
            replayed = false,
        )

        if (deleted) {
            recordSideEffects(
                command = RoleChangeCommand(
                    tenantId = command.tenantId,
                    userId = command.userId,
                    tenantRoleId = command.tenantRoleId,
                    action = "revoke",
                    eventType = "tenant.user.role.revoked",
                ),
                idempotencyKeyId = idempotencyKeyId,
            )
        }

        idempotencyPort.markSucceeded(
            recordId = idempotencyKeyId,
            responseCode = 200,
            responseBody = receipt,
            resourceId = command.userId,
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

    private fun requireActiveTenantUser(tenantId: UUID, userId: UUID) {
        val exists = jdbcTemplate.queryForList(
            """
            SELECT id
            FROM users
            WHERE tenant_id = ?
              AND id = ?
              AND deleted_at IS NULL
              AND status = 'active'
              AND is_active = true
              AND (locked_until IS NULL OR locked_until <= now())
            FOR UPDATE
            """.trimIndent(),
            UUID::class.java,
            tenantId,
            userId,
        ).isNotEmpty()

        if (!exists) {
            throw TenantUserRoleManagementNotFoundException("Active tenant user was not found")
        }
    }

    private fun requireTenantUser(tenantId: UUID, userId: UUID) {
        val exists = jdbcTemplate.queryForList(
            """
            SELECT id
            FROM users
            WHERE tenant_id = ?
              AND id = ?
              AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            UUID::class.java,
            tenantId,
            userId,
        ).isNotEmpty()

        if (!exists) {
            throw TenantUserRoleManagementNotFoundException("Tenant user was not found")
        }
    }

    private fun requireActiveTenantRole(tenantId: UUID, tenantRoleId: UUID) {
        val role = findTenantRole(tenantId, tenantRoleId, activeOnly = true)
        if (role == null) {
            throw TenantUserRoleManagementNotFoundException("Active tenant role was not found")
        }
        require(!role.isSystem) {
            "System tenant roles cannot be assigned or revoked through tenant role management"
        }
    }

    private fun requireTenantRole(tenantId: UUID, tenantRoleId: UUID) {
        val role = findTenantRole(tenantId, tenantRoleId, activeOnly = false)
        if (role == null) {
            throw TenantUserRoleManagementNotFoundException("Tenant role was not found")
        }
        require(!role.isSystem) {
            "System tenant roles cannot be assigned or revoked through tenant role management"
        }
    }

    private fun findTenantRole(
        tenantId: UUID,
        tenantRoleId: UUID,
        activeOnly: Boolean,
    ): TenantRolePolicy? {
        val activeClause = if (activeOnly) "AND is_active = true" else ""
        return jdbcTemplate.query(
            """
            SELECT is_system
            FROM tenant_roles
            WHERE tenant_id = ?
              AND id = ?
              $activeClause
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                TenantRolePolicy(
                    isSystem = rs.getBoolean("is_system"),
                )
            },
            tenantId,
            tenantRoleId,
        ).singleOrNull()
    }

    private fun recordSideEffects(
        command: RoleChangeCommand,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = command.tenantId,
                action = "tenant.users.role.${command.action}",
                resource = AuditResource("user_tenant_roles", command.userId),
                after = mapOf(
                    "tenantId" to command.tenantId,
                    "userId" to command.userId,
                    "tenantRoleId" to command.tenantRoleId,
                    "action" to command.action,
                ),
            ),
        )

        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "user_tenant_roles",
                aggregateId = command.userId,
                tenantId = command.tenantId,
                eventType = command.eventType,
                destination = OutboxDestination.PLATFORM,
                payload = mapOf(
                    "tenantId" to command.tenantId,
                    "userId" to command.userId,
                    "tenantRoleId" to command.tenantRoleId,
                ),
                idempotencyKeyId = idempotencyKeyId,
                priority = 3,
            ),
        )
    }

    private fun replayAssignment(
        reservation: IdempotencyReservation.Replay,
    ): TenantUserRoleAssignmentReceipt {
        if (reservation.responseBody.isNullOrBlank()) {
            throw TenantUserRoleManagementConflictException(
                "Tenant user role replay does not contain a stored response body",
            )
        }

        return objectMapper.readValue(
            reservation.responseBody,
            TenantUserRoleAssignmentReceipt::class.java,
        ).copy(replayed = true)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapTenantRole(rs: ResultSet, rowNumber: Int): TenantRoleSummary {
        return TenantRoleSummary(
            tenantRoleId = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            code = rs.getString("code"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            isSystem = rs.getBoolean("is_system"),
            isActive = rs.getBoolean("is_active"),
            permissionCodes = rs.getArray("permission_codes").toStringList(),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapTenantPermission(rs: ResultSet, rowNumber: Int): TenantPermissionSummary {
        return TenantPermissionSummary(
            permissionId = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            code = rs.getString("code"),
            description = rs.getString("description"),
        )
    }

    private fun Array.toStringList(): List<String> {
        return when (val value = array) {
            is kotlin.Array<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }
    }

    private data class RoleChangeCommand(
        val tenantId: UUID,
        val userId: UUID,
        val tenantRoleId: UUID,
        val action: String,
        val eventType: String,
    )

    private data class TenantRolePolicy(
        val isSystem: Boolean,
    )
}
