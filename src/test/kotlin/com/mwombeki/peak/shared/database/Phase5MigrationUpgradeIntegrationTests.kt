package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class Phase5MigrationUpgradeIntegrationTests @Autowired constructor(
    private val postgres: PostgreSQLContainer,
) {
    @Test
    fun `upgrades populated V49 close inputs with timezone provenance`() {
        val database =
            "phase5_upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password,
        ).use {
            it.createStatement().execute("CREATE DATABASE $database")
        }
        val url = postgres.jdbcUrl.substringBeforeLast('/') + "/$database"
        try {
            Flyway.configure()
                .dataSource(url, postgres.username, postgres.password)
                .target("49")
                .load()
                .migrate()
            val tenantId = UUID.randomUUID()
            val propertyId = UUID.randomUUID()
            val folioId = UUID.randomUUID()
            val chargeId = UUID.randomUUID()
            val legacyPropertyAdminRoleId = UUID.randomUUID()
            val legacyTenantAdminRoleId = UUID.randomUUID()
            val legacyReportPermissionId = UUID.randomUUID()
            val legacyPlatformRootRoleId = UUID.randomUUID()
            val fullPlatformAuthorityRoleId = UUID.randomUUID()
            DriverManager.getConnection(
                url,
                postgres.username,
                postgres.password,
            ).use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute(
                        """
                        INSERT INTO tenants (
                            id, name, slug, status, schema_name, country_code,
                            currency_code, plan_id
                        ) VALUES (
                            '$tenantId', 'Phase 5 Upgrade',
                            'phase5-$tenantId', 'active', 'public', 'TZ', 'TZS',
                            '20202020-0000-0000-0000-000000000001'
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO properties (
                            id, tenant_id, name, code, status, is_active,
                            timezone, business_date
                        ) VALUES (
                            '$propertyId', '$tenantId', 'Kiritimati Hotel',
                            'KIR', 'active', true, 'Pacific/Kiritimati',
                            '2026-01-02'
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO folios (
                            id, tenant_id, property_id, status, currency_code
                        ) VALUES (
                            '$folioId', '$tenantId', '$propertyId',
                            'open', 'TZS'
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO folio_charges (
                            id, tenant_id, property_id, folio_id, charge_type,
                            description, quantity, unit_price, subtotal,
                            tax_rate, tax_amount, amount, posted_at
                        ) VALUES (
                            '$chargeId', '$tenantId', '$propertyId', '$folioId',
                            'MISC', 'V49 charge', 1, 10, 10, 0, 0, 10,
                            '2026-01-01 12:30:00+00'
                        )
                        """.trimIndent(),
                    )
                }
            }

            Flyway.configure()
                .dataSource(url, postgres.username, postgres.password)
                .target("62")
                .load()
                .migrate()
            DriverManager.getConnection(
                url,
                postgres.username,
                postgres.password,
            ).use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute(
                        """
                        INSERT INTO roles (id, tenant_id, name, is_system, is_active)
                        VALUES (
                            '$legacyPropertyAdminRoleId', '$tenantId',
                            'Property Administrator', false, true
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO tenant_roles (
                            id, tenant_id, name, code, is_system, is_active
                        ) VALUES (
                            '$legacyTenantAdminRoleId', '$tenantId',
                            'Tenant Administrator', 'tenant_admin', false, true
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO permissions (
                            id, tenant_id, code, description
                        ) VALUES (
                            '$legacyReportPermissionId', '$tenantId',
                            'reports.manual_generate',
                            'Legacy manual report generation'
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO tenant_role_permissions (
                            tenant_role_id, permission_id
                        )
                        SELECT '$legacyTenantAdminRoleId', id
                        FROM permissions
                        WHERE tenant_id = '$tenantId'
                          AND code = 'reports.manual_generate'
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO role_permissions (role_id, permission_id)
                        SELECT '$legacyPropertyAdminRoleId', id
                        FROM permissions
                        WHERE tenant_id = '$tenantId'
                          AND code = 'reports.manual_generate'
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO permissions (id, tenant_id, code, description)
                        SELECT gen_random_uuid(), '$tenantId', code, description
                        FROM permission_catalog
                        WHERE code IN ('tenant.admin.all', 'admin.all')
                        ON CONFLICT (tenant_id, code) DO NOTHING
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
                        SELECT '$legacyTenantAdminRoleId', id
                        FROM permissions
                        WHERE tenant_id = '$tenantId'
                          AND code = 'tenant.admin.all'
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO role_permissions (role_id, permission_id)
                        SELECT '$legacyPropertyAdminRoleId', id
                        FROM permissions
                        WHERE tenant_id = '$tenantId'
                          AND code = 'admin.all'
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO platform_roles (
                            id, name, code, is_system, is_active
                        ) VALUES (
                            '$legacyPlatformRootRoleId',
                            'Platform Root', 'platform_root', false, true
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO platform_roles (
                            id, name, code, is_system, is_active
                        ) VALUES (
                            '$fullPlatformAuthorityRoleId',
                            'Full Platform Authority',
                            'full_platform_authority_$fullPlatformAuthorityRoleId',
                            false,
                            true
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO platform_role_permissions (
                            platform_role_id, platform_permission_id
                        )
                        SELECT '$fullPlatformAuthorityRoleId', id
                        FROM platform_permissions
                        WHERE code = 'platform.admin.all'
                        """.trimIndent(),
                    )
                }
            }

            val flyway = Flyway.configure()
                .dataSource(url, postgres.username, postgres.password)
                .load()
            flyway.migrate()
            assertEquals("109", flyway.info().current().version.version)

            DriverManager.getConnection(
                url,
                postgres.username,
                postgres.password,
            ).use { connection ->
                connection.prepareStatement(
                    """
                    SELECT business_date, business_date_provenance
                    FROM folio_charges WHERE id = ?
                    """.trimIndent(),
                ).use {
                    it.setObject(1, chargeId)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(
                            LocalDate.of(2026, 1, 2),
                            rows.getObject(
                                "business_date",
                                LocalDate::class.java,
                            ),
                        )
                        assertEquals(
                            "backfilled_v50",
                            rows.getString("business_date_provenance"),
                        )
                    }
                }
                connection.prepareStatement(
                    """
                    SELECT count(*)
                    FROM permissions
                    WHERE tenant_id = ?
                      AND code = 'tenant.administrators.manage'
                    """.trimIndent(),
                ).use {
                    it.setObject(1, tenantId)
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1))
                    }
                }
                connection.prepareStatement(
                    """
                    SELECT count(*)
                    FROM platform_role_permissions prp
                    JOIN platform_permissions pp
                      ON pp.id = prp.platform_permission_id
                    WHERE prp.platform_role_id = ?
                      AND pp.code = 'platform.admin.all'
                    """.trimIndent(),
                ).use {
                    it.setObject(1, fullPlatformAuthorityRoleId)
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(0, rows.getInt(1))
                    }
                }
                connection.prepareStatement(
                    """
                    SELECT count(*)
                    FROM tenant_role_permissions trp
                    JOIN permissions p ON p.id = trp.permission_id
                    WHERE trp.tenant_role_id = ?
                      AND p.code = 'tenant.admin.all'
                    """.trimIndent(),
                ).use {
                    it.setObject(1, legacyTenantAdminRoleId)
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(0, rows.getInt(1))
                    }
                }
                connection.prepareStatement(
                    """
                    SELECT count(*)
                    FROM role_permissions rp
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE rp.role_id = ?
                      AND p.code = 'admin.all'
                    """.trimIndent(),
                ).use {
                    it.setObject(1, legacyPropertyAdminRoleId)
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(0, rows.getInt(1))
                    }
                }
                assertFailsWith<java.sql.SQLException> {
                    connection.prepareStatement(
                        """
                        INSERT INTO platform_role_permissions (
                            platform_role_id,
                            platform_permission_id
                        )
                        SELECT ?, id
                        FROM platform_permissions
                        WHERE code = 'platform.admin.all'
                        """.trimIndent(),
                    ).use {
                        it.setObject(1, fullPlatformAuthorityRoleId)
                        it.executeUpdate()
                    }
                }
                assertFailsWith<java.sql.SQLException> {
                    connection.prepareStatement(
                        """
                        INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
                        SELECT ?, id
                        FROM permissions
                        WHERE tenant_id = ?
                          AND code = 'tenant.admin.all'
                        """.trimIndent(),
                    ).use {
                        it.setObject(1, legacyTenantAdminRoleId)
                        it.setObject(2, tenantId)
                        it.executeUpdate()
                    }
                }
                assertFailsWith<java.sql.SQLException> {
                    connection.prepareStatement(
                        """
                        INSERT INTO role_permissions (role_id, permission_id)
                        SELECT ?, id
                        FROM permissions
                        WHERE tenant_id = ?
                          AND code = 'admin.all'
                        """.trimIndent(),
                    ).use {
                        it.setObject(1, legacyPropertyAdminRoleId)
                        it.setObject(2, tenantId)
                        it.executeUpdate()
                    }
                }
                connection.createStatement().executeQuery(
                    """
                    SELECT count(*) FROM report_catalog
                    WHERE generator_available = true
                    """.trimIndent(),
                ).use { rows ->
                    rows.next()
                    assertEquals(2, rows.getInt(1))
                }
                connection.prepareStatement(
                    """
                    SELECT count(*)
                    FROM permissions
                    WHERE tenant_id = ?
                      AND code = 'tenant.properties.administrators.manage'
                    """.trimIndent(),
                ).use {
                    it.setObject(1, tenantId)
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1))
                    }
                }
                connection.createStatement().executeQuery(
                    """
                    SELECT count(*)
                    FROM module_access_matrix
                    WHERE screen_key LIKE 'tenant.properties.administrators.%'
                    """.trimIndent(),
                ).use { rows ->
                    rows.next()
                    assertEquals(3, rows.getInt(1))
                }
                connection.prepareStatement(
                    """
                    SELECT count(*)
                    FROM tenant_role_permissions grant_row
                    JOIN permissions permission
                      ON permission.id = grant_row.permission_id
                    WHERE grant_row.tenant_role_id = ?
                      AND permission.code = 'reports.generate'
                    """.trimIndent(),
                ).use {
                    it.setObject(1, legacyTenantAdminRoleId)
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(0, rows.getInt(1))
                    }
                }
                connection.prepareStatement(
                    """
                    SELECT count(*)
                    FROM role_permissions grant_row
                    JOIN permissions permission
                      ON permission.id = grant_row.permission_id
                    WHERE grant_row.role_id = ?
                      AND permission.code = 'reports.generate'
                    """.trimIndent(),
                ).use {
                    it.setObject(1, legacyPropertyAdminRoleId)
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1))
                    }
                }
                connection.createStatement().executeQuery(
                    """
                    SELECT count(*)
                    FROM module_access_matrix
                    WHERE http_method = 'POST'
                      AND api_pattern = '/api/properties/:propertyId/reports/:reportCode/runs'
                      AND is_enabled_by_default = true
                      AND permission_code = 'reports.generate'
                    """.trimIndent(),
                ).use { rows ->
                    rows.next()
                    assertEquals(1, rows.getInt(1))
                }
                connection.createStatement().executeQuery(
                    """
                    SELECT count(*)
                    FROM module_access_matrix
                    WHERE is_enabled_by_default = true
                      AND screen_key IN (
                          'reports.manual_generate.tenant',
                          'reports.manual_generate.property',
                          'reports.subscriptions.tenant.manage',
                          'reports.subscriptions.property.manage',
                          'reports.delivery.retry'
                      )
                    """.trimIndent(),
                ).use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1))
                }
                connection.createStatement().executeQuery(
                    """
                    SELECT count(*)
                    FROM module_access_matrix
                    WHERE screen_key IN (
                        'platform.tenants.approve',
                        'platform.tenants.suspend'
                    )
                      AND is_enabled_by_default = true
                      AND api_pattern LIKE '/api/platform/tenants/:tenantId/%'
                    """.trimIndent(),
                ).use { rows ->
                    rows.next()
                    assertEquals(2, rows.getInt(1))
                }
                connection.createStatement().executeQuery(
                    """
                    SELECT count(*)
                    FROM pg_proc
                    WHERE proname = 'can_support_session_access_tenant'
                      AND pronargs = 4
                    """.trimIndent(),
                ).use { rows ->
                    rows.next()
                    assertEquals(1, rows.getInt(1))
                }
                connection.createStatement().executeQuery(
                    """
                    SELECT count(*)
                    FROM module_access_matrix
                    WHERE screen_key LIKE 'tenant.administrators.%'
                    """.trimIndent(),
                ).use { rows ->
                    rows.next()
                    assertEquals(3, rows.getInt(1))
                }
                connection.createStatement().executeQuery(
                    """
                    SELECT count(*)
                    FROM platform_permissions
                    WHERE code = 'platform.administrators.manage'
                    """.trimIndent(),
                ).use { rows ->
                    rows.next()
                    assertEquals(1, rows.getInt(1))
                }
                connection.prepareStatement(
                    """
                    SELECT count(*)
                    FROM platform_role_permissions prp
                    JOIN platform_permissions pp
                      ON pp.id = prp.platform_permission_id
                    WHERE prp.platform_role_id = ?
                      AND pp.code = 'platform.administrators.manage'
                    """.trimIndent(),
                ).use {
                    it.setObject(1, fullPlatformAuthorityRoleId)
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1))
                    }
                }
                connection.createStatement().executeQuery(
                    """
                    SELECT count(*)
                    FROM module_access_matrix
                    WHERE screen_key LIKE 'platform.administrators.%'
                    """.trimIndent(),
                ).use { rows ->
                    rows.next()
                    // Three original administrator routes plus the two
                    // dual-control change-request routes added by V81.
                    assertEquals(5, rows.getInt(1))
                }
                // Name the dual-control routes explicitly, so a future change
                // that removes them fails with a meaningful message rather than
                // an unexplained count mismatch.
                connection.createStatement().executeQuery(
                    """
                    SELECT count(*)
                    FROM module_access_matrix
                    WHERE screen_key IN (
                        'platform.administrators.change.request',
                        'platform.administrators.change.decide'
                    )
                    """.trimIndent(),
                ).use { rows ->
                    rows.next()
                    assertEquals(2, rows.getInt(1))
                }
                connection.prepareStatement(
                    """
                    SELECT name, is_system
                    FROM roles
                    WHERE id = ?
                    """.trimIndent(),
                ).use {
                    it.setObject(1, legacyPropertyAdminRoleId)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(
                            "Property Administrator (Legacy $legacyPropertyAdminRoleId)",
                            rows.getString("name"),
                        )
                        assertEquals(false, rows.getBoolean("is_system"))
                    }
                }
                connection.prepareStatement(
                    """
                    SELECT name, code, is_system
                    FROM tenant_roles
                    WHERE id = ?
                    """.trimIndent(),
                ).use {
                    it.setObject(1, legacyTenantAdminRoleId)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(
                            "Tenant Administrator (Legacy $legacyTenantAdminRoleId)",
                            rows.getString("name"),
                        )
                        assertEquals(
                            "tenant_admin_legacy_${legacyTenantAdminRoleId.toString().replace("-", "")}",
                            rows.getString("code"),
                        )
                        assertEquals(false, rows.getBoolean("is_system"))
                    }
                }
                connection.prepareStatement(
                    """
                    SELECT name, code, is_system
                    FROM platform_roles
                    WHERE id = ?
                    """.trimIndent(),
                ).use {
                    it.setObject(1, legacyPlatformRootRoleId)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(
                            "Platform Root (Legacy $legacyPlatformRootRoleId)",
                            rows.getString("name"),
                        )
                        assertEquals(
                            "platform_root_legacy_${legacyPlatformRootRoleId.toString().replace("-", "")}",
                            rows.getString("code"),
                        )
                        assertEquals(false, rows.getBoolean("is_system"))
                    }
                }
            }
        } finally {
            DriverManager.getConnection(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            ).use {
                it.createStatement().execute(
                    "DROP DATABASE IF EXISTS $database WITH (FORCE)",
                )
            }
        }
    }
}
