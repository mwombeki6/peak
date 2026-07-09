package com.mwombeki.peak.housekeeping.internal

import com.mwombeki.peak.housekeeping.api.HousekeepingCloseSnapshotPort
import com.mwombeki.peak.housekeeping.api.HousekeepingCloseSnapshotSummary
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class HousekeepingCloseSnapshotService(
    private val jdbcTemplate: JdbcTemplate,
) : HousekeepingCloseSnapshotPort {
    override fun closeSnapshotSummary(
        tenantId: UUID,
        propertyId: UUID,
        businessDate: LocalDate,
    ): HousekeepingCloseSnapshotSummary {
        val states = jdbcTemplate.query(
            """
            SELECT status, count(*) AS task_count
            FROM housekeeping_tasks
            WHERE tenant_id = ?
              AND property_id = ?
              AND scheduled_date = ?
            GROUP BY status
            ORDER BY status
            """.trimIndent(),
            { rs, _ -> rs.getString("status") to rs.getInt("task_count") },
            tenantId,
            propertyId,
            businessDate,
        ).toMap()
        return HousekeepingCloseSnapshotSummary(
            states = states,
            openTasks = states.filterKeys {
                it !in setOf("completed", "skipped", "cancelled")
            }.values.sum(),
        )
    }
}
