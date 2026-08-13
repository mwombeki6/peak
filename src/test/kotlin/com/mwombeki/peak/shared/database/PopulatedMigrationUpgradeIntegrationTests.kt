package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class PopulatedMigrationUpgradeIntegrationTests @Autowired constructor(
    private val postgres: PostgreSQLContainer,
) {

    @ParameterizedTest(name = "populated V{0} upgrades without tenant or financial drift")
    @ValueSource(strings = ["53", "67"])
    fun `upgrades supported populated schemas`(startVersion: String) {
        val database = "populated_v${startVersion}_${UUID.randomUUID().toString().replace("-", "")}"
        rootConnection().use { connection ->
            connection.createStatement().execute("CREATE DATABASE $database")
        }
        val url = postgres.jdbcUrl.substringBeforeLast('/') + "/$database"

        try {
            val initial = Flyway.configure()
                .dataSource(url, postgres.username, postgres.password)
                .target(startVersion)
                .load()
            initial.migrate()

            val tenantId = UUID.randomUUID()
            val propertyId = UUID.randomUUID()
            val folioId = UUID.randomUUID()
            val chargeId = UUID.randomUUID()
            val validAuditId = UUID.randomUUID()
            val malformedAuditId = UUID.randomUUID()
            DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute(
                        """
                        INSERT INTO tenants (
                            id, name, slug, status, schema_name, country_code,
                            currency_code, plan_id
                        ) VALUES (
                            '$tenantId', 'Populated Upgrade Tenant',
                            'populated-$tenantId', 'active', 'public', 'TZ', 'TZS',
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
                            '$propertyId', '$tenantId', 'Upgrade Property',
                            'UPG', 'active', true, 'Africa/Dar_es_Salaam', current_date
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO folios (
                            id, tenant_id, property_id, status, currency_code,
                            subtotal, tax_amount, total_amount
                        ) VALUES (
                            '$folioId', '$tenantId', '$propertyId', 'open', 'TZS',
                            125000.00, 22500.00, 147500.00
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO folio_charges (
                            id, tenant_id, property_id, folio_id, charge_type,
                            description, quantity, unit_price, subtotal,
                            tax_rate, tax_amount, amount
                        ) VALUES (
                            '$chargeId', '$tenantId', '$propertyId', '$folioId',
                            'MISC', 'Upgrade invariant charge', 1, 125000.00,
                            125000.00, 0.18, 22500.00, 147500.00
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO audit_logs (
                            id, tenant_id, action, entity_type, ip_address, correlation_id
                        ) VALUES
                            (
                                '$validAuditId', '$tenantId', 'upgrade.valid-ip',
                                'migration_test', '203.0.113.42', 'upgrade-valid-ip'
                            ),
                            (
                                '$malformedAuditId', '$tenantId', 'upgrade.malformed-ip',
                                'migration_test', 'legacy-not-an-ip', 'upgrade-malformed-ip'
                            )
                        """.trimIndent(),
                    )
                }
            }

            val upgraded = Flyway.configure()
                .dataSource(url, postgres.username, postgres.password)
                .load()
            upgraded.migrate()
            assertEquals("100", upgraded.info().current().version.version)

            DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
                connection.prepareStatement(
                    "SELECT subtotal, tax_amount, amount FROM folio_charges WHERE id = ?",
                ).use { statement ->
                    statement.setObject(1, chargeId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals("125000.00", rows.getBigDecimal("subtotal").toPlainString())
                        assertEquals("22500.00", rows.getBigDecimal("tax_amount").toPlainString())
                        assertEquals("147500.00", rows.getBigDecimal("amount").toPlainString())
                    }
                }
                connection.prepareStatement(
                    """
                    SELECT id, host(ip_address) AS ip_address,
                           pg_typeof(ip_address)::text AS ip_address_type
                    FROM audit_logs
                    WHERE id IN (?, ?)
                    ORDER BY id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, validAuditId)
                    statement.setObject(2, malformedAuditId)
                    statement.executeQuery().use { rows ->
                        val converted = buildMap {
                            while (rows.next()) {
                                assertEquals("inet", rows.getString("ip_address_type"))
                                put(
                                    rows.getObject("id", UUID::class.java),
                                    rows.getString("ip_address"),
                                )
                            }
                        }
                        assertEquals("203.0.113.42", converted[validAuditId])
                        assertEquals(null, converted[malformedAuditId])
                    }
                }
                connection.prepareStatement(
                    """
                    SELECT relrowsecurity, relforcerowsecurity
                    FROM pg_class
                    WHERE oid = 'folio_charges'::regclass
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertTrue(rows.getBoolean("relrowsecurity"))
                        assertTrue(rows.getBoolean("relforcerowsecurity"))
                    }
                }
            }
        } finally {
            rootConnection().use { connection ->
                connection.createStatement().execute("DROP DATABASE IF EXISTS $database WITH (FORCE)")
            }
        }
    }

    private fun rootConnection() = DriverManager.getConnection(
        postgres.jdbcUrl,
        postgres.username,
        postgres.password,
    )
}
