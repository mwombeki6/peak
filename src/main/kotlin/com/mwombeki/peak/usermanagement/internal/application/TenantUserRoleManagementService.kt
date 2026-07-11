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
import com.mwombeki.peak.usermanagement.api.CreateTenantRoleCommand
import com.mwombeki.peak.usermanagement.api.DeactivateTenantRoleCommand
import com.mwombeki.peak.usermanagement.api.GetTenantRoleQuery
import com.mwombeki.peak.usermanagement.api.ListTenantPermissionsQuery
import com.mwombeki.peak.usermanagement.api.ListTenantRolesQuery
import com.mwombeki.peak.usermanagement.api.RevokeTenantUserRoleCommand
import com.mwombeki.peak.usermanagement.api.TenantPermissionSummary
import com.mwombeki.peak.usermanagement.api.TenantRoleMutationReceipt
import com.mwombeki.peak.usermanagement.api.TenantRoleSummary
import com.mwombeki.peak.usermanagement.api.TenantUserRoleAssignmentReceipt
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementConflictException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementInProgressException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementNotFoundException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementPort
import com.mwombeki.peak.usermanagement.api.UpdateTenantRoleCommand
import java.sql.Array
import java.sql.ResultSet
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
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

    override fun getTenantRole(query: GetTenantRoleQuery): TenantRoleSummary? {
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
                      AND tr.id = ?
                    GROUP BY tr.id, tr.tenant_id, tr.code, tr.name, tr.description,
                             tr.is_system, tr.is_active
                    """.trimIndent(),
                    ::mapTenantRole,
                    query.tenantId,
                    query.tenantRoleId,
                ).singleOrNull()
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

    override fun createTenantRole(command: CreateTenantRoleCommand): TenantRoleMutationReceipt {
        return mutateTenantRole(
            tenantId = command.tenantId,
            operationType = "tenant.role.create",
            requestPayload = command,
        ) { idempotencyKeyId ->
            val roleId = UUID.randomUUID()
            requireDelegableTenantPermissions(command.tenantId, command.permissionCodes)
            val permissionIds = requireTenantPermissions(command.tenantId, command.permissionCodes)
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO tenant_roles (
                        id,
                        tenant_id,
                        code,
                        name,
                        description,
                        is_system,
                        is_active
                    )
                    VALUES (?, ?, ?, ?, ?, false, true)
                    """.trimIndent(),
                    roleId,
                    command.tenantId,
                    command.code.normalizedCode(),
                    command.name.normalizedRequired("name"),
                    command.description?.trim()?.takeIf { it.isNotEmpty() },
                )
            } catch (ex: DuplicateKeyException) {
                throw TenantUserRoleManagementConflictException("Tenant role code is already in use")
            }
            replaceTenantRolePermissions(roleId, permissionIds)

            TenantRoleMutationReceipt(
                tenantId = command.tenantId,
                tenantRoleId = roleId,
                isActive = true,
                changed = true,
                replayed = false,
            ).also { receipt ->
                recordRoleDefinitionSideEffects(
                    tenantId = command.tenantId,
                    tenantRoleId = roleId,
                    action = "created",
                    eventType = "tenant.role.created",
                    payload = mapOf(
                        "tenantId" to command.tenantId,
                        "tenantRoleId" to roleId,
                        "code" to command.code.normalizedCode(),
                        "permissionCodes" to command.permissionCodes.map { it.normalizedCode() },
                    ),
                    idempotencyKeyId = idempotencyKeyId,
                )
            }
        }
    }

    override fun updateTenantRole(command: UpdateTenantRoleCommand): TenantRoleMutationReceipt {
        return mutateTenantRole(
            tenantId = command.tenantId,
            operationType = "tenant.role.update",
            requestPayload = command,
        ) { idempotencyKeyId ->
            requireMutableTenantRole(command.tenantId, command.tenantRoleId)
            requireDelegableTenantRole(command.tenantId, command.tenantRoleId)
            val permissionIds = command.permissionCodes
                ?.also { requireDelegableTenantPermissions(command.tenantId, it) }
                ?.let { requireTenantPermissions(command.tenantId, it) }
            val rows = jdbcTemplate.update(
                """
                UPDATE tenant_roles
                SET name = COALESCE(?, name),
                    description = COALESCE(?, description),
                    updated_at = now()
                WHERE tenant_id = ?
                  AND id = ?
                  AND is_system = false
                """.trimIndent(),
                command.name?.normalizedRequired("name"),
                command.description?.trim()?.takeIf { it.isNotEmpty() },
                command.tenantId,
                command.tenantRoleId,
            )
            if (permissionIds != null) {
                replaceTenantRolePermissions(command.tenantRoleId, permissionIds)
            }

            TenantRoleMutationReceipt(
                tenantId = command.tenantId,
                tenantRoleId = command.tenantRoleId,
                isActive = tenantRoleIsActive(command.tenantId, command.tenantRoleId),
                changed = rows == 1 || permissionIds != null,
                replayed = false,
            ).also { receipt ->
                if (receipt.changed) {
                    recordRoleDefinitionSideEffects(
                        tenantId = command.tenantId,
                        tenantRoleId = command.tenantRoleId,
                        action = "updated",
                        eventType = "tenant.role.updated",
                        payload = mapOf(
                            "tenantId" to command.tenantId,
                            "tenantRoleId" to command.tenantRoleId,
                            "permissionsChanged" to (permissionIds != null),
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
            }
        }
    }

    override fun deactivateTenantRole(
        command: DeactivateTenantRoleCommand,
    ): TenantRoleMutationReceipt {
        return mutateTenantRole(
            tenantId = command.tenantId,
            operationType = "tenant.role.deactivate",
            requestPayload = command,
        ) { idempotencyKeyId ->
            requireMutableTenantRole(command.tenantId, command.tenantRoleId)
            requireDelegableTenantRole(command.tenantId, command.tenantRoleId)
            val rows = jdbcTemplate.update(
                """
                UPDATE tenant_roles
                SET is_active = false,
                    updated_at = now()
                WHERE tenant_id = ?
                  AND id = ?
                  AND is_system = false
                  AND is_active = true
                """.trimIndent(),
                command.tenantId,
                command.tenantRoleId,
            )
            if (rows == 1) {
                jdbcTemplate.update(
                    """
                    DELETE FROM user_tenant_roles
                    WHERE tenant_id = ?
                      AND tenant_role_id = ?
                    """.trimIndent(),
                    command.tenantId,
                    command.tenantRoleId,
                )
            }

            TenantRoleMutationReceipt(
                tenantId = command.tenantId,
                tenantRoleId = command.tenantRoleId,
                isActive = false,
                changed = rows == 1,
                replayed = false,
            ).also { receipt ->
                if (receipt.changed) {
                    recordRoleDefinitionSideEffects(
                        tenantId = command.tenantId,
                        tenantRoleId = command.tenantRoleId,
                        action = "deactivated",
                        eventType = "tenant.role.deactivated",
                        payload = mapOf(
                            "tenantId" to command.tenantId,
                            "tenantRoleId" to command.tenantRoleId,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
            }
        }
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
                actorUserId = actorUserId,
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
        requireDelegableTenantRole(command.tenantId, command.tenantRoleId)

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
        actorUserId: UUID,
        idempotencyKeyId: UUID,
    ): TenantUserRoleAssignmentReceipt {
        requireTenantUser(command.tenantId, command.userId)
        requireTenantRole(command.tenantId, command.tenantRoleId)
        requireDelegableTenantRole(command.tenantId, command.tenantRoleId, actorUserId)

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
            ?: throw TenantUserRoleManagementNotFoundException("Active tenant role was not found")
        require(!role.isSystem) {
            "System tenant roles cannot be assigned or revoked through tenant role management"
        }
    }

    private fun requireTenantRole(tenantId: UUID, tenantRoleId: UUID) {
        val role = findTenantRole(tenantId, tenantRoleId, activeOnly = false)
            ?: throw TenantUserRoleManagementNotFoundException("Tenant role was not found")
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

    private fun recordRoleDefinitionSideEffects(
        tenantId: UUID,
        tenantRoleId: UUID,
        action: String,
        eventType: String,
        payload: Map<String, Any?>,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = "tenant.roles.$action",
                resource = AuditResource("tenant_roles", tenantRoleId),
                after = payload,
            ),
        )

        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "tenant_roles",
                aggregateId = tenantRoleId,
                tenantId = tenantId,
                eventType = eventType,
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                idempotencyKeyId = idempotencyKeyId,
                priority = 3,
            ),
        )
    }

    private fun mutateTenantRole(
        tenantId: UUID,
        operationType: String,
        requestPayload: Any,
        block: (UUID) -> TenantRoleMutationReceipt,
    ): TenantRoleMutationReceipt {
        return requireNotNull(
            transactionTemplate.execute {
                requireTenantActor(tenantId)
                databaseSessionContext.bind(requestContextHolder.current().identity)
                val reservation = idempotencyPort.reserve(
                    IdempotencyCommand(
                        operationType = operationType,
                        requestPayload = requestPayload,
                        resourceType = "tenant_roles",
                    ),
                )

                when (reservation) {
                    is IdempotencyReservation.Started -> {
                        val receipt = block(reservation.recordId)
                        idempotencyPort.markSucceeded(
                            recordId = reservation.recordId,
                            responseCode = 200,
                            responseBody = receipt,
                            resourceId = receipt.tenantRoleId,
                        )
                        receipt
                    }

                    is IdempotencyReservation.Replay -> replayTenantRoleMutation(reservation)
                    is IdempotencyReservation.InProgress -> {
                        throw TenantUserRoleManagementInProgressException(
                            "Tenant role command is already being processed for this idempotency key",
                        )
                    }

                    is IdempotencyReservation.Conflict -> {
                        throw TenantUserRoleManagementConflictException(
                            "Idempotency key was already used for a different tenant role request",
                        )
                    }
                }
            },
        )
    }

    private fun requireTenantPermissions(
        tenantId: UUID,
        permissionCodes: List<String>,
    ): List<UUID> {
        val normalizedCodes = permissionCodes.map { it.normalizedCode() }.distinct()
        require(normalizedCodes.isNotEmpty()) {
            "At least one tenant permission is required"
        }
        val placeholders = normalizedCodes.joinToString(", ") { "?" }
        val args = mutableListOf<Any>(tenantId)
        args.addAll(normalizedCodes)
        val permissionIds = jdbcTemplate.query(
            """
            SELECT id
            FROM permissions
            WHERE tenant_id = ?
              AND code IN ($placeholders)
            ORDER BY code
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            *args.toTypedArray(),
        )
        if (permissionIds.size != normalizedCodes.size) {
            throw TenantUserRoleManagementNotFoundException(
                "One or more tenant permissions were not found",
            )
        }
        return permissionIds
    }

    private fun replaceTenantRolePermissions(
        tenantRoleId: UUID,
        permissionIds: List<UUID>,
    ) {
        jdbcTemplate.update(
            "DELETE FROM tenant_role_permissions WHERE tenant_role_id = ?",
            tenantRoleId,
        )
        permissionIds.forEach { permissionId ->
            jdbcTemplate.update(
                """
                INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
                VALUES (?, ?)
                ON CONFLICT ON CONSTRAINT tenant_role_permissions_pkey DO NOTHING
                """.trimIndent(),
                tenantRoleId,
                permissionId,
            )
        }
    }

    private fun requireMutableTenantRole(tenantId: UUID, tenantRoleId: UUID) {
        val role = findTenantRole(tenantId, tenantRoleId, activeOnly = false)
            ?: throw TenantUserRoleManagementNotFoundException("Tenant role was not found")
        require(!role.isSystem) {
            "System tenant roles cannot be modified"
        }
        val actorUserId = requireTenantActor(tenantId)
        val assignedToActor = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM user_tenant_roles
                WHERE tenant_id = ?
                  AND user_id = ?
                  AND tenant_role_id = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            actorUserId,
            tenantRoleId,
        ) == true
        require(!assignedToActor) {
            "Tenant user cannot modify a role assigned to self"
        }
    }

    private fun requireDelegableTenantRole(
        tenantId: UUID,
        tenantRoleId: UUID,
        actorUserId: UUID = requireTenantActor(tenantId),
    ) {
        val permissionCodes = jdbcTemplate.queryForList(
            """
            SELECT p.code
            FROM tenant_role_permissions trp
            JOIN permissions p
              ON p.id = trp.permission_id
             AND p.tenant_id = ?
            WHERE trp.tenant_role_id = ?
            ORDER BY p.code
            """.trimIndent(),
            String::class.java,
            tenantId,
            tenantRoleId,
        ).filterNotNull()
        requireDelegableTenantPermissions(tenantId, permissionCodes, actorUserId)
    }

    private fun requireDelegableTenantPermissions(
        tenantId: UUID,
        permissionCodes: List<String>,
        actorUserId: UUID = requireTenantActor(tenantId),
    ) {
        val unauthorized = permissionCodes
            .map { it.normalizedCode() }
            .distinct()
            .filterNot { permissionCode ->
                jdbcTemplate.queryForObject(
                    "SELECT user_has_tenant_permission(?, ?, ?)",
                    Boolean::class.java,
                    actorUserId,
                    tenantId,
                    permissionCode,
                ) == true
            }
        require(unauthorized.isEmpty()) {
            "Tenant roles cannot include permissions the actor does not hold"
        }
    }

    private fun tenantRoleIsActive(tenantId: UUID, tenantRoleId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT is_active
            FROM tenant_roles
            WHERE tenant_id = ?
              AND id = ?
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            tenantRoleId,
        ) == true
    }

    private fun replayTenantRoleMutation(
        reservation: IdempotencyReservation.Replay,
    ): TenantRoleMutationReceipt {
        if (reservation.responseBody.isNullOrBlank()) {
            throw TenantUserRoleManagementConflictException(
                "Tenant role replay does not contain a stored response body",
            )
        }

        return objectMapper.readValue(
            reservation.responseBody,
            TenantRoleMutationReceipt::class.java,
        ).copy(replayed = true)
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

    private fun String.normalizedRequired(field: String): String {
        return trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("$field is required")
    }

    private fun String.normalizedCode(): String {
        return normalizedRequired("code").lowercase()
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
