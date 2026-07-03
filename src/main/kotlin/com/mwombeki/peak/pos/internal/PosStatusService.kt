package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.pos.api.PosNightAuditSummary
import com.mwombeki.peak.pos.api.PosStatusPort
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
}
