package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.shared.context.TenantRequestContext
import com.mwombeki.peak.usermanagement.api.TenantPrivilegedAccessEvent
import com.mwombeki.peak.usermanagement.api.TenantPrivilegedAccessEvidencePort
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Serves the tenant's own privileged-access history.
 *
 * Scope is enforced twice and in different places. Binding the tenant request
 * context sets the database session identity, and the view filters on
 * `current_tenant_id()` itself, so a missing or wrong binding yields an empty
 * result rather than another tenant's history. The service adds no tenant
 * predicate of its own, deliberately: a WHERE clause here would look like the
 * control while the real one lived in the view, and the two could drift.
 */
@Service
class TenantPrivilegedAccessEvidenceService(
    private val jdbcTemplate: JdbcTemplate,
    private val tenantRequestContext: TenantRequestContext,
) : TenantPrivilegedAccessEvidencePort {

    @Transactional(readOnly = true)
    override fun listEvidence(limit: Int): List<TenantPrivilegedAccessEvent> {
        require(limit in 1..500) {
            "Privileged access evidence limit must be between 1 and 500"
        }
        tenantRequestContext.bind()

        return jdbcTemplate.query(
            """
            SELECT access_id, support_ticket_id, operator_name, action_code,
                   operation_code, reason, event_type, occurred_at, starts_at,
                   expires_at, max_uses, use_count, denial_reason
            FROM tenant_privileged_access_evidence
            ORDER BY occurred_at DESC, access_id DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                TenantPrivilegedAccessEvent(
                    accessId = rs.getObject("access_id", UUID::class.java),
                    supportTicketId = rs.getObject("support_ticket_id", UUID::class.java),
                    operatorName = rs.getString("operator_name"),
                    actionCode = rs.getString("action_code"),
                    operationCode = rs.getString("operation_code"),
                    reason = rs.getString("reason"),
                    eventType = rs.getString("event_type"),
                    occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                    startsAt = rs.getTimestamp("starts_at").toInstant(),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                    maxUses = rs.getInt("max_uses"),
                    useCount = rs.getInt("use_count"),
                    denialReason = rs.getString("denial_reason"),
                )
            },
            limit,
        )
    }
}
