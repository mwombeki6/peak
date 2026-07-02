package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
class PaymentLifecycleMigrationUpgradeIntegrationTests @Autowired constructor(
    private val postgres: PostgreSQLContainer,
) {

    @Test
    fun `upgrades V40 confirmed and cancelled payments to canonical states`() {
        val database = "payment_upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password,
        ).use { connection ->
            connection.createStatement().use {
                it.execute("CREATE DATABASE $database")
            }
        }
        val jdbcUrl = postgres.jdbcUrl.substringBeforeLast('/') + "/$database"
        try {
            Flyway.configure()
                .dataSource(jdbcUrl, postgres.username, postgres.password)
                .target("40")
                .load()
                .migrate()

            val confirmedId = UUID.randomUUID()
            val cancelledId = UUID.randomUUID()
            val tenantId = UUID.randomUUID()
            val propertyId = UUID.randomUUID()
            DriverManager.getConnection(
                jdbcUrl,
                postgres.username,
                postgres.password,
            ).use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO tenants (
                        id, name, slug, status, schema_name, country_code,
                        currency_code, plan_id
                    )
                    VALUES (?, 'V40 Upgrade Tenant', ?, 'active', 'public', 'TZ',
                            'TZS', '20202020-0000-0000-0000-000000000001')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.setString(2, "v40-upgrade-$tenantId")
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO properties (
                        id, tenant_id, name, code, status, is_active
                    )
                    VALUES (?, ?, 'V40 Upgrade Property', ?, 'active', true)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, propertyId)
                    statement.setObject(2, tenantId)
                    statement.setString(
                        3,
                        "V40${propertyId.toString().take(8)}",
                    )
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO payment_transactions (
                        id, tenant_id, property_id, transaction_direction,
                        transaction_type, internal_reference, amount, status,
                        confirmed_at
                    )
                    VALUES (?, ?, ?, 'inbound', 'collection', ?, 1000, ?, now())
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, confirmedId)
                    statement.setObject(2, tenantId)
                    statement.setObject(3, propertyId)
                    statement.setString(4, "V40-CONFIRMED")
                    statement.setString(5, "confirmed")
                    statement.executeUpdate()

                    statement.setObject(1, cancelledId)
                    statement.setObject(2, tenantId)
                    statement.setObject(3, propertyId)
                    statement.setString(4, "V40-CANCELLED")
                    statement.setString(5, "cancelled")
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    UPDATE properties
                    SET status = 'suspended', is_active = false
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, propertyId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "UPDATE tenants SET status = 'suspended' WHERE id = ?",
                ).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.executeUpdate()
                }
            }

            Flyway.configure()
                .dataSource(jdbcUrl, postgres.username, postgres.password)
                .load()
                .migrate()

            DriverManager.getConnection(
                jdbcUrl,
                postgres.username,
                postgres.password,
            ).use { connection ->
                connection.prepareStatement(
                    """
                    SELECT status, posted_at, expired_at
                    FROM payment_transactions
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, confirmedId)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals("posted", rows.getString("status"))
                        assertNotNull(rows.getTimestamp("posted_at"))
                    }

                    statement.setObject(1, cancelledId)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals("expired", rows.getString("status"))
                        assertNotNull(rows.getTimestamp("expired_at"))
                    }
                }
                connection.prepareStatement(
                    """
                    UPDATE properties
                    SET status = 'active', is_active = true
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, propertyId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "UPDATE tenants SET status = 'active' WHERE id = ?",
                ).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.executeUpdate()
                }
                assertFailsWith<SQLException> {
                    connection.prepareStatement(
                        """
                        UPDATE payment_transactions
                        SET status = 'posted', posted_at = now()
                        WHERE id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, cancelledId)
                        statement.executeUpdate()
                    }
                }
                assertFailsWith<SQLException> {
                    connection.prepareStatement(
                        """
                        UPDATE payment_transactions
                        SET refunded_amount = amount + 0.01
                        WHERE id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, confirmedId)
                        statement.executeUpdate()
                    }
                }
            }
        } finally {
            DriverManager.getConnection(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            ).use { connection ->
                connection.createStatement().use {
                    it.execute("DROP DATABASE IF EXISTS $database WITH (FORCE)")
                }
            }
        }
    }
}
