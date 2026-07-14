package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.PlatformAuditEvent
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.AssignPlatformAdministratorCommand
import com.mwombeki.peak.usermanagement.api.AssignPlatformUserRoleCommand
import com.mwombeki.peak.usermanagement.api.CreatePlatformRoleCommand
import com.mwombeki.peak.usermanagement.api.CreatePlatformUserCommand
import com.mwombeki.peak.usermanagement.api.DeactivatePlatformRoleCommand
import com.mwombeki.peak.usermanagement.api.LinkPlatformOidcIdentityCommand
import com.mwombeki.peak.usermanagement.api.PlatformAdministrationConflictException
import com.mwombeki.peak.usermanagement.api.PlatformAdministrationInProgressException
import com.mwombeki.peak.usermanagement.api.PlatformAdministrationNotFoundException
import com.mwombeki.peak.usermanagement.api.PlatformAdministrationPort
import com.mwombeki.peak.usermanagement.api.PlatformAdministratorSummary
import com.mwombeki.peak.usermanagement.api.PlatformIdentityLinkReceipt
import com.mwombeki.peak.usermanagement.api.PlatformPermissionSummary
import com.mwombeki.peak.usermanagement.api.PlatformRoleMutationReceipt
import com.mwombeki.peak.usermanagement.api.PlatformRoleSummary
import com.mwombeki.peak.usermanagement.api.PlatformUserLifecycleCommand
import com.mwombeki.peak.usermanagement.api.PlatformUserMutationReceipt
import com.mwombeki.peak.usermanagement.api.PlatformUserRoleMutationReceipt
import com.mwombeki.peak.usermanagement.api.PlatformUserSummary
import com.mwombeki.peak.usermanagement.api.ProvisionTenantAdministratorCommand
import com.mwombeki.peak.usermanagement.api.RevokePlatformAdministratorCommand
import com.mwombeki.peak.usermanagement.api.RevokePlatformOidcIdentityCommand
import com.mwombeki.peak.usermanagement.api.RevokePlatformUserRoleCommand
import com.mwombeki.peak.usermanagement.api.SupportTenantAccessPort
import com.mwombeki.peak.usermanagement.api.SupportTenantAccessRequest
import com.mwombeki.peak.usermanagement.api.TenantAdministratorProvisioningReceipt
import com.mwombeki.peak.usermanagement.api.TenantProfileVerificationReceipt
import com.mwombeki.peak.usermanagement.api.UpdatePlatformRoleCommand
import com.mwombeki.peak.usermanagement.api.UpdatePlatformUserCommand
import com.mwombeki.peak.usermanagement.api.VerifyTenantBusinessProfileCommand
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Array
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class PlatformAdministrationService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val supportTenantAccessPort: SupportTenantAccessPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : PlatformAdministrationPort {

    override fun listPlatformUsers(): List<PlatformUserSummary> {
        return requireNotNull(
            transactionTemplate.execute {
                bindPlatformContext()
                jdbcTemplate.query(
                    """
                    $PLATFORM_USER_SELECT
                    WHERE pu.deleted_at IS NULL
                    $PLATFORM_USER_GROUP_BY
                    ORDER BY pu.created_at DESC
                    """.trimIndent(),
                    ::mapPlatformUser,
                )
            },
        )
    }

    override fun getPlatformUser(platformUserId: UUID): PlatformUserSummary? {
        return requireNotNull(
            transactionTemplate.execute {
                bindPlatformContext()
                jdbcTemplate.query(
                    """
                    $PLATFORM_USER_SELECT
                    WHERE pu.deleted_at IS NULL
                      AND pu.id = ?
                    $PLATFORM_USER_GROUP_BY
                    """.trimIndent(),
                    ::mapPlatformUser,
                    platformUserId,
                ).singleOrNull()
            },
        )
    }

    override fun listPlatformAdministrators(): List<PlatformAdministratorSummary> {
        return requireNotNull(
            transactionTemplate.execute {
                bindPlatformContext()
                requireCurrentPlatformPermission(
                    PLATFORM_ROLE_VIEW_PERMISSION,
                    "Platform operator lacks platform role view permission",
                )
                jdbcTemplate.query(
                    """
                    SELECT
                        pu.id AS platform_user_id,
                        pr.id AS platform_role_id,
                        pu.full_name,
                        pu.email,
                        pu.status,
                        pu.locked_until,
                        COUNT(DISTINCT il.id)::integer AS active_identity_links,
                        (
                            pu.status = 'active'
                            AND pu.deleted_at IS NULL
                            AND (pu.locked_until IS NULL OR pu.locked_until <= now())
                            AND COUNT(DISTINCT il.id) > 0
                            AND pr.is_active = true
                        ) AS effective
                    FROM platform_user_roles pur
                    JOIN platform_roles pr
                      ON pr.id = pur.platform_role_id
                    JOIN platform_users pu
                      ON pu.id = pur.platform_user_id
                    LEFT JOIN identity_links il
                      ON il.platform_user_id = pu.id
                     AND il.identity_mode = 'platform'
                     AND il.revoked_at IS NULL
                    WHERE pr.code = ?
                      AND pr.is_system = true
                    GROUP BY pu.id, pr.id, pr.is_active
                    ORDER BY pu.full_name, pu.email, pu.id
                    """.trimIndent(),
                    { rs, _ ->
                        PlatformAdministratorSummary(
                            platformUserId = rs.getObject("platform_user_id", UUID::class.java),
                            platformRoleId = rs.getObject("platform_role_id", UUID::class.java),
                            fullName = rs.getString("full_name"),
                            email = rs.getString("email"),
                            status = rs.getString("status"),
                            lockedUntil = rs.getTimestamp("locked_until")?.toInstant(),
                            activeIdentityLinks = rs.getInt("active_identity_links"),
                            effective = rs.getBoolean("effective"),
                        )
                    },
                    PLATFORM_ROOT_ROLE_CODE,
                )
            },
        )
    }

    override fun createPlatformUser(
        command: CreatePlatformUserCommand,
    ): PlatformUserMutationReceipt {
        return mutate(
            operationType = "platform.user.create",
            requestPayload = command,
            resourceType = "platform_users",
            replayType = PlatformUserMutationReceipt::class.java,
        ) { reservationId ->
            val id = UUID.randomUUID()
            val status = canonicalInitialStatus(command.status)
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO platform_users (id, full_name, email, status)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                    id,
                    command.fullName.normalizedRequired("fullName"),
                    command.email.normalizedEmail(),
                    status,
                )
            } catch (ex: DuplicateKeyException) {
                throw PlatformAdministrationConflictException("Platform user email is already in use")
            }

            PlatformUserMutationReceipt(id, status, changed = true, replayed = false)
                .also { receipt ->
                    recordPlatformSideEffects(
                        action = "platform.users.created",
                        resourceType = "platform_users",
                        resourceId = id,
                        payload = mapOf(
                            "platformUserId" to id,
                            "email" to command.email.normalizedEmail(),
                            "status" to status,
                        ),
                        idempotencyKeyId = reservationId,
                    )
                }
        }
    }

    override fun updatePlatformUser(
        command: UpdatePlatformUserCommand,
    ): PlatformUserMutationReceipt {
        return mutate(
            operationType = "platform.user.update",
            requestPayload = command,
            resourceType = "platform_users",
            replayType = PlatformUserMutationReceipt::class.java,
        ) { reservationId ->
            val actorId = currentPlatformActorId()
            requirePlatformUser(command.platformUserId)
            requireActorCanManagePlatformUser(actorId, command.platformUserId)
            val rows = try {
                jdbcTemplate.update(
                    """
                    UPDATE platform_users
                    SET full_name = COALESCE(?, full_name),
                        email = COALESCE(?, email),
                        updated_at = now()
                    WHERE id = ?
                      AND deleted_at IS NULL
                    """.trimIndent(),
                    command.fullName?.normalizedRequired("fullName"),
                    command.email?.normalizedEmail(),
                    command.platformUserId,
                )
            } catch (ex: DuplicateKeyException) {
                throw PlatformAdministrationConflictException("Platform user email is already in use")
            }

            val status = platformUserStatus(command.platformUserId)
            PlatformUserMutationReceipt(
                platformUserId = command.platformUserId,
                status = status,
                changed = rows == 1,
                replayed = false,
            ).also { receipt ->
                if (receipt.changed) {
                    recordPlatformSideEffects(
                        action = "platform.users.updated",
                        resourceType = "platform_users",
                        resourceId = command.platformUserId,
                        payload = mapOf(
                            "platformUserId" to command.platformUserId,
                            "fullNameChanged" to (command.fullName != null),
                            "emailChanged" to (command.email != null),
                        ),
                        idempotencyKeyId = reservationId,
                    )
                }
            }
        }
    }

    override fun changePlatformUserLifecycle(
        command: PlatformUserLifecycleCommand,
    ): PlatformUserMutationReceipt {
        return mutate(
            operationType = "platform.user.${command.action.databaseValue}",
            requestPayload = command,
            resourceType = "platform_users",
            replayType = PlatformUserMutationReceipt::class.java,
        ) { reservationId ->
            val actorId = currentPlatformActorId()
            require(actorId != command.platformUserId) {
                "Platform operator cannot change own lifecycle state"
            }
            lockPlatformAdministratorContinuity()
            requirePlatformUser(command.platformUserId)
            requireActorCanManagePlatformUser(actorId, command.platformUserId)
            if (
                command.action.databaseValue != "active" &&
                effectivePlatformAdministrator(command.platformUserId)
            ) {
                requireAnotherEffectivePlatformAdministrator(command.platformUserId)
            }

            val rows = jdbcTemplate.update(
                """
                UPDATE platform_users
                SET status = ?,
                    locked_until = CASE WHEN ? = 'locked' THEN now() + interval '15 minutes' ELSE NULL END,
                    updated_at = now()
                WHERE id = ?
                  AND deleted_at IS NULL
                """.trimIndent(),
                command.action.databaseValue,
                command.action.databaseValue,
                command.platformUserId,
            )

            PlatformUserMutationReceipt(
                platformUserId = command.platformUserId,
                status = command.action.databaseValue,
                changed = rows == 1,
                replayed = false,
            ).also { receipt ->
                if (receipt.changed) {
                    recordPlatformSideEffects(
                        action = "platform.users.${command.action.databaseValue}",
                        resourceType = "platform_users",
                        resourceId = command.platformUserId,
                        payload = mapOf(
                            "platformUserId" to command.platformUserId,
                            "status" to command.action.databaseValue,
                        ),
                        idempotencyKeyId = reservationId,
                    )
                }
            }
        }
    }

    override fun listPlatformRoles(): List<PlatformRoleSummary> {
        return requireNotNull(
            transactionTemplate.execute {
                bindPlatformContext()
                jdbcTemplate.query(
                    """
                    $PLATFORM_ROLE_SELECT
                    $PLATFORM_ROLE_GROUP_BY
                    ORDER BY pr.is_system DESC, pr.name
                    """.trimIndent(),
                    ::mapPlatformRole,
                )
            },
        )
    }

    override fun getPlatformRole(platformRoleId: UUID): PlatformRoleSummary? {
        return requireNotNull(
            transactionTemplate.execute {
                bindPlatformContext()
                jdbcTemplate.query(
                    """
                    $PLATFORM_ROLE_SELECT
                    WHERE pr.id = ?
                    $PLATFORM_ROLE_GROUP_BY
                    """.trimIndent(),
                    ::mapPlatformRole,
                    platformRoleId,
                ).singleOrNull()
            },
        )
    }

    override fun createPlatformRole(
        command: CreatePlatformRoleCommand,
    ): PlatformRoleMutationReceipt {
        return mutate(
            operationType = "platform.role.create",
            requestPayload = command,
            resourceType = "platform_roles",
            replayType = PlatformRoleMutationReceipt::class.java,
        ) { reservationId ->
            val id = UUID.randomUUID()
            requireDelegablePlatformPermissions(command.permissionCodes)
            val permissionIds = requirePlatformPermissions(command.permissionCodes)
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO platform_roles (id, code, name, description, is_system, is_active)
                    VALUES (?, ?, ?, ?, false, true)
                    """.trimIndent(),
                    id,
                    command.code.normalizedPlatformRoleCode(),
                    command.name.normalizedPlatformRoleName(),
                    command.description?.trim()?.takeIf { it.isNotEmpty() },
                )
            } catch (ex: DuplicateKeyException) {
                throw PlatformAdministrationConflictException("Platform role code is already in use")
            }
            replacePlatformRolePermissions(id, permissionIds)

            PlatformRoleMutationReceipt(id, isActive = true, changed = true, replayed = false)
                .also { receipt ->
                    recordPlatformSideEffects(
                        action = "platform.roles.created",
                        resourceType = "platform_roles",
                        resourceId = id,
                        payload = mapOf(
                            "platformRoleId" to id,
                            "code" to command.code.normalizedPlatformRoleCode(),
                            "permissionCodes" to command.permissionCodes.map { it.normalizedCode() },
                        ),
                        idempotencyKeyId = reservationId,
                    )
                }
        }
    }

    override fun updatePlatformRole(
        command: UpdatePlatformRoleCommand,
    ): PlatformRoleMutationReceipt {
        return mutate(
            operationType = "platform.role.update",
            requestPayload = command,
            resourceType = "platform_roles",
            replayType = PlatformRoleMutationReceipt::class.java,
        ) { reservationId ->
            requireMutablePlatformRole(command.platformRoleId)
            requireDelegablePlatformRole(command.platformRoleId)
            val roleName = command.name?.normalizedPlatformRoleName()
            val permissionIds = command.permissionCodes
                ?.also(::requireDelegablePlatformPermissions)
                ?.let(::requirePlatformPermissions)

            val rows = jdbcTemplate.update(
                """
                UPDATE platform_roles
                SET name = COALESCE(?, name),
                    description = COALESCE(?, description),
                    updated_at = now()
                WHERE id = ?
                  AND is_system = false
                """.trimIndent(),
                roleName,
                command.description?.trim()?.takeIf { it.isNotEmpty() },
                command.platformRoleId,
            )
            if (permissionIds != null) {
                replacePlatformRolePermissions(command.platformRoleId, permissionIds)
            }

            PlatformRoleMutationReceipt(
                platformRoleId = command.platformRoleId,
                isActive = platformRoleIsActive(command.platformRoleId),
                changed = rows == 1 || permissionIds != null,
                replayed = false,
            ).also { receipt ->
                if (receipt.changed) {
                    recordPlatformSideEffects(
                        action = "platform.roles.updated",
                        resourceType = "platform_roles",
                        resourceId = command.platformRoleId,
                        payload = mapOf(
                            "platformRoleId" to command.platformRoleId,
                            "permissionsChanged" to (permissionIds != null),
                        ),
                        idempotencyKeyId = reservationId,
                    )
                }
            }
        }
    }

    override fun deactivatePlatformRole(
        command: DeactivatePlatformRoleCommand,
    ): PlatformRoleMutationReceipt {
        return mutate(
            operationType = "platform.role.deactivate",
            requestPayload = command,
            resourceType = "platform_roles",
            replayType = PlatformRoleMutationReceipt::class.java,
        ) { reservationId ->
            requireMutablePlatformRole(command.platformRoleId)
            requireDelegablePlatformRole(command.platformRoleId)
            val rows = jdbcTemplate.update(
                """
                UPDATE platform_roles
                SET is_active = false,
                    updated_at = now()
                WHERE id = ?
                  AND is_system = false
                  AND is_active = true
                """.trimIndent(),
                command.platformRoleId,
            )

            PlatformRoleMutationReceipt(
                platformRoleId = command.platformRoleId,
                isActive = false,
                changed = rows == 1,
                replayed = false,
            ).also { receipt ->
                if (receipt.changed) {
                    recordPlatformSideEffects(
                        action = "platform.roles.deactivated",
                        resourceType = "platform_roles",
                        resourceId = command.platformRoleId,
                        payload = mapOf("platformRoleId" to command.platformRoleId),
                        idempotencyKeyId = reservationId,
                    )
                }
            }
        }
    }

    override fun assignPlatformUserRole(
        command: AssignPlatformUserRoleCommand,
    ): PlatformUserRoleMutationReceipt {
        return mutate(
            operationType = "platform.user.role.assign",
            requestPayload = command,
            resourceType = "platform_user_roles",
            replayType = PlatformUserRoleMutationReceipt::class.java,
        ) { reservationId ->
            val actorId = currentPlatformActorId()
            require(actorId != command.platformUserId) {
                "Platform operator cannot change own role assignments"
            }
            requireActivePlatformUser(command.platformUserId)
            requireActivePlatformRole(command.platformRoleId)
            requireDynamicPlatformRole(command.platformRoleId)
            requireDelegablePlatformRole(command.platformRoleId)
            requireActorCanManagePlatformUser(actorId, command.platformUserId)
            val inserted = jdbcTemplate.update(
                """
                INSERT INTO platform_user_roles (platform_user_id, platform_role_id, assigned_by)
                VALUES (?, ?, ?)
                ON CONFLICT ON CONSTRAINT platform_user_roles_pkey DO NOTHING
                """.trimIndent(),
                command.platformUserId,
                command.platformRoleId,
                actorId,
            ) == 1

            PlatformUserRoleMutationReceipt(
                platformUserId = command.platformUserId,
                platformRoleId = command.platformRoleId,
                assigned = true,
                changed = inserted,
                replayed = false,
            ).also { receipt ->
                if (receipt.changed) {
                    recordPlatformSideEffects(
                        action = "platform.users.role.assigned",
                        resourceType = "platform_user_roles",
                        resourceId = command.platformUserId,
                        payload = mapOf(
                            "platformUserId" to command.platformUserId,
                            "platformRoleId" to command.platformRoleId,
                        ),
                        idempotencyKeyId = reservationId,
                    )
                }
            }
        }
    }

    override fun revokePlatformUserRole(
        command: RevokePlatformUserRoleCommand,
    ): PlatformUserRoleMutationReceipt {
        return mutate(
            operationType = "platform.user.role.revoke",
            requestPayload = command,
            resourceType = "platform_user_roles",
            replayType = PlatformUserRoleMutationReceipt::class.java,
        ) { reservationId ->
            val actorId = currentPlatformActorId()
            require(actorId != command.platformUserId) {
                "Platform operator cannot change own role assignments"
            }
            requirePlatformUser(command.platformUserId)
            requirePlatformRole(command.platformRoleId)
            requireDynamicPlatformRole(command.platformRoleId)
            requireDelegablePlatformRole(command.platformRoleId)
            requireActorCanManagePlatformUser(actorId, command.platformUserId)
            val deleted = jdbcTemplate.update(
                """
                DELETE FROM platform_user_roles
                WHERE platform_user_id = ?
                  AND platform_role_id = ?
                """.trimIndent(),
                command.platformUserId,
                command.platformRoleId,
            ) == 1

            PlatformUserRoleMutationReceipt(
                platformUserId = command.platformUserId,
                platformRoleId = command.platformRoleId,
                assigned = false,
                changed = deleted,
                replayed = false,
            ).also { receipt ->
                if (receipt.changed) {
                    recordPlatformSideEffects(
                        action = "platform.users.role.revoked",
                        resourceType = "platform_user_roles",
                        resourceId = command.platformUserId,
                        payload = mapOf(
                            "platformUserId" to command.platformUserId,
                            "platformRoleId" to command.platformRoleId,
                        ),
                        idempotencyKeyId = reservationId,
                    )
                }
            }
        }
    }

    override fun assignPlatformAdministrator(
        command: AssignPlatformAdministratorCommand,
    ): PlatformUserRoleMutationReceipt {
        return mutate(
            operationType = "platform.administrator.assign",
            requestPayload = command,
            resourceType = "platform_user_roles",
            replayType = PlatformUserRoleMutationReceipt::class.java,
        ) { reservationId ->
            val actorId = currentPlatformActorId()
            requireCurrentPlatformPermission(
                PLATFORM_ADMINISTRATOR_MANAGE_PERMISSION,
                "Platform operator lacks platform administrator management permission",
            )
            require(actorId != command.platformUserId) {
                "Platform operator cannot assign own platform administrator access"
            }
            lockPlatformAdministratorContinuity()
            requireActivePlatformUser(command.platformUserId)
            val platformRoleId = requireSystemPlatformRootRole()
            val inserted = jdbcTemplate.update(
                """
                INSERT INTO platform_user_roles (platform_user_id, platform_role_id, assigned_by)
                VALUES (?, ?, ?)
                ON CONFLICT ON CONSTRAINT platform_user_roles_pkey DO NOTHING
                """.trimIndent(),
                command.platformUserId,
                platformRoleId,
                actorId,
            ) == 1

            PlatformUserRoleMutationReceipt(
                platformUserId = command.platformUserId,
                platformRoleId = platformRoleId,
                assigned = true,
                changed = inserted,
                replayed = false,
            ).also { receipt ->
                if (receipt.changed) {
                    recordPlatformSideEffects(
                        action = "platform.administrator.assigned",
                        resourceType = "platform_user_roles",
                        resourceId = command.platformUserId,
                        payload = mapOf(
                            "platformUserId" to command.platformUserId,
                            "platformRoleId" to platformRoleId,
                        ),
                        idempotencyKeyId = reservationId,
                    )
                }
            }
        }
    }

    override fun revokePlatformAdministrator(
        command: RevokePlatformAdministratorCommand,
    ): PlatformUserRoleMutationReceipt {
        return mutate(
            operationType = "platform.administrator.revoke",
            requestPayload = command,
            resourceType = "platform_user_roles",
            replayType = PlatformUserRoleMutationReceipt::class.java,
        ) { reservationId ->
            val actorId = currentPlatformActorId()
            requireCurrentPlatformPermission(
                PLATFORM_ADMINISTRATOR_MANAGE_PERMISSION,
                "Platform operator lacks platform administrator management permission",
            )
            require(actorId != command.platformUserId) {
                "Platform operator cannot revoke own platform administrator access"
            }
            lockPlatformAdministratorContinuity()
            requirePlatformUser(command.platformUserId)
            val platformRoleId = requireSystemPlatformRootRole()
            val assigned = platformUserRoleAssignmentExists(
                command.platformUserId,
                platformRoleId,
            )
            if (assigned) {
                requireAnotherEffectivePlatformAdministrator(command.platformUserId)
            }
            val deleted = assigned && jdbcTemplate.update(
                """
                DELETE FROM platform_user_roles
                WHERE platform_user_id = ?
                  AND platform_role_id = ?
                """.trimIndent(),
                command.platformUserId,
                platformRoleId,
            ) == 1

            PlatformUserRoleMutationReceipt(
                platformUserId = command.platformUserId,
                platformRoleId = platformRoleId,
                assigned = false,
                changed = deleted,
                replayed = false,
            ).also { receipt ->
                if (receipt.changed) {
                    recordPlatformSideEffects(
                        action = "platform.administrator.revoked",
                        resourceType = "platform_user_roles",
                        resourceId = command.platformUserId,
                        payload = mapOf(
                            "platformUserId" to command.platformUserId,
                            "platformRoleId" to platformRoleId,
                        ),
                        idempotencyKeyId = reservationId,
                    )
                }
            }
        }
    }

    override fun listPlatformPermissions(): List<PlatformPermissionSummary> {
        return requireNotNull(
            transactionTemplate.execute {
                bindPlatformContext()
                jdbcTemplate.query(
                    """
                    SELECT id, code, namespace, description
                    FROM platform_permissions
                    ORDER BY namespace, code
                    """.trimIndent(),
                    ::mapPlatformPermission,
                )
            },
        )
    }

    override fun linkPlatformOidcIdentity(
        command: LinkPlatformOidcIdentityCommand,
    ): PlatformIdentityLinkReceipt {
        return mutate(
            operationType = "platform.user.identity.link",
            requestPayload = command,
            resourceType = "identity_links",
            replayType = PlatformIdentityLinkReceipt::class.java,
        ) { reservationId ->
            val actorId = currentPlatformActorId()
            require(actorId != command.platformUserId) {
                "Platform operator cannot link own identity"
            }
            requireActivePlatformUser(command.platformUserId)
            requireActorCanManagePlatformUser(actorId, command.platformUserId)
            val issuer = command.issuer.normalizedRequired("issuer")
            val subject = command.subject.normalizedRequired("subject")
            val existing = activeIdentityFor(issuer, subject)
            if (existing != null) {
                if (existing.platformUserId != command.platformUserId) {
                    throw PlatformAdministrationConflictException("OIDC identity is already linked")
                }
                PlatformIdentityLinkReceipt(
                    platformUserId = command.platformUserId,
                    identityLinkId = existing.identityLinkId,
                    revokedAt = null,
                    changed = false,
                    replayed = false,
                )
            } else {
                val id = UUID.randomUUID()
                jdbcTemplate.update(
                    """
                    INSERT INTO identity_links (
                        id,
                        identity_mode,
                        provider,
                        issuer,
                        subject,
                        platform_user_id,
                        email,
                        linked_by_platform_user_id
                    )
                    VALUES (?, 'platform', 'oidc', ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    id,
                    issuer,
                    subject,
                    command.platformUserId,
                    command.email?.normalizedEmail(),
                    actorId,
                )
                PlatformIdentityLinkReceipt(
                    platformUserId = command.platformUserId,
                    identityLinkId = id,
                    revokedAt = null,
                    changed = true,
                    replayed = false,
                ).also { receipt ->
                    recordPlatformSideEffects(
                        action = "platform.users.identity.linked",
                        resourceType = "identity_links",
                        resourceId = id,
                        payload = mapOf(
                            "platformUserId" to command.platformUserId,
                            "identityLinkId" to id,
                            "issuer" to issuer,
                        ),
                        idempotencyKeyId = reservationId,
                    )
                }
            }
        }
    }

    override fun revokePlatformOidcIdentity(
        command: RevokePlatformOidcIdentityCommand,
    ): PlatformIdentityLinkReceipt {
        return mutate(
            operationType = "platform.user.identity.revoke",
            requestPayload = command,
            resourceType = "identity_links",
            replayType = PlatformIdentityLinkReceipt::class.java,
        ) { reservationId ->
            val actorId = currentPlatformActorId()
            require(actorId != command.platformUserId) {
                "Platform operator cannot revoke own identity link"
            }
            lockPlatformAdministratorContinuity()
            requirePlatformUser(command.platformUserId)
            requireActorCanManagePlatformUser(actorId, command.platformUserId)
            if (
                activePlatformIdentityLink(command.platformUserId, command.identityLinkId) &&
                activePlatformIdentityLinkCount(command.platformUserId) == 1 &&
                effectivePlatformAdministrator(command.platformUserId)
            ) {
                requireAnotherEffectivePlatformAdministrator(command.platformUserId)
            }
            val rows = jdbcTemplate.update(
                """
                UPDATE identity_links
                SET revoked_at = now(),
                    updated_at = now()
                WHERE id = ?
                  AND identity_mode = 'platform'
                  AND platform_user_id = ?
                  AND revoked_at IS NULL
                """.trimIndent(),
                command.identityLinkId,
                command.platformUserId,
            )
            if (rows == 0 && !identityLinkExists(command.platformUserId, command.identityLinkId)) {
                throw PlatformAdministrationNotFoundException("Platform OIDC identity link was not found")
            }
            val revokedAt = identityLinkRevokedAt(command.identityLinkId)
            PlatformIdentityLinkReceipt(
                platformUserId = command.platformUserId,
                identityLinkId = command.identityLinkId,
                revokedAt = revokedAt,
                changed = rows == 1,
                replayed = false,
            ).also { receipt ->
                if (receipt.changed) {
                    recordPlatformSideEffects(
                        action = "platform.users.identity.revoked",
                        resourceType = "identity_links",
                        resourceId = command.identityLinkId,
                        payload = mapOf(
                            "platformUserId" to command.platformUserId,
                            "identityLinkId" to command.identityLinkId,
                        ),
                        idempotencyKeyId = reservationId,
                    )
                }
            }
        }
    }

    override fun provisionTenantAdministrator(
        command: ProvisionTenantAdministratorCommand,
    ): TenantAdministratorProvisioningReceipt {
        return mutate(
            operationType = "platform.tenant.administrator.provision",
            requestPayload = command,
            resourceType = "users",
            replayType = TenantAdministratorProvisioningReceipt::class.java,
        ) { reservationId ->
            requireSupportBreakGlassAccess(
                tenantId = command.tenantId,
                actionCode = "platform.security.manage",
                operation = "platform.tenants.administrator.provision",
            )
            val row = jdbcTemplate.query(
                """
                SELECT user_id, tenant_role_id, identity_link_id, changed
                FROM provision_tenant_administrator(?, ?, ?, ?, ?)
                """.trimIndent(),
                { rs, _ ->
                    TenantAdministratorProvisioningReceipt(
                        tenantId = command.tenantId,
                        tenantUserId = rs.getObject("user_id", UUID::class.java),
                        tenantRoleId = rs.getObject("tenant_role_id", UUID::class.java),
                        identityLinkId = rs.getObject("identity_link_id", UUID::class.java),
                        changed = rs.getBoolean("changed"),
                        replayed = false,
                    )
                },
                command.tenantId,
                command.fullName.normalizedRequired("fullName"),
                command.email.normalizedEmail(),
                command.issuer.normalizedRequired("issuer"),
                command.subject.normalizedRequired("subject"),
            ).single()

            if (row.changed) {
                recordPlatformSideEffects(
                    action = "platform.tenants.administrator.provisioned",
                    resourceType = "users",
                    resourceId = row.tenantUserId,
                    payload = mapOf(
                        "tenantId" to row.tenantId,
                        "tenantUserId" to row.tenantUserId,
                        "tenantRoleId" to row.tenantRoleId,
                        "identityLinkId" to row.identityLinkId,
                    ),
                    idempotencyKeyId = reservationId,
                )
            }
            row
        }
    }

    override fun verifyTenantBusinessProfile(
        command: VerifyTenantBusinessProfileCommand,
    ): TenantProfileVerificationReceipt {
        return mutate(
            operationType = "platform.tenant.profile.verify",
            requestPayload = command,
            resourceType = "tenant_profiles",
            replayType = TenantProfileVerificationReceipt::class.java,
        ) { reservationId ->
            requireSupportBreakGlassAccess(
                tenantId = command.tenantId,
                actionCode = "platform.tenants.verify",
                operation = "platform.tenants.profile.verify",
            )
            val row = jdbcTemplate.query(
                """
                SELECT verification_status, changed
                FROM verify_tenant_business_profile(?)
                """.trimIndent(),
                { rs, _ ->
                    TenantProfileVerificationReceipt(
                        tenantId = command.tenantId,
                        verificationStatus = rs.getString("verification_status"),
                        changed = rs.getBoolean("changed"),
                        replayed = false,
                    )
                },
                command.tenantId,
            ).single()

            if (row.changed) {
                recordPlatformSideEffects(
                    action = "platform.tenants.profile.verified",
                    resourceType = "tenant_profiles",
                    resourceId = row.tenantId,
                    payload = mapOf(
                        "tenantId" to row.tenantId,
                        "verificationStatus" to row.verificationStatus,
                    ),
                    idempotencyKeyId = reservationId,
                )
            }
            row
        }
    }

    private fun <T : Any> mutate(
        operationType: String,
        requestPayload: Any,
        resourceType: String,
        replayType: Class<T>,
        block: (UUID) -> T,
    ): T {
        return requireNotNull(
            transactionTemplate.execute {
                bindPlatformContext()
                val reservation = idempotencyPort.reserve(
                    IdempotencyCommand(
                        operationType = operationType,
                        requestPayload = requestPayload,
                        resourceType = resourceType,
                    ),
                )

                when (reservation) {
                    is IdempotencyReservation.Started -> {
                        val receipt = block(reservation.recordId)
                        idempotencyPort.markSucceeded(
                            recordId = reservation.recordId,
                            responseCode = 200,
                            responseBody = receipt,
                            resourceId = resourceId(receipt),
                        )
                        meterRegistry.counter(
                            "peak.platform.admin.command",
                            "operation", operationType,
                            "result", "succeeded",
                        ).increment()
                        receipt
                    }

                    is IdempotencyReservation.Replay -> {
                        if (reservation.responseBody.isNullOrBlank()) {
                            throw PlatformAdministrationConflictException(
                                "Platform administration replay does not contain a stored response body",
                            )
                        }
                        objectMapper.readValue(reservation.responseBody, replayType)
                            .withReplayFlag()
                    }

                    is IdempotencyReservation.InProgress -> {
                        meterRegistry.counter(
                            "peak.platform.admin.command",
                            "operation", operationType,
                            "result", "in_progress",
                        ).increment()
                        throw PlatformAdministrationInProgressException(
                            "Platform administration command is already being processed for this idempotency key",
                        )
                    }

                    is IdempotencyReservation.Conflict -> {
                        meterRegistry.counter(
                            "peak.platform.admin.command",
                            "operation", operationType,
                            "result", "conflict",
                        ).increment()
                        throw PlatformAdministrationConflictException(
                            "Idempotency key was already used for a different platform administration request",
                        )
                    }
                }
            },
        )
    }

    private fun resourceId(receipt: Any): UUID? {
        return when (receipt) {
            is PlatformUserMutationReceipt -> receipt.platformUserId
            is PlatformRoleMutationReceipt -> receipt.platformRoleId
            is PlatformUserRoleMutationReceipt -> receipt.platformUserId
            is PlatformIdentityLinkReceipt -> receipt.identityLinkId
            is TenantAdministratorProvisioningReceipt -> receipt.tenantUserId
            is TenantProfileVerificationReceipt -> receipt.tenantId
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> T.withReplayFlag(): T {
        return when (this) {
            is PlatformUserMutationReceipt -> copy(replayed = true) as T
            is PlatformRoleMutationReceipt -> copy(replayed = true) as T
            is PlatformUserRoleMutationReceipt -> copy(replayed = true) as T
            is PlatformIdentityLinkReceipt -> copy(replayed = true) as T
            is TenantAdministratorProvisioningReceipt -> copy(replayed = true) as T
            is TenantProfileVerificationReceipt -> copy(replayed = true) as T
            else -> this
        }
    }

    private fun bindPlatformContext() {
        requirePlatformIdentity()
        databaseSessionContext.bind(requestContextHolder.current().identity)
    }

    private fun currentPlatformActorId(): UUID {
        return requirePlatformIdentity()
    }

    private fun requirePlatformIdentity(): UUID {
        return when (val identity = requestContextHolder.current().identity) {
            is RequestIdentity.Platform -> identity.platformUserId
            is RequestIdentity.Support -> identity.platformUserId
            else -> throw IllegalStateException("Platform identity is required")
        }
    }

    private fun requireCurrentPlatformPermission(permissionCode: String, denialMessage: String) {
        val allowed = jdbcTemplate.queryForObject(
            "SELECT platform_user_has_permission(?, ?)",
            Boolean::class.java,
            currentPlatformActorId(),
            permissionCode,
        ) == true
        require(allowed) {
            denialMessage
        }
    }

    private fun lockPlatformAdministratorContinuity() {
        jdbcTemplate.execute(
            "SELECT pg_advisory_xact_lock(hashtext('$PLATFORM_ADMINISTRATOR_LOCK_KEY'))",
        )
    }

    private fun requireSupportBreakGlassAccess(
        tenantId: UUID,
        actionCode: String,
        operation: String,
    ) {
        supportTenantAccessPort.requireAuthorized(
            SupportTenantAccessRequest(
                tenantId = tenantId,
                permissionCode = actionCode,
                operation = operation,
            ),
        )
    }

    private fun requirePlatformUser(platformUserId: UUID) {
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM platform_users
                WHERE id = ?
                  AND deleted_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            platformUserId,
        ) == true
        if (!exists) {
            throw PlatformAdministrationNotFoundException("Platform user was not found")
        }
    }

    private fun requireActivePlatformUser(platformUserId: UUID) {
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM platform_users
                WHERE id = ?
                  AND deleted_at IS NULL
                  AND status = 'active'
                  AND (locked_until IS NULL OR locked_until <= now())
            )
            """.trimIndent(),
            Boolean::class.java,
            platformUserId,
        ) == true
        if (!exists) {
            throw PlatformAdministrationNotFoundException("Active platform user was not found")
        }
    }

    private fun requireActorCanManagePlatformUser(
        actorUserId: UUID,
        targetUserId: UUID,
    ) {
        val missingPermissions = targetPlatformPermissionCodes(targetUserId)
            .filterNot { permissionCode ->
                jdbcTemplate.queryForObject(
                    "SELECT platform_user_has_permission(?, ?)",
                    Boolean::class.java,
                    actorUserId,
                    permissionCode,
                ) == true
            }
        require(missingPermissions.isEmpty()) {
            "Platform operator cannot manage a user with permissions the actor does not hold"
        }
    }

    private fun targetPlatformPermissionCodes(platformUserId: UUID): List<String> {
        return jdbcTemplate.queryForList(
            """
            SELECT DISTINCT pp.code
            FROM platform_user_roles pur
            JOIN platform_roles pr
              ON pr.id = pur.platform_role_id
            JOIN platform_role_permissions prp
              ON prp.platform_role_id = pr.id
            JOIN platform_permissions pp
              ON pp.id = prp.platform_permission_id
            WHERE pur.platform_user_id = ?
              AND pr.is_active = true
            ORDER BY pp.code
            """.trimIndent(),
            String::class.java,
            platformUserId,
        ).filterNotNull()
    }

    private fun requirePlatformRole(platformRoleId: UUID) {
        val exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM platform_roles WHERE id = ?)",
            Boolean::class.java,
            platformRoleId,
        ) == true
        if (!exists) {
            throw PlatformAdministrationNotFoundException("Platform role was not found")
        }
    }

    private fun requireDynamicPlatformRole(platformRoleId: UUID) {
        val isSystem = jdbcTemplate.queryForObject(
            "SELECT is_system FROM platform_roles WHERE id = ?",
            Boolean::class.java,
            platformRoleId,
        ) ?: throw PlatformAdministrationNotFoundException("Platform role was not found")
        require(!isSystem) {
            "System platform role assignments require dedicated administrator routes"
        }
    }

    private fun requireSystemPlatformRootRole(): UUID {
        return jdbcTemplate.query(
            """
            SELECT id
            FROM platform_roles
            WHERE code = ?
              AND is_system = true
              AND is_active = true
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            PLATFORM_ROOT_ROLE_CODE,
        ).singleOrNull()
            ?: throw PlatformAdministrationNotFoundException(
                "Active system Platform Root role was not found",
            )
    }

    private fun platformUserRoleAssignmentExists(
        platformUserId: UUID,
        platformRoleId: UUID,
    ): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM platform_user_roles
                WHERE platform_user_id = ?
                  AND platform_role_id = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            platformUserId,
            platformRoleId,
        ) == true
    }

    private fun effectivePlatformAdministrator(platformUserId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM platform_user_roles pur
                JOIN platform_roles pr
                  ON pr.id = pur.platform_role_id
                JOIN platform_users pu
                  ON pu.id = pur.platform_user_id
                WHERE pur.platform_user_id = ?
                  AND pr.code = ?
                  AND pr.is_system = true
                  AND pr.is_active = true
                  AND pu.status = 'active'
                  AND pu.deleted_at IS NULL
                  AND (pu.locked_until IS NULL OR pu.locked_until <= now())
                  AND EXISTS (
                      SELECT 1
                      FROM identity_links il
                      WHERE il.platform_user_id = pu.id
                        AND il.identity_mode = 'platform'
                        AND il.revoked_at IS NULL
                  )
            )
            """.trimIndent(),
            Boolean::class.java,
            platformUserId,
            PLATFORM_ROOT_ROLE_CODE,
        ) == true
    }

    private fun requireAnotherEffectivePlatformAdministrator(excludedPlatformUserId: UUID) {
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM platform_user_roles pur
                JOIN platform_roles pr
                  ON pr.id = pur.platform_role_id
                JOIN platform_users pu
                  ON pu.id = pur.platform_user_id
                WHERE pur.platform_user_id <> ?
                  AND pr.code = ?
                  AND pr.is_system = true
                  AND pr.is_active = true
                  AND pu.status = 'active'
                  AND pu.deleted_at IS NULL
                  AND (pu.locked_until IS NULL OR pu.locked_until <= now())
                  AND EXISTS (
                      SELECT 1
                      FROM identity_links il
                      WHERE il.platform_user_id = pu.id
                        AND il.identity_mode = 'platform'
                        AND il.revoked_at IS NULL
                  )
            )
            """.trimIndent(),
            Boolean::class.java,
            excludedPlatformUserId,
            PLATFORM_ROOT_ROLE_CODE,
        ) == true
        require(exists) {
            "Platform administrator access cannot be removed without another effective administrator"
        }
    }

    private fun requireActivePlatformRole(platformRoleId: UUID) {
        val exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM platform_roles WHERE id = ? AND is_active = true)",
            Boolean::class.java,
            platformRoleId,
        ) == true
        if (!exists) {
            throw PlatformAdministrationNotFoundException("Active platform role was not found")
        }
    }

    private fun requireMutablePlatformRole(platformRoleId: UUID) {
        val row = jdbcTemplate.query(
            """
            SELECT is_system
            FROM platform_roles
            WHERE id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getBoolean("is_system") },
            platformRoleId,
        ).singleOrNull() ?: throw PlatformAdministrationNotFoundException("Platform role was not found")

        require(!row) {
            "System platform roles cannot be modified"
        }
        val assignedToActor = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM platform_user_roles
                WHERE platform_user_id = ?
                  AND platform_role_id = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            currentPlatformActorId(),
            platformRoleId,
        ) == true
        require(!assignedToActor) {
            "Platform operator cannot modify a role assigned to self"
        }
    }

    private fun requireDelegablePlatformRole(platformRoleId: UUID) {
        val permissionCodes = jdbcTemplate.queryForList(
            """
            SELECT pp.code
            FROM platform_role_permissions prp
            JOIN platform_permissions pp
              ON pp.id = prp.platform_permission_id
            WHERE prp.platform_role_id = ?
            ORDER BY pp.code
            """.trimIndent(),
            String::class.java,
            platformRoleId,
        ).filterNotNull()
        requireDelegablePlatformPermissions(permissionCodes)
    }

    private fun requireDelegablePlatformPermissions(permissionCodes: List<String>) {
        val actorId = currentPlatformActorId()
        val unauthorized = permissionCodes
            .map { it.normalizedCode() }
            .distinct()
            .filterNot { permissionCode ->
                jdbcTemplate.queryForObject(
                    "SELECT platform_user_has_permission(?, ?)",
                    Boolean::class.java,
                    actorId,
                    permissionCode,
                ) == true
            }
        require(unauthorized.isEmpty()) {
            "Platform roles cannot include permissions the actor does not hold"
        }
    }

    private fun requirePlatformPermissions(codes: List<String>): List<UUID> {
        val normalizedCodes = codes.map { it.normalizedCode() }.distinct()
        require(normalizedCodes.isNotEmpty()) {
            "At least one platform permission is required"
        }
        val placeholders = normalizedCodes.joinToString(", ") { "?" }
        val permissionIds = jdbcTemplate.query(
            """
            SELECT id
            FROM platform_permissions
            WHERE code IN ($placeholders)
            ORDER BY code
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            *normalizedCodes.toTypedArray(),
        )
        if (permissionIds.size != normalizedCodes.size) {
            throw PlatformAdministrationNotFoundException("One or more platform permissions were not found")
        }
        return permissionIds
    }

    private fun replacePlatformRolePermissions(
        platformRoleId: UUID,
        permissionIds: List<UUID>,
    ) {
        jdbcTemplate.update(
            "DELETE FROM platform_role_permissions WHERE platform_role_id = ?",
            platformRoleId,
        )
        permissionIds.forEach { permissionId ->
            jdbcTemplate.update(
                """
                INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
                VALUES (?, ?)
                ON CONFLICT ON CONSTRAINT platform_role_permissions_pkey DO NOTHING
                """.trimIndent(),
                platformRoleId,
                permissionId,
            )
        }
    }

    private fun platformUserStatus(platformUserId: UUID): String {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM platform_users WHERE id = ?",
            String::class.java,
            platformUserId,
        ) ?: throw PlatformAdministrationNotFoundException("Platform user was not found")
    }

    private fun platformRoleIsActive(platformRoleId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            "SELECT is_active FROM platform_roles WHERE id = ?",
            Boolean::class.java,
            platformRoleId,
        ) == true
    }

    private fun activeIdentityFor(issuer: String, subject: String): PlatformIdentitySnapshot? {
        return jdbcTemplate.query(
            """
            SELECT id, platform_user_id
            FROM identity_links
            WHERE provider = 'oidc'
              AND issuer = ?
              AND subject = ?
              AND revoked_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                PlatformIdentitySnapshot(
                    identityLinkId = rs.getObject("id", UUID::class.java),
                    platformUserId = rs.getObject("platform_user_id", UUID::class.java),
                )
            },
            issuer,
            subject,
        ).singleOrNull()
    }

    private fun identityLinkExists(platformUserId: UUID, identityLinkId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM identity_links
                WHERE id = ?
                  AND identity_mode = 'platform'
                  AND platform_user_id = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            identityLinkId,
            platformUserId,
        ) == true
    }

    private fun activePlatformIdentityLink(
        platformUserId: UUID,
        identityLinkId: UUID,
    ): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM identity_links
                WHERE id = ?
                  AND identity_mode = 'platform'
                  AND platform_user_id = ?
                  AND revoked_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            identityLinkId,
            platformUserId,
        ) == true
    }

    private fun activePlatformIdentityLinkCount(platformUserId: UUID): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM identity_links
                WHERE identity_mode = 'platform'
                  AND platform_user_id = ?
                  AND revoked_at IS NULL
                """.trimIndent(),
                Int::class.java,
                platformUserId,
            ),
        )
    }

    private fun identityLinkRevokedAt(identityLinkId: UUID): Instant? {
        return jdbcTemplate.queryForObject(
            "SELECT revoked_at FROM identity_links WHERE id = ?",
            Timestamp::class.java,
            identityLinkId,
        )?.toInstant()
    }

    private fun recordPlatformSideEffects(
        action: String,
        resourceType: String,
        resourceId: UUID,
        payload: Map<String, Any?>,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordPlatformEvent(
            PlatformAuditEvent(
                action = action,
                resource = AuditResource(resourceType, resourceId),
                after = payload,
            ),
        )

        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = resourceType,
                aggregateId = resourceId,
                eventType = action,
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                idempotencyKeyId = idempotencyKeyId,
                priority = 3,
            ),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapPlatformUser(rs: ResultSet, rowNumber: Int): PlatformUserSummary {
        return PlatformUserSummary(
            platformUserId = rs.getObject("id", UUID::class.java),
            fullName = rs.getString("full_name"),
            email = rs.getString("email"),
            status = rs.getString("status"),
            lockedUntil = rs.getTimestamp("locked_until")?.toInstant(),
            roleCodes = rs.getArray("role_codes").toStringList(),
            activeIdentityLinks = rs.getInt("active_identity_links"),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapPlatformRole(rs: ResultSet, rowNumber: Int): PlatformRoleSummary {
        return PlatformRoleSummary(
            platformRoleId = rs.getObject("id", UUID::class.java),
            code = rs.getString("code"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            isSystem = rs.getBoolean("is_system"),
            isActive = rs.getBoolean("is_active"),
            permissionCodes = rs.getArray("permission_codes").toStringList(),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapPlatformPermission(
        rs: ResultSet,
        rowNumber: Int,
    ): PlatformPermissionSummary {
        return PlatformPermissionSummary(
            platformPermissionId = rs.getObject("id", UUID::class.java),
            code = rs.getString("code"),
            namespace = rs.getString("namespace"),
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

    private fun String.normalizedEmail(): String {
        val value = normalizedRequired("email").lowercase()
        require(value.contains("@")) {
            "email must be valid"
        }
        return value
    }

    private fun String.normalizedCode(): String {
        return normalizedRequired("code").lowercase()
    }

    private fun String.normalizedPlatformRoleCode(): String {
        return normalizedCode().also { roleCode ->
            require(roleCode != PLATFORM_ROOT_ROLE_CODE) {
                "platform_root is reserved for the system Platform Root role"
            }
        }
    }

    private fun String.normalizedPlatformRoleName(): String {
        return normalizedRequired("name").also { roleName ->
            require(!roleName.equals(PLATFORM_ROOT_ROLE_NAME, ignoreCase = true)) {
                "Platform Root is reserved for the system platform role"
            }
        }
    }

    private fun canonicalInitialStatus(status: String): String {
        val normalized = status.trim().lowercase()
        require(normalized == "invited" || normalized == "active") {
            "Platform user initial status must be invited or active"
        }
        return normalized
    }

    private data class PlatformIdentitySnapshot(
        val identityLinkId: UUID,
        val platformUserId: UUID?,
    )

    private companion object {
        private const val PLATFORM_ADMINISTRATOR_LOCK_KEY = "peak.platform.administrator.continuity"
        private const val PLATFORM_ADMINISTRATOR_MANAGE_PERMISSION = "platform.administrators.manage"
        private const val PLATFORM_ROLE_VIEW_PERMISSION = "platform.roles.view"
        private const val PLATFORM_ROOT_ROLE_CODE = "platform_root"
        private const val PLATFORM_ROOT_ROLE_NAME = "Platform Root"

        val PLATFORM_USER_SELECT = """
            SELECT
                pu.id,
                pu.full_name,
                pu.email,
                pu.status,
                pu.locked_until,
                COALESCE(
                    array_agg(pr.code ORDER BY pr.code)
                        FILTER (WHERE pr.code IS NOT NULL),
                    ARRAY[]::text[]
                ) AS role_codes,
                COUNT(DISTINCT il.id)::integer AS active_identity_links
            FROM platform_users pu
            LEFT JOIN platform_user_roles pur
                ON pur.platform_user_id = pu.id
            LEFT JOIN platform_roles pr
                ON pr.id = pur.platform_role_id
            LEFT JOIN identity_links il
                ON il.platform_user_id = pu.id
               AND il.identity_mode = 'platform'
               AND il.revoked_at IS NULL
        """.trimIndent()

        val PLATFORM_USER_GROUP_BY = """
            GROUP BY pu.id, pu.full_name, pu.email, pu.status, pu.locked_until, pu.created_at
        """.trimIndent()

        val PLATFORM_ROLE_SELECT = """
            SELECT
                pr.id,
                pr.code,
                pr.name,
                pr.description,
                pr.is_system,
                pr.is_active,
                COALESCE(
                    array_agg(pp.code ORDER BY pp.code)
                        FILTER (WHERE pp.code IS NOT NULL),
                    ARRAY[]::text[]
                ) AS permission_codes
            FROM platform_roles pr
            LEFT JOIN platform_role_permissions prp
                ON prp.platform_role_id = pr.id
            LEFT JOIN platform_permissions pp
                ON pp.id = prp.platform_permission_id
        """.trimIndent()

        val PLATFORM_ROLE_GROUP_BY = """
            GROUP BY pr.id, pr.code, pr.name, pr.description, pr.is_system, pr.is_active
        """.trimIndent()
    }
}
