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
import com.mwombeki.peak.usermanagement.api.AssignPropertyUserRoleCommand
import com.mwombeki.peak.usermanagement.api.CreatePropertyRoleCommand
import com.mwombeki.peak.usermanagement.api.DeactivatePropertyRoleCommand
import com.mwombeki.peak.usermanagement.api.EnsurePropertyAdministratorCommand
import com.mwombeki.peak.usermanagement.api.GetPropertyRoleQuery
import com.mwombeki.peak.usermanagement.api.ListPropertyRolesQuery
import com.mwombeki.peak.usermanagement.api.ListUserPropertyRolesQuery
import com.mwombeki.peak.usermanagement.api.PropertyAccessBootstrapPort
import com.mwombeki.peak.usermanagement.api.PropertyAccessBootstrapReceipt
import com.mwombeki.peak.usermanagement.api.PropertyRoleMutationReceipt
import com.mwombeki.peak.usermanagement.api.PropertyRoleSummary
import com.mwombeki.peak.usermanagement.api.PropertyUserRoleAssignmentReceipt
import com.mwombeki.peak.usermanagement.api.RevokePropertyUserRoleCommand
import com.mwombeki.peak.usermanagement.api.TenantPropertyRoleManagementPort
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementConflictException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementInProgressException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementNotFoundException
import com.mwombeki.peak.usermanagement.api.UpdatePropertyRoleCommand
import java.sql.Array
import java.sql.ResultSet
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class TenantPropertyRoleManagementService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) : TenantPropertyRoleManagementPort, PropertyAccessBootstrapPort {

    override fun listPropertyRoles(query: ListPropertyRolesQuery): List<PropertyRoleSummary> {
        return requireNotNull(
            transactionTemplate.execute {
                bindTenantPropertyRoleViewer(query.tenantId)
                requirePropertyBelongsToTenant(query.tenantId, query.propertyId)
                queryPropertyRoles(query.tenantId, query.propertyId, null)
            },
        )
    }

    override fun getPropertyRole(query: GetPropertyRoleQuery): PropertyRoleSummary? {
        return requireNotNull(
            transactionTemplate.execute {
                bindTenantPropertyRoleViewer(query.tenantId)
                requirePropertyBelongsToTenant(query.tenantId, query.propertyId)
                queryPropertyRoles(query.tenantId, query.propertyId, query.propertyRoleId)
                    .singleOrNull()
            },
        )
    }

    override fun createPropertyRole(command: CreatePropertyRoleCommand): PropertyRoleMutationReceipt {
        return mutatePropertyRole(
            tenantId = command.tenantId,
            propertyId = command.propertyId,
            operationType = "tenant.property.role.create",
            requestPayload = command,
        ) { idempotencyKeyId ->
            val propertyRoleId = UUID.randomUUID()
            val permissionIds = requireDelegablePropertyPermissionIds(
                tenantId = command.tenantId,
                propertyId = command.propertyId,
                permissionCodes = command.permissionCodes,
            )
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO roles (id, tenant_id, name, is_system, is_active)
                    VALUES (?, ?, ?, false, true)
                    """.trimIndent(),
                    propertyRoleId,
                    command.tenantId,
                    command.name.normalizedRequired("name"),
                )
            } catch (ex: DuplicateKeyException) {
                throw TenantUserRoleManagementConflictException("Property role name is already in use")
            }
            replacePropertyRolePermissions(propertyRoleId, permissionIds)

            PropertyRoleMutationReceipt(
                tenantId = command.tenantId,
                propertyId = command.propertyId,
                propertyRoleId = propertyRoleId,
                isActive = true,
                changed = true,
                replayed = false,
            ).also { receipt ->
                recordPropertyRoleSideEffects(
                    tenantId = command.tenantId,
                    propertyId = command.propertyId,
                    propertyRoleId = propertyRoleId,
                    action = "created",
                    eventType = "tenant.property.role.created",
                    payload = mapOf(
                        "tenantId" to command.tenantId,
                        "propertyId" to command.propertyId,
                        "propertyRoleId" to propertyRoleId,
                        "name" to command.name.normalizedRequired("name"),
                        "permissionCodes" to command.permissionCodes.map { it.normalizedCode() },
                    ),
                    idempotencyKeyId = idempotencyKeyId,
                )
            }
        }
    }

    override fun updatePropertyRole(command: UpdatePropertyRoleCommand): PropertyRoleMutationReceipt {
        return mutatePropertyRole(
            tenantId = command.tenantId,
            propertyId = command.propertyId,
            operationType = "tenant.property.role.update",
            requestPayload = command,
        ) { idempotencyKeyId ->
            requireMutablePropertyRole(command.tenantId, command.propertyId, command.propertyRoleId)
            requireDelegablePropertyRole(command.tenantId, command.propertyId, command.propertyRoleId)
            val permissionIds = command.permissionCodes?.let {
                requireDelegablePropertyPermissionIds(
                    tenantId = command.tenantId,
                    propertyId = command.propertyId,
                    permissionCodes = it,
                )
            }
            val rows = jdbcTemplate.update(
                """
                UPDATE roles
                SET name = COALESCE(?, name),
                    updated_at = now()
                WHERE tenant_id = ?
                  AND id = ?
                  AND is_system = false
                """.trimIndent(),
                command.name?.normalizedRequired("name"),
                command.tenantId,
                command.propertyRoleId,
            )
            if (permissionIds != null) {
                replacePropertyRolePermissions(command.propertyRoleId, permissionIds)
            }

            PropertyRoleMutationReceipt(
                tenantId = command.tenantId,
                propertyId = command.propertyId,
                propertyRoleId = command.propertyRoleId,
                isActive = propertyRoleIsActive(command.tenantId, command.propertyRoleId),
                changed = rows == 1 || permissionIds != null,
                replayed = false,
            ).also { receipt ->
                if (receipt.changed) {
                    recordPropertyRoleSideEffects(
                        tenantId = command.tenantId,
                        propertyId = command.propertyId,
                        propertyRoleId = command.propertyRoleId,
                        action = "updated",
                        eventType = "tenant.property.role.updated",
                        payload = mapOf(
                            "tenantId" to command.tenantId,
                            "propertyId" to command.propertyId,
                            "propertyRoleId" to command.propertyRoleId,
                            "permissionsChanged" to (permissionIds != null),
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
            }
        }
    }

    override fun deactivatePropertyRole(command: DeactivatePropertyRoleCommand): PropertyRoleMutationReceipt {
        return mutatePropertyRole(
            tenantId = command.tenantId,
            propertyId = command.propertyId,
            operationType = "tenant.property.role.deactivate",
            requestPayload = command,
        ) { idempotencyKeyId ->
            requireMutablePropertyRole(command.tenantId, command.propertyId, command.propertyRoleId)
            requireDelegablePropertyRole(command.tenantId, command.propertyId, command.propertyRoleId)
            val rows = jdbcTemplate.update(
                """
                UPDATE roles
                SET is_active = false,
                    updated_at = now()
                WHERE tenant_id = ?
                  AND id = ?
                  AND is_system = false
                  AND is_active = true
                """.trimIndent(),
                command.tenantId,
                command.propertyRoleId,
            )
            if (rows == 1) {
                jdbcTemplate.update(
                    """
                    DELETE FROM user_property_roles
                    WHERE tenant_id = ?
                      AND role_id = ?
                    """.trimIndent(),
                    command.tenantId,
                    command.propertyRoleId,
                )
            }

            PropertyRoleMutationReceipt(
                tenantId = command.tenantId,
                propertyId = command.propertyId,
                propertyRoleId = command.propertyRoleId,
                isActive = false,
                changed = rows == 1,
                replayed = false,
            ).also { receipt ->
                if (receipt.changed) {
                    recordPropertyRoleSideEffects(
                        tenantId = command.tenantId,
                        propertyId = command.propertyId,
                        propertyRoleId = command.propertyRoleId,
                        action = "deactivated",
                        eventType = "tenant.property.role.deactivated",
                        payload = mapOf(
                            "tenantId" to command.tenantId,
                            "propertyId" to command.propertyId,
                            "propertyRoleId" to command.propertyRoleId,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
            }
        }
    }

    override fun listUserPropertyRoles(query: ListUserPropertyRolesQuery): List<PropertyRoleSummary> {
        return requireNotNull(
            transactionTemplate.execute {
                bindTenantPropertyRoleViewer(query.tenantId)
                requirePropertyBelongsToTenant(query.tenantId, query.propertyId)
                requireTenantUser(query.tenantId, query.userId, activeOnly = false)
                jdbcTemplate.query(
                    """
                    SELECT
                        r.id,
                        r.tenant_id,
                        r.name,
                        r.is_system,
                        r.is_active,
                        COALESCE(
                            array_agg(p.code ORDER BY p.code) FILTER (WHERE p.code IS NOT NULL),
                            ARRAY[]::text[]
                        ) AS permission_codes
                    FROM user_property_roles upr
                    JOIN roles r
                      ON r.id = upr.role_id
                     AND r.tenant_id = upr.tenant_id
                    LEFT JOIN role_permissions rp
                      ON rp.role_id = r.id
                    LEFT JOIN permissions p
                      ON p.id = rp.permission_id
                     AND p.tenant_id = r.tenant_id
                    WHERE upr.tenant_id = ?
                      AND upr.property_id = ?
                      AND upr.user_id = ?
                    GROUP BY r.id, r.tenant_id, r.name, r.is_system, r.is_active
                    ORDER BY r.is_system DESC, r.name
                    """.trimIndent(),
                    { rs, _ -> mapPropertyRole(rs, query.propertyId) },
                    query.tenantId,
                    query.propertyId,
                    query.userId,
                )
            },
        )
    }

    override fun assignPropertyUserRole(
        command: AssignPropertyUserRoleCommand,
    ): PropertyUserRoleAssignmentReceipt {
        return mutatePropertyAssignment(
            tenantId = command.tenantId,
            propertyId = command.propertyId,
            userId = command.userId,
            propertyRoleId = command.propertyRoleId,
            operationType = "tenant.property.user.role.assign",
            requestPayload = command,
        ) { actorUserId, idempotencyKeyId ->
            require(actorUserId != command.userId) {
                "Tenant user cannot assign own property roles"
            }
            requireActiveTenantUser(command.tenantId, command.userId)
            requireAssignablePropertyRole(command.tenantId, command.propertyId, command.propertyRoleId)

            val inserted = jdbcTemplate.update(
                """
                INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT user_property_roles_pkey DO NOTHING
                """.trimIndent(),
                command.userId,
                command.propertyId,
                command.propertyRoleId,
                command.tenantId,
            ) == 1

            PropertyUserRoleAssignmentReceipt(
                tenantId = command.tenantId,
                propertyId = command.propertyId,
                userId = command.userId,
                propertyRoleId = command.propertyRoleId,
                assigned = true,
                changed = inserted,
                replayed = false,
            ).also { receipt ->
                if (inserted) {
                    recordPropertyAssignmentSideEffects(
                        tenantId = command.tenantId,
                        propertyId = command.propertyId,
                        userId = command.userId,
                        propertyRoleId = command.propertyRoleId,
                        action = "assigned",
                        eventType = "tenant.property.user.role.assigned",
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
            }
        }
    }

    override fun revokePropertyUserRole(
        command: RevokePropertyUserRoleCommand,
    ): PropertyUserRoleAssignmentReceipt {
        return mutatePropertyAssignment(
            tenantId = command.tenantId,
            propertyId = command.propertyId,
            userId = command.userId,
            propertyRoleId = command.propertyRoleId,
            operationType = "tenant.property.user.role.revoke",
            requestPayload = command,
        ) { actorUserId, idempotencyKeyId ->
            require(actorUserId != command.userId) {
                "Tenant user cannot revoke own property roles"
            }
            requireTenantUser(command.tenantId, command.userId, activeOnly = false)
            requireRevocablePropertyRole(command.tenantId, command.propertyRoleId)
            requireDelegablePropertyRole(command.tenantId, command.propertyId, command.propertyRoleId)

            val deleted = jdbcTemplate.update(
                """
                DELETE FROM user_property_roles
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND user_id = ?
                  AND role_id = ?
                """.trimIndent(),
                command.tenantId,
                command.propertyId,
                command.userId,
                command.propertyRoleId,
            ) == 1

            PropertyUserRoleAssignmentReceipt(
                tenantId = command.tenantId,
                propertyId = command.propertyId,
                userId = command.userId,
                propertyRoleId = command.propertyRoleId,
                assigned = false,
                changed = deleted,
                replayed = false,
            ).also { receipt ->
                if (deleted) {
                    recordPropertyAssignmentSideEffects(
                        tenantId = command.tenantId,
                        propertyId = command.propertyId,
                        userId = command.userId,
                        propertyRoleId = command.propertyRoleId,
                        action = "revoked",
                        eventType = "tenant.property.user.role.revoked",
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
            }
        }
    }

    override fun ensurePropertyAdministrator(
        command: EnsurePropertyAdministratorCommand,
    ): PropertyAccessBootstrapReceipt {
        return requireNotNull(
            transactionTemplate.execute {
                val identity = requestContextHolder.current().identity
                require(identity is RequestIdentity.Tenant) {
                    "Tenant user identity is required"
                }
                require(identity.tenantId == command.tenantId) {
                    "Requested tenant does not match identity"
                }
                databaseSessionContext.bind(identity)
                requirePropertyBelongsToTenant(command.tenantId, command.propertyId)
                requireActiveTenantUser(command.tenantId, command.tenantUserId)

                var changed = false
                val roleId = ensureSystemPropertyAdministratorRole(command.tenantId).also {
                    changed = changed || it.changed
                }.propertyRoleId
                val insertedAssignment = jdbcTemplate.update(
                    """
                    INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT ON CONSTRAINT user_property_roles_pkey DO NOTHING
                    """.trimIndent(),
                    command.tenantUserId,
                    command.propertyId,
                    roleId,
                    command.tenantId,
                ) == 1
                changed = changed || insertedAssignment
                if (changed) {
                    recordPropertyAssignmentSideEffects(
                        tenantId = command.tenantId,
                        propertyId = command.propertyId,
                        userId = command.tenantUserId,
                        propertyRoleId = roleId,
                        action = "bootstrapped",
                        eventType = "tenant.property.user.role.bootstrapped",
                        idempotencyKeyId = null,
                    )
                }
                PropertyAccessBootstrapReceipt(
                    tenantId = command.tenantId,
                    propertyId = command.propertyId,
                    tenantUserId = command.tenantUserId,
                    propertyRoleId = roleId,
                    changed = changed,
                )
            },
        )
    }

    private fun queryPropertyRoles(
        tenantId: UUID,
        propertyId: UUID,
        propertyRoleId: UUID?,
    ): List<PropertyRoleSummary> {
        val idClause = if (propertyRoleId != null) "AND r.id = ?" else ""
        val args = mutableListOf<Any>(tenantId)
        if (propertyRoleId != null) {
            args.add(propertyRoleId)
        }
        return jdbcTemplate.query(
            """
            SELECT
                r.id,
                r.tenant_id,
                r.name,
                r.is_system,
                r.is_active,
                COALESCE(
                    array_agg(p.code ORDER BY p.code) FILTER (WHERE p.code IS NOT NULL),
                    ARRAY[]::text[]
                ) AS permission_codes
            FROM roles r
            LEFT JOIN role_permissions rp
              ON rp.role_id = r.id
            LEFT JOIN permissions p
              ON p.id = rp.permission_id
             AND p.tenant_id = r.tenant_id
            WHERE r.tenant_id = ?
              $idClause
            GROUP BY r.id, r.tenant_id, r.name, r.is_system, r.is_active
            ORDER BY r.is_system DESC, r.name
            """.trimIndent(),
            { rs, _ -> mapPropertyRole(rs, propertyId) },
            *args.toTypedArray(),
        )
    }

    private fun mutatePropertyRole(
        tenantId: UUID,
        propertyId: UUID,
        operationType: String,
        requestPayload: Any,
        block: (UUID) -> PropertyRoleMutationReceipt,
    ): PropertyRoleMutationReceipt {
        return requireNotNull(
            transactionTemplate.execute {
                bindTenantAccessManager(tenantId)
                requirePropertyBelongsToTenant(tenantId, propertyId)
                val reservation = idempotencyPort.reserve(
                    IdempotencyCommand(
                        operationType = operationType,
                        requestPayload = requestPayload,
                        resourceType = "roles",
                    ),
                )
                when (reservation) {
                    is IdempotencyReservation.Started -> {
                        val receipt = block(reservation.recordId)
                        idempotencyPort.markSucceeded(
                            recordId = reservation.recordId,
                            responseCode = 200,
                            responseBody = receipt,
                            resourceId = receipt.propertyRoleId,
                        )
                        receipt
                    }

                    is IdempotencyReservation.Replay -> replayPropertyRoleMutation(reservation)
                    is IdempotencyReservation.InProgress -> throw TenantUserRoleManagementInProgressException(
                        "Property role command is already being processed for this idempotency key",
                    )

                    is IdempotencyReservation.Conflict -> throw TenantUserRoleManagementConflictException(
                        "Idempotency key was already used for a different property role request",
                    )
                }
            },
        )
    }

    private fun mutatePropertyAssignment(
        tenantId: UUID,
        propertyId: UUID,
        userId: UUID,
        propertyRoleId: UUID,
        operationType: String,
        requestPayload: Any,
        block: (UUID, UUID) -> PropertyUserRoleAssignmentReceipt,
    ): PropertyUserRoleAssignmentReceipt {
        return requireNotNull(
            transactionTemplate.execute {
                val actorUserId = bindTenantAccessManager(tenantId)
                requirePropertyBelongsToTenant(tenantId, propertyId)
                val reservation = idempotencyPort.reserve(
                    IdempotencyCommand(
                        operationType = operationType,
                        requestPayload = requestPayload,
                        resourceType = "user_property_roles",
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

                    is IdempotencyReservation.Replay -> replayPropertyAssignment(reservation)
                    is IdempotencyReservation.InProgress -> throw TenantUserRoleManagementInProgressException(
                        "Property user role command is already being processed for this idempotency key",
                    )

                    is IdempotencyReservation.Conflict -> throw TenantUserRoleManagementConflictException(
                        "Idempotency key was already used for a different property user role request",
                    )
                }
            },
        )
    }

    private fun bindTenantAccessManager(tenantId: UUID): UUID {
        return bindTenantActorWithPermission(
            tenantId = tenantId,
            permissionCode = TENANT_PROPERTY_ACCESS_PERMISSION,
            denialMessage = "Tenant user lacks property access management permission",
        )
    }

    private fun bindTenantPropertyRoleViewer(tenantId: UUID): UUID {
        return bindTenantActorWithPermission(
            tenantId = tenantId,
            permissionCode = TENANT_PROPERTY_ROLE_VIEW_PERMISSION,
            denialMessage = "Tenant user lacks property role view permission",
        )
    }

    private fun bindTenantActorWithPermission(
        tenantId: UUID,
        permissionCode: String,
        denialMessage: String,
    ): UUID {
        val identity = requestContextHolder.current().identity
        require(identity is RequestIdentity.Tenant) {
            "Tenant user identity is required"
        }
        require(identity.tenantId == tenantId) {
            "Requested tenant does not match identity"
        }
        databaseSessionContext.bind(identity)
        val allowed = jdbcTemplate.queryForObject(
            "SELECT user_has_tenant_permission(?, ?, ?)",
            Boolean::class.java,
            identity.tenantUserId,
            tenantId,
            permissionCode,
        ) == true
        require(allowed) {
            denialMessage
        }
        return identity.tenantUserId
    }

    private fun requirePropertyBelongsToTenant(tenantId: UUID, propertyId: UUID) {
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM properties
                WHERE tenant_id = ?
                  AND id = ?
                  AND deleted_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            propertyId,
        ) == true
        if (!exists) {
            throw TenantUserRoleManagementNotFoundException("Property was not found for tenant")
        }
    }

    private fun requireActiveTenantUser(tenantId: UUID, userId: UUID) {
        requireTenantUser(tenantId, userId, activeOnly = true)
    }

    private fun requireTenantUser(tenantId: UUID, userId: UUID, activeOnly: Boolean) {
        val activeClause = if (activeOnly) {
            """
              AND status = 'active'
              AND is_active = true
              AND (locked_until IS NULL OR locked_until <= now())
            """.trimIndent()
        } else {
            ""
        }
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM users
                WHERE tenant_id = ?
                  AND id = ?
                  AND deleted_at IS NULL
                  $activeClause
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            userId,
        ) == true
        if (!exists) {
            throw TenantUserRoleManagementNotFoundException(
                if (activeOnly) "Active tenant user was not found" else "Tenant user was not found",
            )
        }
    }

    private fun requireActivePropertyRole(tenantId: UUID, propertyRoleId: UUID): PropertyRolePolicy {
        return requirePropertyRole(tenantId, propertyRoleId, activeOnly = true)
    }

    private fun requireAssignablePropertyRole(
        tenantId: UUID,
        propertyId: UUID,
        propertyRoleId: UUID,
    ): PropertyRolePolicy {
        val role = requireActivePropertyRole(tenantId, propertyRoleId)
        require(!role.isSystem) {
            "System property roles cannot be assigned or revoked through tenant property role management"
        }
        requireDelegablePropertyRole(tenantId, propertyId, propertyRoleId)
        return role
    }

    private fun requireRevocablePropertyRole(
        tenantId: UUID,
        propertyRoleId: UUID,
    ): PropertyRolePolicy {
        val role = requirePropertyRole(tenantId, propertyRoleId, activeOnly = false)
        require(!role.isSystem) {
            "System property roles cannot be assigned or revoked through tenant property role management"
        }
        return role
    }

    private fun requirePropertyRole(
        tenantId: UUID,
        propertyRoleId: UUID,
        activeOnly: Boolean,
    ): PropertyRolePolicy {
        val activeClause = if (activeOnly) "AND is_active = true" else ""
        return jdbcTemplate.query(
            """
            SELECT is_system
            FROM roles
            WHERE tenant_id = ?
              AND id = ?
              $activeClause
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> PropertyRolePolicy(rs.getBoolean("is_system")) },
            tenantId,
            propertyRoleId,
        ).singleOrNull()
            ?: throw TenantUserRoleManagementNotFoundException(
                if (activeOnly) "Active property role was not found" else "Property role was not found",
            )
    }

    private fun requireMutablePropertyRole(
        tenantId: UUID,
        propertyId: UUID,
        propertyRoleId: UUID,
    ) {
        val role = requirePropertyRole(tenantId, propertyRoleId, activeOnly = false)
        require(!role.isSystem) {
            "System property roles cannot be modified"
        }
        val actorUserId = (requestContextHolder.current().identity as RequestIdentity.Tenant).tenantUserId
        val assignedToActor = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM user_property_roles
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND user_id = ?
                  AND role_id = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            propertyId,
            actorUserId,
            propertyRoleId,
        ) == true
        require(!assignedToActor) {
            "Tenant user cannot modify a property role assigned to self"
        }
    }

    private fun requireDelegablePropertyRole(
        tenantId: UUID,
        propertyId: UUID,
        propertyRoleId: UUID,
    ) {
        val permissionCodes = jdbcTemplate.queryForList(
            """
            SELECT p.code
            FROM role_permissions rp
            JOIN roles r
              ON r.id = rp.role_id
             AND r.tenant_id = ?
            JOIN permissions p
              ON p.id = rp.permission_id
             AND p.tenant_id = r.tenant_id
            WHERE rp.role_id = ?
            ORDER BY p.code
            """.trimIndent(),
            String::class.java,
            tenantId,
            propertyRoleId,
        ).filterNotNull()
        requireDelegablePropertyPermissions(tenantId, propertyId, permissionCodes)
    }

    private fun requireDelegablePropertyPermissionIds(
        tenantId: UUID,
        propertyId: UUID,
        permissionCodes: List<String>,
    ): List<UUID> {
        val permissionIds = requirePropertyPermissionIds(tenantId, permissionCodes)
        requireDelegablePropertyPermissions(tenantId, propertyId, permissionCodes)
        return permissionIds
    }

    private fun requireDelegablePropertyPermissions(
        tenantId: UUID,
        propertyId: UUID,
        permissionCodes: List<String>,
    ) {
        val actorUserId = (requestContextHolder.current().identity as RequestIdentity.Tenant).tenantUserId
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

        val unauthorized = permissionCodes
            .map { it.normalizedCode() }
            .distinct()
            .filterNot { permissionCode ->
                jdbcTemplate.queryForObject(
                    "SELECT user_has_property_permission(?, ?, ?, ?)",
                    Boolean::class.java,
                    actorUserId,
                    tenantId,
                    propertyId,
                    permissionCode,
                ) == true
            }
        require(unauthorized.isEmpty()) {
            "Property roles cannot include permissions the actor does not hold for this property"
        }
    }

    private fun requirePropertyPermissionIds(
        tenantId: UUID,
        permissionCodes: List<String>,
    ): List<UUID> {
        val normalizedCodes = permissionCodes.map { it.normalizedCode() }.distinct()
        require(normalizedCodes.isNotEmpty()) {
            "At least one property permission is required"
        }
        val placeholders = normalizedCodes.joinToString(", ") { "?" }
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
              AND pc.access_scope IN ('property', 'both')
            ORDER BY p.code
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            *args.toTypedArray(),
        )
        if (permissionIds.size != normalizedCodes.size) {
            throw TenantUserRoleManagementNotFoundException(
                "One or more property permissions were not found",
            )
        }
        return permissionIds
    }

    private fun replacePropertyRolePermissions(propertyRoleId: UUID, permissionIds: List<UUID>) {
        jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id = ?", propertyRoleId)
        permissionIds.forEach { permissionId ->
            jdbcTemplate.update(
                """
                INSERT INTO role_permissions (role_id, permission_id)
                VALUES (?, ?)
                ON CONFLICT ON CONSTRAINT role_permissions_pkey DO NOTHING
                """.trimIndent(),
                propertyRoleId,
                permissionId,
            )
        }
    }

    private fun propertyRoleIsActive(tenantId: UUID, propertyRoleId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT is_active
            FROM roles
            WHERE tenant_id = ?
              AND id = ?
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            propertyRoleId,
        ) == true
    }

    private fun ensureSystemPropertyAdministratorRole(tenantId: UUID): PropertyRoleMutationState {
        var changed = false
        val roleId = jdbcTemplate.query(
            """
            SELECT id
            FROM roles
            WHERE tenant_id = ?
              AND name = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            tenantId,
            PROPERTY_ADMIN_ROLE_NAME,
        ).singleOrNull() ?: UUID.randomUUID().also { id ->
            jdbcTemplate.update(
                """
                INSERT INTO roles (id, tenant_id, name, is_system, is_active)
                VALUES (?, ?, ?, true, true)
                """.trimIndent(),
                id,
                tenantId,
                PROPERTY_ADMIN_ROLE_NAME,
            )
            changed = true
        }

        val permissionIds = ensurePropertyPermissionIds(tenantId, PROPERTY_ADMIN_PERMISSION_CODES.toList())
        permissionIds.forEach { permissionId ->
            val inserted = jdbcTemplate.update(
                """
                INSERT INTO role_permissions (role_id, permission_id)
                VALUES (?, ?)
                ON CONFLICT ON CONSTRAINT role_permissions_pkey DO NOTHING
                """.trimIndent(),
                roleId,
                permissionId,
            ) == 1
            changed = changed || inserted
        }

        return PropertyRoleMutationState(roleId, changed)
    }

    private fun ensurePropertyPermissionIds(
        tenantId: UUID,
        permissionCodes: List<String>,
    ): List<UUID> {
        val normalizedCodes = permissionCodes.map { it.normalizedCode() }.distinct()
        normalizedCodes.forEach { code ->
            jdbcTemplate.update(
                """
                INSERT INTO permissions (id, tenant_id, code, description)
                SELECT gen_random_uuid(), ?, pc.code, pc.description
                FROM permission_catalog pc
                WHERE pc.code = ?
                  AND pc.is_tenant_permission = true
                  AND pc.access_scope IN ('property', 'both')
                ON CONFLICT (tenant_id, code) DO UPDATE SET
                    description = EXCLUDED.description,
                    updated_at = now()
                """.trimIndent(),
                tenantId,
                code,
            )
        }
        return requirePropertyPermissionIds(tenantId, normalizedCodes)
    }

    private fun recordPropertyRoleSideEffects(
        tenantId: UUID,
        propertyId: UUID,
        propertyRoleId: UUID,
        action: String,
        eventType: String,
        payload: Map<String, Any?>,
        idempotencyKeyId: UUID?,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = "tenant.property.roles.$action",
                resource = AuditResource("roles", propertyRoleId),
                after = payload,
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "roles",
                aggregateId = propertyRoleId,
                tenantId = tenantId,
                propertyId = propertyId,
                eventType = eventType,
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                idempotencyKeyId = idempotencyKeyId,
                priority = 3,
            ),
        )
    }

    private fun recordPropertyAssignmentSideEffects(
        tenantId: UUID,
        propertyId: UUID,
        userId: UUID,
        propertyRoleId: UUID,
        action: String,
        eventType: String,
        idempotencyKeyId: UUID?,
    ) {
        val payload = mapOf(
            "tenantId" to tenantId,
            "propertyId" to propertyId,
            "userId" to userId,
            "propertyRoleId" to propertyRoleId,
            "action" to action,
        )
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = "tenant.property.user.role.$action",
                resource = AuditResource("user_property_roles", userId),
                after = payload,
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "user_property_roles",
                aggregateId = userId,
                tenantId = tenantId,
                propertyId = propertyId,
                eventType = eventType,
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                idempotencyKeyId = idempotencyKeyId,
                priority = 3,
            ),
        )
    }

    private fun replayPropertyRoleMutation(
        reservation: IdempotencyReservation.Replay,
    ): PropertyRoleMutationReceipt {
        if (reservation.responseBody.isNullOrBlank()) {
            throw TenantUserRoleManagementConflictException(
                "Property role replay does not contain a stored response body",
            )
        }
        return objectMapper.readValue(
            reservation.responseBody,
            PropertyRoleMutationReceipt::class.java,
        ).copy(replayed = true)
    }

    private fun replayPropertyAssignment(
        reservation: IdempotencyReservation.Replay,
    ): PropertyUserRoleAssignmentReceipt {
        if (reservation.responseBody.isNullOrBlank()) {
            throw TenantUserRoleManagementConflictException(
                "Property user role replay does not contain a stored response body",
            )
        }
        return objectMapper.readValue(
            reservation.responseBody,
            PropertyUserRoleAssignmentReceipt::class.java,
        ).copy(replayed = true)
    }

    private fun mapPropertyRole(rs: ResultSet, propertyId: UUID): PropertyRoleSummary {
        return PropertyRoleSummary(
            propertyRoleId = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            propertyId = propertyId,
            name = rs.getString("name"),
            isSystem = rs.getBoolean("is_system"),
            isActive = rs.getBoolean("is_active"),
            permissionCodes = rs.getArray("permission_codes").toStringList(),
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

    private data class PropertyRolePolicy(
        val isSystem: Boolean,
    )

    private data class PropertyRoleMutationState(
        val propertyRoleId: UUID,
        val changed: Boolean,
    )

    private companion object {
        private const val TENANT_PROPERTY_ACCESS_PERMISSION = "tenant.properties.manage_access"
        private const val TENANT_PROPERTY_ROLE_VIEW_PERMISSION = "tenant.properties.roles.view"
        private const val TENANT_ADMIN_ALL_PERMISSION = "tenant.admin.all"
        private const val PROPERTY_ADMIN_ROLE_NAME = "Property Administrator"

        private val PROPERTY_ADMIN_PERMISSION_CODES = setOf(
            "admin.all",
            "property.view",
            "property.manage",
            "property.lifecycle",
            "property.roles.view",
            "property.roles.manage",
            "realtime.stream",
        )
    }
}
