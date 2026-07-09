package com.mwombeki.peak.inventory.internal

import com.mwombeki.peak.inventory.api.InventoryCloseSnapshotPort
import com.mwombeki.peak.inventory.api.InventoryCloseSnapshotSummary
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class InventoryCloseSnapshotService(
    private val jdbcTemplate: JdbcTemplate,
) : InventoryCloseSnapshotPort {
    override fun closeSnapshotSummary(
        tenantId: UUID,
        propertyId: UUID,
        businessDate: LocalDate,
    ): InventoryCloseSnapshotSummary {
        return jdbcTemplate.query(
            """
            SELECT
                (
                    SELECT count(*)
                    FROM stock_levels level
                    JOIN inventory_locations location
                      ON location.tenant_id = level.tenant_id
                     AND location.id = level.location_id
                    WHERE level.tenant_id = ?
                      AND location.property_id = ?
                      AND level.quantity <= level.reorder_level
                ) AS low_stock_items,
                (
                    SELECT round(COALESCE(sum(movement.total_cost), 0), 2)
                    FROM stock_movements movement
                    WHERE movement.tenant_id = ?
                      AND movement.property_id = ?
                      AND movement.business_date = ?
                      AND movement.type = 'waste'
                ) AS waste_total,
                (
                    SELECT round(COALESCE(
                        sum(level.quantity * level.average_cost), 0
                    ), 2)
                    FROM stock_levels level
                    JOIN inventory_locations location
                      ON location.tenant_id = level.tenant_id
                     AND location.id = level.location_id
                    WHERE level.tenant_id = ?
                      AND location.property_id = ?
                ) AS inventory_value
            """.trimIndent(),
            { rs, _ ->
                InventoryCloseSnapshotSummary(
                    lowStockItems = rs.getInt("low_stock_items"),
                    wasteTotal = rs.getBigDecimal("waste_total").setScale(2),
                    inventoryValue = rs.getBigDecimal(
                        "inventory_value",
                    ).setScale(2),
                )
            },
            tenantId,
            propertyId,
            tenantId,
            propertyId,
            businessDate,
            tenantId,
            propertyId,
        ).single()
    }
}
