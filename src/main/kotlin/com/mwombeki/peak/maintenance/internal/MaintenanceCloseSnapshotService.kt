package com.mwombeki.peak.maintenance.internal

import com.mwombeki.peak.maintenance.api.MaintenanceCloseSnapshotPort
import com.mwombeki.peak.maintenance.api.MaintenanceCloseSnapshotSummary
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class MaintenanceCloseSnapshotService(
    private val jdbcTemplate: JdbcTemplate,
) : MaintenanceCloseSnapshotPort {
    override fun closeSnapshotSummary(
        tenantId: UUID,
        propertyId: UUID,
    ): MaintenanceCloseSnapshotSummary {
        return jdbcTemplate.query(
            """
            SELECT
                COUNT(DISTINCT block.room_id) FILTER (
                    WHERE block.status = 'active'
                      AND block.block_type = 'out_of_order'
                ) AS out_of_order_rooms,
                COUNT(DISTINCT block.room_id) FILTER (
                    WHERE block.status = 'active'
                      AND block.block_type = 'out_of_service'
                ) AS out_of_service_rooms,
                (
                    SELECT count(*)
                    FROM maintenance_requests request
                    WHERE request.tenant_id = ?
                      AND request.property_id = ?
                      AND request.status IN (
                          'open', 'assigned', 'in_progress', 'deferred'
                      )
                ) AS open_exceptions
            FROM room_blocks block
            WHERE block.tenant_id = ?
              AND block.property_id = ?
            """.trimIndent(),
            { rs, _ ->
                MaintenanceCloseSnapshotSummary(
                    outOfOrderRooms = rs.getInt("out_of_order_rooms"),
                    outOfServiceRooms = rs.getInt("out_of_service_rooms"),
                    openExceptions = rs.getInt("open_exceptions"),
                )
            },
            tenantId,
            propertyId,
            tenantId,
            propertyId,
        ).single()
    }
}
