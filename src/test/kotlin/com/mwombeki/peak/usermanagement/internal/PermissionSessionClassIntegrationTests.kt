package com.mwombeki.peak.usermanagement.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.SessionClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Nothing dangerous may be reachable from six digits typed on a terminal.
 *
 * V114 asserts this once, at migration time. That is not enough: a permission added in a later
 * migration and classified operational would never meet that check again, because a `DO` block
 * runs exactly once and never revisits the rows a future migration inserts.
 *
 * So the rule lives here too, where it runs on every build. This is the same reason
 * `RestorableRoleBootstrapTests` exists — a migration guard proves the state at the moment it
 * applied, and a test proves the state now.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PermissionSessionClassIntegrationTests {

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun resetSession() {
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun noPrivilegedPermissionIsReachableFromADevicePin() {
        val dangerous = jdbcTemplate.queryForList(
            """
            SELECT code FROM permission_catalog
            WHERE minimum_session_class = 'operational'
              AND (
                  code LIKE 'platform.%'
               OR code LIKE 'tenant.subscription.%'
               OR code LIKE 'tenant.users.%'
               OR code LIKE 'tenant.roles.%'
               OR code LIKE 'tenant.properties.%'
               OR code LIKE '%.configure'
               OR code LIKE 'payments.refund%'
               OR code LIKE 'payments.reconcile%'
               OR code LIKE '%write_off%'
               OR code IN ('tenant.admin.all', 'admin.all')
              )
            ORDER BY code
            """.trimIndent(),
            String::class.java,
        )

        assertEquals(
            emptyList(),
            dangerous,
            "these change money, identity or configuration and are within reach of a PIN",
        )
    }

    /**
     * Deny-by-default only means something if the default is actually strong. A migration that
     * flipped the column default would leave every future permission open to a PIN session and
     * break nothing visible.
     */
    @Test
    fun anUnclassifiedPermissionDefaultsToStrong() {
        // Asserted against the schema rather than by inserting a probe row: permission_catalog
        // constrains its namespace, so a synthetic row has to satisfy rules unrelated to what
        // is being tested, and deleting it afterwards leaves the test dependent on cleanup.
        val default = jdbcTemplate.queryForObject(
            """
            SELECT column_default FROM information_schema.columns
            WHERE table_name = 'permission_catalog' AND column_name = 'minimum_session_class'
            """.trimIndent(),
            String::class.java,
        )

        assertTrue(
            default.orEmpty().contains("'strong'"),
            "forgetting to classify a permission must refuse a waiter, never allow one; " +
                "the column default is $default",
        )

        assertEquals(
            "NO",
            jdbcTemplate.queryForObject(
                """
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'permission_catalog' AND column_name = 'minimum_session_class'
                """.trimIndent(),
                String::class.java,
            ),
            "a NULL requirement would satisfy nothing and be read as unclassified",
        )
    }

    /** Every stored value must be one SessionClass can parse. */
    @Test
    fun everyStoredRequirementIsParseable() {
        val stored = jdbcTemplate.queryForList(
            "SELECT DISTINCT minimum_session_class FROM permission_catalog",
            String::class.java,
        )

        assertTrue(stored.isNotEmpty(), "no permissions exist, so this asserts nothing")
        stored.filterNotNull().forEach { SessionClass.fromPolicy(it) }
    }

    /** The operational set must not be empty, or a device session can do nothing at all. */
    @Test
    fun theOperationalSetIsNotEmpty() {
        val operational = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM permission_catalog WHERE minimum_session_class = 'operational'",
            Int::class.java,
        )

        assertTrue(
            (operational ?: 0) >= 15,
            "a device session needs the POS, housekeeping and maintenance work classified, " +
                "found $operational",
        )
    }
}
