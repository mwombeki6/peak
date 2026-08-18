package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * A table whose tenant_id is NOT NULL is tenant-owned business truth and must
 * have RLS plus a policy. FORCE is required when a policy isolates by
 * current_tenant_id() — that is the tenant-owned case. Platform tables may
 * carry a required tenant_id and still be gated by platform permission
 * instead; FORCE there would block SECURITY DEFINER writers that run as the
 * table owner (see V148 on platform_audit_logs).
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TenantRlsCoverageArchitectureTests {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun everyTenantScopedTableHasForcedRowLevelSecurityAndAPolicy() {
        val tenantOwned = """
            EXISTS (
                SELECT 1
                FROM pg_attribute a
                WHERE a.attrelid = c.oid
                  AND a.attname = 'tenant_id'
                  AND a.attnotnull
                  AND NOT a.attisdropped
            )
        """.trimIndent()
        val missingRls = queryNames(
            """
            SELECT c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
              AND c.relkind IN ('r', 'p')
              AND $tenantOwned
              AND NOT c.relrowsecurity
            ORDER BY 1
            """.trimIndent(),
        )
        val missingForce = queryNames(
            """
            SELECT c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
              AND c.relkind IN ('r', 'p')
              AND $tenantOwned
              AND EXISTS (
                  SELECT 1
                  FROM pg_policy p
                  WHERE p.polrelid = c.oid
                    AND pg_get_expr(p.polqual, p.polrelid) ILIKE '%current_tenant_id%'
              )
              AND c.relrowsecurity
              AND NOT c.relforcerowsecurity
            ORDER BY 1
            """.trimIndent(),
        )
        val missingPolicy = queryNames(
            """
            SELECT c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
              AND c.relkind IN ('r', 'p')
              AND EXISTS (
                  SELECT 1
                  FROM pg_attribute a
                  WHERE a.attrelid = c.oid
                    AND a.attname = 'tenant_id'
                    AND NOT a.attisdropped
              )
              AND NOT EXISTS (
                  SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid
              )
            ORDER BY 1
            """.trimIndent(),
        )
        val platformMentionMissingRls = queryNames(
            """
            SELECT c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
              AND c.relkind IN ('r', 'p')
              AND EXISTS (
                  SELECT 1
                  FROM pg_attribute a
                  WHERE a.attrelid = c.oid
                    AND a.attname = 'tenant_id'
                    AND NOT a.attnotnull
                    AND NOT a.attisdropped
              )
              AND NOT c.relrowsecurity
            ORDER BY 1
            """.trimIndent(),
        )
        assertTrue(
            missingRls.isEmpty() &&
                missingForce.isEmpty() &&
                missingPolicy.isEmpty() &&
                platformMentionMissingRls.isEmpty(),
            buildString {
                appendLine("Tenant-scoped tables missing RLS coverage:")
                if (missingRls.isNotEmpty()) appendLine("  no RLS: ${missingRls.joinToString()}")
                if (missingForce.isNotEmpty()) appendLine("  RLS without FORCE: ${missingForce.joinToString()}")
                if (missingPolicy.isNotEmpty()) appendLine("  no policy: ${missingPolicy.joinToString()}")
                if (platformMentionMissingRls.isNotEmpty()) {
                    appendLine(
                        "  nullable tenant_id without RLS: ${platformMentionMissingRls.joinToString()}",
                    )
                }
            },
        )
    }

    private fun queryNames(sql: String): List<String> =
        jdbcTemplate.query(sql) { rs, _ -> rs.getString(1) }
}
