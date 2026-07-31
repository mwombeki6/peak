package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import java.util.UUID
import kotlin.test.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * A SECURITY DEFINER function that does not name pg_temp in its search_path
 * resolves unqualified table names against the caller's temporary schema first,
 * so the caller can decide what the function reads. Since these functions run as
 * the migration role, which is SUPERUSER and BYPASSRLS, a forged answer carries
 * the highest authority in the cluster.
 *
 * Both halves of the guard are here. The first proves the property still holds
 * for the specific function the original hijack was demonstrated against. The
 * second is the one that matters over time: it fails for any future function
 * written the unsafe way, which a fixed list of ALTER statements cannot do.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class DefinerSearchPathIntegrationTests @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `a shadowed temporary table cannot forge a public scope decision`() {
        val tenantId = UUID.randomUUID()
        val propertyId = UUID.randomUUID()

        val decidedWithShadows = jdbcTemplate.execute(
            ConnectionCallback { connection ->
                connection.createStatement().use { statement ->
                    // The function only answers for an unauthenticated caller,
                    // so the session must look like a public request.
                    statement.execute("SELECT set_config('app.current_tenant_id', '', false)")
                    statement.execute("SELECT set_config('app.current_tenant_user_id', '', false)")
                    statement.execute("SELECT set_config('app.current_platform_user_id', '', false)")

                    // Shadow every relation can_access_public_module reads
                    // unqualified, each populated so the checks would pass.
                    statement.execute(
                        "CREATE TEMP TABLE tenants (id uuid, status text, deleted_at timestamptz)",
                    )
                    statement.execute(
                        "INSERT INTO pg_temp.tenants VALUES ('$tenantId', 'active', NULL)",
                    )
                    statement.execute(
                        "CREATE TEMP TABLE properties " +
                            "(id uuid, tenant_id uuid, status text, is_active boolean, deleted_at timestamptz)",
                    )
                    statement.execute(
                        "INSERT INTO pg_temp.properties " +
                            "VALUES ('$propertyId', '$tenantId', 'active', true, NULL)",
                    )
                    statement.execute(
                        "CREATE TEMP TABLE tenant_modules (tenant_id uuid, module_id text, is_enabled boolean)",
                    )
                    statement.execute(
                        "INSERT INTO pg_temp.tenant_modules VALUES ('$tenantId', 'booking_engine', true)",
                    )
                    statement.execute(
                        "CREATE TEMP TABLE property_modules " +
                            "(tenant_id uuid, property_id uuid, module_id text, is_enabled boolean)",
                    )
                    statement.execute(
                        "INSERT INTO pg_temp.property_modules " +
                            "VALUES ('$tenantId', '$propertyId', 'booking_engine', true)",
                    )
                }

                val decided = connection.prepareStatement(
                    "SELECT can_access_public_module(?, ?, 'booking_engine')",
                ).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.setObject(2, propertyId)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getBoolean(1)
                    }
                }

                // These share a pooled connection with every other test, and a
                // temporary table called `tenants` would break all of them.
                connection.createStatement().use { statement ->
                    statement.execute(
                        "DROP TABLE IF EXISTS " +
                            "pg_temp.tenants, pg_temp.properties, " +
                            "pg_temp.tenant_modules, pg_temp.property_modules",
                    )
                }
                decided
            },
        )

        assertFalse(
            decidedWithShadows,
            "neither the tenant nor the property exists, so a forged temporary " +
                "table must not be able to make the scope check succeed",
        )
    }

    @Test
    fun `every security definer function searches the temporary schema last`() {
        val unsafe = jdbcTemplate.queryForList(
            """
            SELECT function_name.proname
            FROM pg_catalog.pg_proc AS function_name
            JOIN pg_catalog.pg_namespace AS schema_name
              ON schema_name.oid = function_name.pronamespace
            WHERE schema_name.nspname = 'public'
              AND function_name.prosecdef
              AND COALESCE(
                  array_to_string(function_name.proconfig, ',') NOT LIKE '%pg_temp%',
                  true
              )
            ORDER BY function_name.proname
            """.trimIndent(),
            String::class.java,
        )

        assertTrue(
            unsafe.isEmpty(),
            "these SECURITY DEFINER functions omit pg_temp from search_path, so a " +
                "caller can shadow the tables they read: ${unsafe.joinToString(", ")}",
        )
    }
}
