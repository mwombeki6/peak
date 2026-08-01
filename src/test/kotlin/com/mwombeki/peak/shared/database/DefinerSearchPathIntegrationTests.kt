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

    /**
     * A definer function executes with its owner's rights, and the migration
     * role that owns most of them is SUPERUSER and BYPASSRLS. Naming pg_temp
     * stopped a caller from choosing what those functions read, but it did not
     * reduce what they are permitted to do once they read it.
     *
     * Fixing that means giving each one a dedicated NOBYPASSRLS owner and the
     * narrow policies its body needs, which is per-function work rather than a
     * sweep: [LEGACY_SUPERUSER_OWNED_DEFINERS] is the outstanding debt, not an
     * approved design. The pattern to follow is pms_privileged_access_owner in
     * V76 and its accommodation in V78.
     *
     * This asserts in both directions on purpose. A new definer function
     * defaulting to the superuser owner fails, so the debt cannot grow. An
     * entry that has since been rehomed also fails, so the list cannot rot into
     * a record of problems that were fixed years ago.
     */
    @Test
    fun `security definer ownership debt neither grows nor goes stale`() {
        val superuserOwned = jdbcTemplate.queryForList(
            """
            SELECT function_name.proname
            FROM pg_catalog.pg_proc AS function_name
            JOIN pg_catalog.pg_namespace AS schema_name
              ON schema_name.oid = function_name.pronamespace
            JOIN pg_catalog.pg_roles AS owner_role
              ON owner_role.oid = function_name.proowner
            WHERE schema_name.nspname = 'public'
              AND function_name.prosecdef
              AND owner_role.rolsuper
            ORDER BY function_name.proname
            """.trimIndent(),
            String::class.java,
        ).toSet()

        val newlyUnsafe = superuserOwned - LEGACY_SUPERUSER_OWNED_DEFINERS
        assertTrue(
            newlyUnsafe.isEmpty(),
            "these SECURITY DEFINER functions run as a superuser that bypasses row-level " +
                "security. Give them a dedicated NOBYPASSRLS owner rather than adding them " +
                "to the legacy list: ${newlyUnsafe.joinToString(", ")}",
        )

        val alreadyRehomed = LEGACY_SUPERUSER_OWNED_DEFINERS - superuserOwned
        assertTrue(
            alreadyRehomed.isEmpty(),
            "these no longer run as a superuser, so remove them from the legacy list " +
                "to keep it an accurate record of what is left: " +
                alreadyRehomed.joinToString(", "),
        )
    }

    /**
     * PostgreSQL grants EXECUTE on a new function to PUBLIC unless told
     * otherwise, so a definer function is callable by every role in the cluster
     * by default. Every one here already revokes that and grants explicitly,
     * which is the third property these functions depend on alongside their
     * search_path and their owner.
     *
     * It costs nothing to keep, and the default is the failure mode: forgetting
     * a REVOKE is silent, and the function still works for the roles that were
     * supposed to have it.
     */
    @Test
    fun `no security definer function is executable by PUBLIC`() {
        val publiclyExecutable = jdbcTemplate.queryForList(
            """
            SELECT DISTINCT function_name.proname
            FROM pg_catalog.pg_proc AS function_name
            JOIN pg_catalog.pg_namespace AS schema_name
              ON schema_name.oid = function_name.pronamespace
            WHERE schema_name.nspname = 'public'
              AND function_name.prosecdef
              AND (
                  -- A null ACL means the built-in default, which includes PUBLIC.
                  function_name.proacl IS NULL
                  -- Grantee zero is PUBLIC. Matching the ACL text instead would
                  -- also match any named role holding EXECUTE.
                  OR EXISTS (
                      SELECT 1
                      FROM aclexplode(function_name.proacl) AS entry
                      WHERE entry.grantee = 0
                  )
              )
            ORDER BY 1
            """.trimIndent(),
            String::class.java,
        )

        assertTrue(
            publiclyExecutable.isEmpty(),
            "these SECURITY DEFINER functions can be executed by any role. Revoke " +
                "EXECUTE from PUBLIC and grant it to the roles that need it: " +
                publiclyExecutable.joinToString(", "),
        )
    }

    private companion object {
        /** Outstanding debt, not an approved design. Shrink it; never extend it. */
        val LEGACY_SUPERUSER_OWNED_DEFINERS = setOf(
            "active_contract_mock_provider_counts",
            "append_realtime_event",
            "assert_tenant_capacity",
            "assert_tenant_entitlement_enabled",
            "can_access_public_module",
            "can_platform_admin_access_tenant",
            "can_support_session_access_tenant",
            "claim_expired_report_artifacts",
            "claim_outbox_events",
            "complete_outbox_event",
            "dead_letter_outbox_event",
            "delete_expired_realtime_events",
            "effective_tenant_entitlement",
            "enqueue_report_delivery_outbox_event",
            "fail_outbox_event",
            "latest_realtime_event_sequence",
            "maintain_idempotency_keys",
            "mirror_property_outbox_to_realtime_journal",
            "phase3_operational_metrics",
            "platform_user_has_permission",
            "platform_user_holds_permission",
            "poll_realtime_events",
            "production_provider_readiness_counts",
            "provision_tenant_administrator",
            "reclaim_stale_outbox_events",
            "replay_realtime_events",
            "resolve_oidc_identity_link",
            "resolve_payment_webhook_scope",
            "resolve_public_property_scope",
            "sync_corporate_account_balance_from_ar",
            "verify_tenant_business_profile",
        )
    }
}
