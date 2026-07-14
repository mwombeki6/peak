package com.mwombeki.peak.usermanagement.internal.bootstrap

import com.mwombeki.peak.TestcontainersConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PlatformBootstrapRunnerIntegrationTests {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun recoversPlatformRootOnlyWhenNoEffectiveAdministratorCanSignIn() {
        val rootRoleId = resetSystemPlatformRootRoleAssignments()
        val targetUserId = UUID.randomUUID()
        val email = "recovery-${targetUserId.toString().take(8)}@example.com"
        val issuer = "https://keycloak.example.com/realms/peak"
        val subject = "recovery-${UUID.randomUUID()}"
        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, full_name, email, status, locked_until)
            VALUES (?, 'Disabled Recovery Operator', ?, 'disabled', now() + interval '1 hour')
            """.trimIndent(),
            targetUserId,
            email,
        )

        recoveryRunner(email, issuer, subject).run(DefaultApplicationArguments())

        assertEquals(
            "active",
            jdbcTemplate.queryForObject(
                "SELECT status FROM platform_users WHERE id = ?",
                String::class.java,
                targetUserId,
            ),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM platform_user_roles
                WHERE platform_user_id = ?
                  AND platform_role_id = ?
                """.trimIndent(),
                Int::class.java,
                targetUserId,
                rootRoleId,
            ),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM identity_links
                WHERE platform_user_id = ?
                  AND identity_mode = 'platform'
                  AND issuer = ?
                  AND subject = ?
                  AND revoked_at IS NULL
                """.trimIndent(),
                Int::class.java,
                targetUserId,
                issuer,
                subject,
            ),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM platform_audit_logs
                WHERE platform_user_id = ?
                  AND action = 'platform.recovery.completed'
                """.trimIndent(),
                Int::class.java,
                targetUserId,
            ),
        )
    }

    @Test
    fun rejectsRecoveryWhileAnEffectivePlatformRootCanSignIn() {
        val rootRoleId = resetSystemPlatformRootRoleAssignments()
        val effectiveRootId = insertActivePlatformUser()
        jdbcTemplate.update(
            """
            INSERT INTO platform_user_roles (platform_user_id, platform_role_id, assigned_by)
            VALUES (?, ?, ?)
            """.trimIndent(),
            effectiveRootId,
            rootRoleId,
            effectiveRootId,
        )
        insertPlatformIdentity(effectiveRootId)

        val error = assertFailsWith<IllegalStateException> {
            recoveryRunner(
                email = "blocked-recovery-${UUID.randomUUID()}@example.com",
                issuer = "https://keycloak.example.com/realms/peak",
                subject = "blocked-recovery-${UUID.randomUUID()}",
            ).run(DefaultApplicationArguments())
        }

        assertTrue(
            requireNotNull(error.message)
                .contains("Platform recovery is closed while an effective platform root can sign in"),
        )
    }

    private fun recoveryRunner(
        email: String,
        issuer: String,
        subject: String,
    ): PlatformBootstrapRunner {
        return PlatformBootstrapRunner(
            properties = PlatformBootstrapProperties(
                enabled = true,
                recoveryEnabled = true,
                fullName = "Recovered Platform Root",
                email = email,
                issuer = issuer,
                subject = subject,
            ),
            jdbcTemplate = jdbcTemplate,
            transactionTemplate = TransactionTemplate(transactionManager),
        )
    }

    private fun resetSystemPlatformRootRoleAssignments(): UUID {
        val roleId = jdbcTemplate.query(
            """
            SELECT id
            FROM platform_roles
            WHERE code = 'platform_root'
              AND is_system = true
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
        ).singleOrNull() ?: UUID.randomUUID().also { newRoleId ->
            jdbcTemplate.update(
                """
                INSERT INTO platform_roles (id, name, code, is_system, is_active)
                VALUES (?, 'Platform Root', 'platform_root', true, true)
                """.trimIndent(),
                newRoleId,
            )
        }
        jdbcTemplate.update(
            "UPDATE platform_roles SET is_active = true WHERE id = ?",
            roleId,
        )
        jdbcTemplate.update(
            "DELETE FROM platform_user_roles WHERE platform_role_id = ?",
            roleId,
        )
        return roleId
    }

    private fun insertActivePlatformUser(): UUID {
        val platformUserId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, full_name, email, status)
            VALUES (?, 'Effective Platform Root', ?, 'active')
            """.trimIndent(),
            platformUserId,
            "effective-root-${platformUserId.toString().take(8)}@example.com",
        )
        return platformUserId
    }

    private fun insertPlatformIdentity(platformUserId: UUID) {
        val identityLinkId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO identity_links (
                id, identity_mode, provider, issuer, subject, platform_user_id, email
            ) VALUES (?, 'platform', 'oidc', ?, ?, ?, ?)
            """.trimIndent(),
            identityLinkId,
            "https://keycloak.example.com/realms/peak",
            "effective-root-${UUID.randomUUID()}",
            platformUserId,
            "effective-root-${identityLinkId.toString().take(8)}@example.com",
        )
    }
}
