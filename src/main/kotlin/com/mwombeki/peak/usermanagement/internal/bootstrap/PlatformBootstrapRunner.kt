package com.mwombeki.peak.usermanagement.internal.bootstrap

import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
@ConditionalOnProperty(
    prefix = "peak.bootstrap.platform",
    name = ["enabled"],
    havingValue = "true",
)
class PlatformBootstrapRunner(
    private val properties: PlatformBootstrapProperties,
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val fullName = properties.fullName.required("PEAK_PLATFORM_BOOTSTRAP_FULL_NAME")
        val email = properties.email.required("PEAK_PLATFORM_BOOTSTRAP_EMAIL").lowercase()
        val issuer = properties.issuer.required("PEAK_PLATFORM_BOOTSTRAP_ISSUER")
        val subject = properties.subject.required("PEAK_PLATFORM_BOOTSTRAP_SUBJECT")

        val result = requireNotNull(
            transactionTemplate.execute {
                bootstrap(fullName, email, issuer, subject, properties.recoveryEnabled)
            },
        )
        logger.info(
            "Platform bootstrap completed platformUserId={} recovery={} changed={} correlationId={}",
            result.platformUserId,
            properties.recoveryEnabled,
            result.changed,
            result.correlationId,
        )
    }

    private fun bootstrap(
        fullName: String,
        email: String,
        issuer: String,
        subject: String,
        recoveryEnabled: Boolean,
    ): BootstrapResult {
        jdbcTemplate.execute(
            "SELECT pg_advisory_xact_lock(hashtext('peak.platform.administrator.continuity'))",
        )
        val platformUserCount = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform_users WHERE deleted_at IS NULL",
                Int::class.java,
            ),
        )
        val effectiveRootCount = requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(DISTINCT pu.id)
                FROM platform_users pu
                JOIN platform_user_roles pur
                  ON pur.platform_user_id = pu.id
                JOIN platform_roles pr
                  ON pr.id = pur.platform_role_id
                WHERE pr.code = 'platform_root'
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
                """.trimIndent(),
                Int::class.java,
            ),
        )
        if (recoveryEnabled) {
            check(effectiveRootCount == 0) {
                "Platform recovery is closed while an effective platform root can sign in"
            }
        }
        var platformUserId = jdbcTemplate.query(
            """
            SELECT id
            FROM platform_users
            WHERE lower(email) = ?
              AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            email,
        ).singleOrNull()
        var changed = false
        val createdUser = platformUserId == null

        if (platformUserCount > 0 && createdUser && !recoveryEnabled) {
            error(
                "Platform bootstrap is closed because a different platform user already exists. " +
                        "Use authenticated platform administration or the documented recovery procedure.",
            )
        }

        if (platformUserId == null) {
            platformUserId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO platform_users (
                    id,
                    full_name,
                    email,
                    status,
                    must_change_pw,
                    mfa_enabled
                )
                VALUES (?, ?, ?, 'active', false, true)
                """.trimIndent(),
                platformUserId,
                fullName,
                email,
            )
            changed = true
        } else if (recoveryEnabled) {
            jdbcTemplate.update(
                """
                UPDATE platform_users
                SET full_name = ?,
                    status = 'active',
                    locked_until = NULL,
                    failed_attempts = 0,
                    must_change_pw = false,
                    mfa_enabled = true,
                    updated_at = now()
                WHERE id = ?
                """.trimIndent(),
                fullName,
                platformUserId,
            )
            changed = true
        } else {
            val active = jdbcTemplate.queryForObject(
                """
                SELECT status = 'active'
                       AND (locked_until IS NULL OR locked_until <= now())
                FROM platform_users
                WHERE id = ?
                """.trimIndent(),
                Boolean::class.java,
                platformUserId,
            ) == true
            check(active) {
                "Existing bootstrap platform user is not active"
            }
        }

        var roleId = jdbcTemplate.query(
            """
            SELECT id, is_system
            FROM platform_roles
            WHERE code = 'platform_root'
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                rs.getObject("id", UUID::class.java) to rs.getBoolean("is_system")
            },
        ).singleOrNull()?.also { (_, isSystem) ->
            check(isSystem) {
                "The platform_root role code is occupied by a mutable role"
            }
        }?.first

        if (roleId == null) {
            roleId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO platform_roles (
                    id,
                    name,
                    code,
                    description,
                    is_system,
                    is_active
                )
                VALUES (
                    ?,
                    'Platform Root',
                    'platform_root',
                    'Immutable initial platform operator role',
                    true,
                    true
                )
                """.trimIndent(),
                roleId,
            )
            changed = true
        }

        jdbcTemplate.update(
            """
            INSERT INTO platform_role_permissions (
                platform_role_id,
                platform_permission_id
            )
            SELECT ?, pp.id
            FROM platform_permissions pp
            ON CONFLICT ON CONSTRAINT platform_role_permissions_pkey DO NOTHING
            """.trimIndent(),
            roleId,
        )
        val assignmentInserted = jdbcTemplate.update(
            """
            INSERT INTO platform_user_roles (
                platform_user_id,
                platform_role_id,
                assigned_by
            )
            VALUES (?, ?, ?)
            ON CONFLICT ON CONSTRAINT platform_user_roles_pkey DO NOTHING
            """.trimIndent(),
            platformUserId,
            roleId,
            platformUserId,
        )
        changed = changed || assignmentInserted > 0

        val identity = jdbcTemplate.query(
            """
            SELECT id, platform_user_id
            FROM identity_links
            WHERE issuer = ?
              AND subject = ?
              AND revoked_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                rs.getObject("id", UUID::class.java) to
                        rs.getObject("platform_user_id", UUID::class.java)
            },
            issuer,
            subject,
        ).singleOrNull()

        if (identity != null) {
            check(identity.second == platformUserId) {
                "Bootstrap OIDC identity is already linked to another user"
            }
        } else {
            check(createdUser || recoveryEnabled) {
                "Platform bootstrap is already complete; identity replacement requires authenticated administration"
            }
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
                UUID.randomUUID(),
                issuer,
                subject,
                platformUserId,
                email,
                platformUserId,
            )
            changed = true
        }

        val correlationId = if (changed) {
            val eventName = if (recoveryEnabled) {
                "platform.recovery.completed"
            } else {
                "platform.bootstrap.completed"
            }
            val value = if (recoveryEnabled) {
                "platform-recovery-${UUID.randomUUID()}"
            } else {
                "platform-bootstrap-${UUID.randomUUID()}"
            }
            jdbcTemplate.update(
                """
                INSERT INTO platform_audit_logs (
                    platform_user_id,
                    action,
                    entity_type,
                    entity_id,
                    new_values,
                    correlation_id
                )
                VALUES (
                    ?,
                    ?,
                    'platform_users',
                    ?,
                    jsonb_build_object(
                        'platformUserId', ?::text,
                        'roleCode', 'platform_root',
                        'issuer', ?
                    ),
                    ?
                )
                """.trimIndent(),
                platformUserId,
                eventName,
                platformUserId,
                platformUserId.toString(),
                issuer,
                value,
            )
            value
        } else {
            null
        }

        return BootstrapResult(platformUserId, changed, correlationId)
    }

    private fun String?.required(environmentName: String): String {
        return this?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("$environmentName is required for platform bootstrap")
    }

    private data class BootstrapResult(
        val platformUserId: UUID,
        val changed: Boolean,
        val correlationId: String?,
    )

    private companion object {
        private val logger = LoggerFactory.getLogger(PlatformBootstrapRunner::class.java)
    }
}
