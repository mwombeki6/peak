package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
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
                connection.createStatement().execute(
                    """
                    INSERT INTO roles (id, tenant_id, name, is_system, is_active)
                    VALUES (
                        '$legacyPropertyAdminRoleId', '$tenantId',
                        'Property Administrator', false, true
                    )
                    """.trimIndent(),
                )
            }

            val flyway = Flyway.configure()
                .dataSource(url, postgres.username, postgres.password)
                .load()
            flyway.migrate()
            assertEquals("63", flyway.info().current().version.version)

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
