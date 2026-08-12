package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
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
class Phase4MigrationUpgradeIntegrationTests @Autowired constructor(
    private val postgres: PostgreSQLContainer,
) {
    @Test
    fun `upgrades populated V44 department records through current schema`() {
        val database = "phase4_upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use {
            it.createStatement().execute("CREATE DATABASE $database")
        }
        val url = postgres.jdbcUrl.substringBeforeLast('/') + "/$database"
        try {
            Flyway.configure()
                .dataSource(url, postgres.username, postgres.password)
                .target("44")
                .load()
                .migrate()

            val tenant = UUID.randomUUID()
            val property = UUID.randomUUID()
            val user = UUID.randomUUID()
            val roomType = UUID.randomUUID()
            val room = UUID.randomUUID()
            val task = UUID.randomUUID()
            val item = UUID.randomUUID()
            val location = UUID.randomUUID()
            val supplier = UUID.randomUUID()
            val order = UUID.randomUUID()
            DriverManager.getConnection(url, postgres.username, postgres.password).use { c ->
                c.createStatement().use { sql ->
                    sql.execute(
                        """
                        INSERT INTO tenants (
                            id, name, slug, status, schema_name, country_code,
                            currency_code, plan_id
                        ) VALUES (
                            '$tenant', 'Upgrade Tenant', 'upgrade-$tenant', 'active',
                            'public', 'TZ', 'TZS',
                            '20202020-0000-0000-0000-000000000001'
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO properties (
                            id, tenant_id, name, code, status, is_active
                        ) VALUES (
                            '$property', '$tenant', 'Upgrade Property',
                            'UPGRADE', 'active', true
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO users (
                            id, tenant_id, full_name, email, status, is_active
                        ) VALUES (
                            '$user', '$tenant', 'Upgrade User',
                            'upgrade-$user@example.com', 'active', true
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO room_types (
                            id, tenant_id, property_id, name, code, base_price
                        ) VALUES (
                            '$roomType', '$tenant', '$property', 'Standard', 'STD', 100
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO rooms (
                            id, tenant_id, property_id, room_type_id,
                            room_number, status
                        ) VALUES (
                            '$room', '$tenant', '$property', '$roomType',
                            '101', 'vacant_dirty'
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO housekeeping_tasks (
                            id, tenant_id, room_id, type, status, scheduled_date
                        ) VALUES (
                            '$task', '$tenant', '$room', 'departure_clean',
                            'pending', current_date
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO inventory_items (
                            id, tenant_id, name, unit, reorder_level,
                            cost_per_unit, current_stock
                        ) VALUES (
                            '$item', '$tenant', 'Rice', 'kg', 2, 4.25, 10
                        )
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO inventory_locations (
                            id, tenant_id, property_id, type
                        ) VALUES ('$location', '$tenant', '$property', 'store')
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO stock_levels (
                            tenant_id, item_id, location_id, quantity, reorder_level
                        ) VALUES ('$tenant', '$item', '$location', 10, 2)
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO suppliers (id, tenant_id, name)
                        VALUES ('$supplier', '$tenant', 'Upgrade Supplier')
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO purchase_orders (
                            id, tenant_id, supplier_id, total_amount, status
                        ) VALUES ('$order', '$tenant', '$supplier', 42.50, 'approved')
                        """.trimIndent(),
                    )
                    sql.execute(
                        """
                        INSERT INTO purchase_order_items (
                            tenant_id, purchase_order_id, inventory_item_id,
                            quantity, unit_price, total_price
                        ) VALUES ('$tenant', '$order', '$item', 10, 4.25, 42.50)
                        """.trimIndent(),
                    )
                }
            }

            val flyway = Flyway.configure()
                .dataSource(url, postgres.username, postgres.password)
                .load()
            flyway.migrate()
            assertEquals("89", flyway.info().current().version.version)

            DriverManager.getConnection(url, postgres.username, postgres.password).use { c ->
                c.prepareStatement(
                    """
                    SELECT property_id, status FROM housekeeping_tasks WHERE id = ?
                    """.trimIndent(),
                ).use {
                    it.setObject(1, task)
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(property, rows.getObject("property_id", UUID::class.java))
                        assertEquals("pending", rows.getString("status"))
                    }
                }
                c.prepareStatement(
                    "SELECT average_cost FROM stock_levels WHERE item_id = ? AND location_id = ?",
                ).use {
                    it.setObject(1, item)
                    it.setObject(2, location)
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals("4.250000", rows.getBigDecimal("average_cost").toPlainString())
                    }
                }
                c.prepareStatement(
                    """
                    SELECT property_id, currency, status, order_number
                    FROM purchase_orders WHERE id = ?
                    """.trimIndent(),
                ).use {
                    it.setObject(1, order)
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(property, rows.getObject("property_id", UUID::class.java))
                        assertEquals("TZS", rows.getString("currency").trim())
                        assertEquals("approved", rows.getString("status"))
                        assertNotNull(rows.getString("order_number"))
                    }
                }
            }
        } finally {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use {
                it.createStatement().execute("DROP DATABASE IF EXISTS $database WITH (FORCE)")
            }
        }
    }
}
