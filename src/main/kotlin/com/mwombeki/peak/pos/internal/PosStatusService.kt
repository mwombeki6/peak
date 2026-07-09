package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.pos.api.PosNightAuditSummary
import com.mwombeki.peak.pos.api.PosCloseSnapshotSummary
import com.mwombeki.peak.pos.api.PosStatusPort
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class PosStatusService(
    private val jdbcTemplate: JdbcTemplate,
) : PosStatusPort {
    override fun nightAuditSummary(
        tenantId: UUID,
        propertyId: UUID,
    ): PosNightAuditSummary {
        val count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM pos_sessions ps
            JOIN outlets o
              ON o.tenant_id = ps.tenant_id
             AND o.id = ps.outlet_id
            WHERE ps.tenant_id = ?
              AND o.property_id = ?
              AND ps.status IN ('open', 'pending_variance_approval')
            """.trimIndent(),
            Int::class.java,
            tenantId,
            propertyId,
        ) ?: 0
        return PosNightAuditSummary(openOrUnapprovedSessions = count)
    }

    override fun closeSnapshotSummary(
        tenantId: UUID,
        propertyId: UUID,
        businessDate: LocalDate,
    ): PosCloseSnapshotSummary {
        return jdbcTemplate.query(
            """
            SELECT COUNT(*) FILTER (
                       WHERE status = 'closed'
                         AND settlement_status NOT IN (
                             'confirmed', 'transferred', 'legacy'
                         )
                   ) AS closed_unsettled_orders,
                   round(COALESCE(sum(total_amount) FILTER (
                       WHERE status = 'closed'
                         AND settlement_status IN (
                             'confirmed', 'transferred', 'legacy'
                         )
                   ), 0), 2) AS revenue
            FROM pos_orders
            WHERE tenant_id = ?
              AND property_id = ?
              AND business_date = ?
              AND deleted_at IS NULL
            """.trimIndent(),
            { rs, _ ->
                PosCloseSnapshotSummary(
                    closedUnsettledOrders = rs.getInt(
                        "closed_unsettled_orders",
                    ),
                    revenue = rs.getBigDecimal("revenue").setScale(2),
                )
            },
            tenantId,
            propertyId,
            businessDate,
        ).single()
    }
}
