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
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.AssignTenantAdministratorCommand
import com.mwombeki.peak.usermanagement.api.AssignTenantUserRoleCommand
import com.mwombeki.peak.usermanagement.api.CreateTenantRoleCommand
import com.mwombeki.peak.usermanagement.api.DeactivateTenantRoleCommand
import com.mwombeki.peak.usermanagement.api.GetTenantRoleQuery
import com.mwombeki.peak.usermanagement.api.ListTenantAdministratorsQuery
import com.mwombeki.peak.usermanagement.api.ListTenantPermissionsQuery
import com.mwombeki.peak.usermanagement.api.ListTenantRolesQuery
import com.mwombeki.peak.usermanagement.api.RevokeTenantAdministratorCommand
import com.mwombeki.peak.usermanagement.api.RevokeTenantUserRoleCommand
import com.mwombeki.peak.usermanagement.api.TenantAdministratorSummary
import com.mwombeki.peak.usermanagement.api.TenantPermissionSummary
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessPort
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessRequest
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
    private val tenantPermissionAccessPort: TenantPermissionAccessPort,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) : TenantUserRoleManagementPort {
    override fun listTenantRoles(query: ListTenantRolesQuery): List<TenantRoleSummary> {
        return requireNotNull(
            transactionTemplate.execute {
                bindTenantActorWithPermission(query.tenantId, TENANT_ROLE_VIEW_PERMISSION)
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
                bindTenantActorWithPermission(query.tenantId, TENANT_ROLE_VIEW_PERMISSION)
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
                bindTenantActorWithPermission(query.tenantId, TENANT_ROLE_VIEW_PERMISSION)
                jdbcTemplate.query(
                    """
                    SELECT p.id, p.tenant_id, p.code, p.description
                    FROM permissions p
                    JOIN permission_catalog pc
                      ON pc.code = p.code
                    WHERE p.tenant_id = ?
                      AND pc.is_tenant_permission = true
                      AND pc.access_scope IN ('tenant', 'both')
                    ORDER BY p.code
                    """.trimIndent(),
                    ::mapTenantPermission,
                    query.tenantId,
                )
            },
        )
    }

    override fun listTenantAdministrators(
        query: ListTenantAdministratorsQuery,
    ): List<TenantAdministratorSummary> {
        return requireNotNull(
            transactionTemplate.execute {
                bindTenantActorWithPermission(
                    tenantId = query.tenantId,
                    permissionCode = TENANT_ROLE_VIEW_PERMISSION,
                    denialMessage = "Tenant user lacks tenant role view permission",
                )
                jdbcTemplate.query(
                    """
                    SELECT
                        u.tenant_id,
                        tr.id AS tenant_role_id,
                        u.id AS user_id,
                        COALESCE(u.full_name, '') AS full_name,
                        u.email,
                        COALESCE(u.status, '') AS status,
                        u.is_active,
                        u.locked_until,
                        EXISTS (
                            SELECT 1
                            FROM identity_links il
                            WHERE il.tenant_id = u.tenant_id
                              AND il.user_id = u.id
                              AND il.identity_mode = 'tenant'
                              AND il.revoked_at IS NULL
                        ) AS has_active_identity
                    FROM user_tenant_roles utr
                    JOIN tenant_roles tr
                      ON tr.id = utr.tenant_role_id
                     AND tr.tenant_id = utr.tenant_id
                    JOIN users u
                      ON u.id = utr.user_id
                     AND u.tenant_id = utr.tenant_id
                    WHERE utr.tenant_id = ?
                      AND tr.code = ?
                      AND tr.is_system = true
                    ORDER BY u.full_name, u.email, u.id
                    """.trimIndent(),
                    { rs, _ ->
                        TenantAdministratorSummary(
                            tenantId = rs.getObject("tenant_id", UUID::class.java),
                            tenantRoleId = rs.getObject("tenant_role_id", UUID::class.java),
                            userId = rs.getObject("user_id", UUID::class.java),
                            fullName = rs.getString("full_name"),
                            email = rs.getString("email"),
                            status = rs.getString("status"),
                            isActive = rs.getBoolean("is_active"),
                            lockedUntil = rs.getTimestamp("locked_until")?.toInstant(),
                            hasActiveIdentity = rs.getBoolean("has_active_identity"),
                        )
                    },
                    query.tenantId,
                    TENANT_ADMIN_ROLE_CODE,
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
            val roleCode = command.code.normalizedTenantRoleCode()
            val roleName = command.name.normalizedTenantRoleName()
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
                    roleCode,
                    roleName,
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
            ).also {
                recordRoleDefinitionSideEffects(
                    tenantId = command.tenantId,
                    tenantRoleId = roleId,
                    action = "created",
                    eventType = "tenant.role.created",
                    payload = mapOf(
                        "tenantId" to command.tenantId,
                        "tenantRoleId" to roleId,
                        "code" to roleCode,
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
            val roleName = command.name?.normalizedTenantRoleName()
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
                roleName,
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

    override fun assignTenantAdministrator(
        command: AssignTenantAdministratorCommand,
    ): TenantUserRoleAssignmentReceipt {
        return mutateTenantAdministratorAssignment(
            tenantId = command.tenantId,
            userId = command.userId,
            operationType = "tenant.administrator.assign",
            requestPayload = command,
        ) { actorUserId, idempotencyKeyId ->
            require(
                actorUserId != command.userId ||
                    actorHasTenantAdminPermission(command.tenantId, actorUserId),
            ) {
                "Only a tenant super administrator can assign own tenant administrator access"
            }
            requireActiveTenantUser(command.tenantId, command.userId)
            val tenantRoleId = requireSystemTenantAdministratorRole(command.tenantId)
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
                tenantRoleId,
                actorUserId,
            ) == 1

            TenantUserRoleAssignmentReceipt(
                tenantId = command.tenantId,
                userId = command.userId,
                tenantRoleId = tenantRoleId,
                assigned = true,
                changed = inserted,
                replayed = false,
            ).also { receipt ->
                if (inserted) {
                    recordTenantAdministratorSideEffects(
                        receipt = receipt,
                        action = "assigned",
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
            }
        }
    }

    override fun revokeTenantAdministrator(
        command: RevokeTenantAdministratorCommand,
    ): TenantUserRoleAssignmentReceipt {
        return mutateTenantAdministratorAssignment(
            tenantId = command.tenantId,
            userId = command.userId,
            operationType = "tenant.administrator.revoke",
            requestPayload = command,
        ) { actorUserId, idempotencyKeyId ->
            require(actorUserId != command.userId) {
                "Tenant user cannot revoke own tenant administrator access"
            }
            requireTenantUser(command.tenantId, command.userId)
            val tenantRoleId = requireSystemTenantAdministratorRole(command.tenantId)
            val assigned = tenantAdministratorAssignmentExists(
                tenantId = command.tenantId,
                userId = command.userId,
                tenantRoleId = tenantRoleId,
            )
            if (assigned) {
                requireAnotherActiveTenantAdministrator(
                    tenantId = command.tenantId,
                    excludedUserId = command.userId,
                    tenantRoleId = tenantRoleId,
                )
            }
            val deleted = assigned && jdbcTemplate.update(
                """
                DELETE FROM user_tenant_roles
                WHERE tenant_id = ?
                  AND user_id = ?
                  AND tenant_role_id = ?
                """.trimIndent(),
                command.tenantId,
                command.userId,
                tenantRoleId,
            ) == 1

            TenantUserRoleAssignmentReceipt(
                tenantId = command.tenantId,
                userId = command.userId,
                tenantRoleId = tenantRoleId,
                assigned = false,
                changed = deleted,
                replayed = false,
            ).also { receipt ->
                if (deleted) {
                    recordTenantAdministratorSideEffects(
                        receipt = receipt,
                        action = "revoked",
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
            }
        }
    }

    private fun mutateTenantAdministratorAssignment(
        tenantId: UUID,
        userId: UUID,
        operationType: String,
        requestPayload: Any,
        block: (UUID, UUID) -> TenantUserRoleAssignmentReceipt,
    ): TenantUserRoleAssignmentReceipt {
        return requireNotNull(
            transactionTemplate.execute {
                val actorUserId = bindTenantActorWithPermission(
                    tenantId = tenantId,
                    permissionCode = TENANT_ADMINISTRATOR_MANAGE_PERMISSION,
                    denialMessage = "Tenant user lacks tenant administrator management permission",
                )
                lockTenantForAdministratorContinuity(tenantId)
                val reservation = idempotencyPort.reserve(
                    IdempotencyCommand(
                        operationType = operationType,
                        requestPayload = requestPayload,
                        resourceType = "user_tenant_roles",
                    ),
                )
                when (reservation) {
                    is IdempotencyReservation.Started -> {
                        val receipt = block(actorUserId, reservation.recordId)
                        idempotencyPort.markSucceeded(
                            recordId = reservation.recordId,
                            responseCode = 200,
                            responseBody = receipt,
                            resourceId = userId,
                        )
                        receipt
                    }

                    is IdempotencyReservation.Replay -> replayAssignment(reservation)
                    is IdempotencyReservation.InProgress -> throw TenantUserRoleManagementInProgressException(
                        "Tenant administrator command is already being processed for this idempotency key",
                    )

                    is IdempotencyReservation.Conflict -> throw TenantUserRoleManagementConflictException(
                        "Idempotency key was already used for a different tenant administrator request",
                    )
                }
            },
        )
    }

    private fun assignInsideTransaction(
        command: AssignTenantUserRoleCommand,
    ): TenantUserRoleAssignmentReceipt {
        val actorUserId = bindTenantActorWithPermission(
            command.tenantId,
            TENANT_USER_MANAGE_PERMISSION,
        )
        require(actorUserId != command.userId) {
            "Tenant user cannot assign own tenant roles"
        }

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
        val actorUserId = bindTenantActorWithPermission(
            command.tenantId,
            TENANT_USER_MANAGE_PERMISSION,
        )
        require(actorUserId != command.userId) {
            "Tenant user cannot revoke own tenant roles"
        }

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
        requireActorCanManageTargetUser(command.tenantId, actorUserId, command.userId)

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
        requireActorCanManageTargetUser(command.tenantId, actorUserId, command.userId)

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

    private fun bindTenantActorWithPermission(
        tenantId: UUID,
        permissionCode: String,
        denialMessage: String = "Tenant user lacks required permission",
    ): UUID {
        return tenantPermissionAccessPort.requireAuthorized(
            TenantPermissionAccessRequest(tenantId, permissionCode, denialMessage),
        )
    }

    private fun lockTenantForAdministratorContinuity(tenantId: UUID) {
        val locked = jdbcTemplate.queryForObject(
            "SELECT public.lock_tenant_administrator_continuity(?)",
            Boolean::class.java,
            tenantId,
        ) == true
        if (!locked) {
            throw TenantUserRoleManagementNotFoundException("Tenant was not found")
        }
    }

    private fun actorHasTenantAdminPermission(tenantId: UUID, actorUserId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            "SELECT user_has_tenant_permission(?, ?, ?)",
            Boolean::class.java,
            actorUserId,
            tenantId,
            TENANT_ADMIN_ALL_PERMISSION,
        ) == true
    }

    private fun requireSystemTenantAdministratorRole(tenantId: UUID): UUID {
        return jdbcTemplate.query(
            """
            SELECT id
            FROM tenant_roles
            WHERE tenant_id = ?
              AND code = ?
              AND is_system = true
              AND is_active = true
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            tenantId,
            TENANT_ADMIN_ROLE_CODE,
        ).singleOrNull()
            ?: throw TenantUserRoleManagementNotFoundException(
                "Active system Tenant Administrator role was not found",
            )
    }

    private fun tenantAdministratorAssignmentExists(
        tenantId: UUID,
        userId: UUID,
        tenantRoleId: UUID,
    ): Boolean {
        return jdbcTemplate.queryForObject(
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
            userId,
            tenantRoleId,
        ) == true
    }

    private fun requireAnotherActiveTenantAdministrator(
        tenantId: UUID,
        excludedUserId: UUID,
        tenantRoleId: UUID,
    ) {
        val exists = jdbcTemplate.queryForObject(
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
            excludedUserId,
        ) == true
        require(exists) {
            "Tenant administrator access cannot be removed without another active administrator"
        }
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

    private fun recordTenantAdministratorSideEffects(
        receipt: TenantUserRoleAssignmentReceipt,
        action: String,
        idempotencyKeyId: UUID,
    ) {
        val payload = mapOf(
            "tenantId" to receipt.tenantId,
            "userId" to receipt.userId,
            "tenantRoleId" to receipt.tenantRoleId,
            "action" to action,
        )
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = receipt.tenantId,
                action = "tenant.administrator.$action",
                resource = AuditResource("user_tenant_roles", receipt.userId),
                after = payload,
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "user_tenant_roles",
                aggregateId = receipt.userId,
                tenantId = receipt.tenantId,
                eventType = "tenant.administrator.$action",
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                idempotencyKeyId = idempotencyKeyId,
                priority = 4,
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
                bindTenantActorWithPermission(tenantId, TENANT_USER_MANAGE_PERMISSION)
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
        val validCatalogCodes = jdbcTemplate.queryForList(
            """
            SELECT code
            FROM permission_catalog
            WHERE code IN ($placeholders)
              AND is_tenant_permission = true
              AND access_scope IN ('tenant', 'both')
            """.trimIndent(),
            String::class.java,
            *normalizedCodes.toTypedArray(),
        ).filterNotNull().toSet()
        require(validCatalogCodes.size == normalizedCodes.size) {
            "Dynamic tenant roles may contain only tenant-scoped permissions"
        }
        val args = mutableListOf<Any>(tenantId)
        args.addAll(normalizedCodes)
        val permissionIds = jdbcTemplate.query(
            """
            SELECT p.id
            FROM permissions p
            JOIN permission_catalog pc
              ON pc.code = p.code
            WHERE p.tenant_id = ?
              AND p.code IN ($placeholders)
              AND pc.is_tenant_permission = true
              AND pc.access_scope IN ('tenant', 'both')
            ORDER BY p.code
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
        val normalizedCodes = permissionCodes.map { it.normalizedCode() }.distinct()
        require(TENANT_ADMIN_ALL_PERMISSION !in normalizedCodes) {
            "tenant.admin.all is reserved for the system Tenant Administrator role"
        }
        val unauthorized = normalizedCodes
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

    private fun String.normalizedTenantRoleCode(): String {
        return normalizedCode().also { roleCode ->
            require(roleCode != TENANT_ADMIN_ROLE_CODE) {
                "tenant_admin is reserved for the system Tenant Administrator role"
            }
        }
    }

    private fun String.normalizedTenantRoleName(): String {
        return normalizedRequired("name").also { roleName ->
            require(!roleName.equals(TENANT_ADMIN_ROLE_NAME, ignoreCase = true)) {
                "Tenant Administrator is reserved for the system tenant role"
            }
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

    private data class PropertyPermissionGrant(
        val propertyId: UUID,
        val permissionCode: String,
    )

    private companion object {
        const val TENANT_ADMIN_ALL_PERMISSION = "tenant.admin.all"
        private const val TENANT_ADMINISTRATOR_MANAGE_PERMISSION = "tenant.administrators.manage"
        private const val TENANT_ROLE_VIEW_PERMISSION = "tenant.roles.view"
        private const val TENANT_USER_MANAGE_PERMISSION = "tenant.users.manage"
        private const val TENANT_ADMIN_ROLE_CODE = "tenant_admin"
        private const val TENANT_ADMIN_ROLE_NAME = "Tenant Administrator"
    }
}
