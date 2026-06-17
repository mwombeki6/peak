package com.mwombeki.peak.platformgovernance.internal

import com.mwombeki.peak.platformgovernance.api.TenantGovernancePort
import com.mwombeki.peak.platformgovernance.api.GovernanceActionResponse
// REMOVED: The restricted internal import is gone!
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TenantGovernanceService(
    private val jdbcTemplate: JdbcTemplate // We only need the database template here
) : TenantGovernancePort {

    @Transactional
    override fun approveTenant(tenantId: UUID, operatorId: UUID, reason: String): GovernanceActionResponse {
        val currentStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM tenants WHERE id = ?",
            String::class.java,
            tenantId
        ) ?: throw IllegalArgumentException("Tenant with ID $tenantId not found")

        if (currentStatus != "PENDING_VERIFICATION") {
            throw IllegalStateException("Cannot approve a tenant that is currently $currentStatus")
        }

        // 1. Update the tenant status
        jdbcTemplate.update(
            "UPDATE tenants SET status = 'ACTIVE', updated_at = now() WHERE id = ?",
            tenantId
        )

        // 2. Log directly to the audit trail table since we have database access anyway!
        jdbcTemplate.update(
            """
            INSERT INTO tenant_lifecycle_logs (tenant_id, operator_id, previous_status, new_status, reason)
            VALUES (?, ?, ?, 'ACTIVE', ?)
            """.trimIndent(),
            tenantId,
            operatorId,
            currentStatus,
            reason
        )

        return GovernanceActionResponse(
            tenantId = tenantId,
            previousStatus = currentStatus,
            newStatus = "ACTIVE",
            message = "Hotel tenant successfully approved and activated."
        )
    }

    @Transactional
    override fun suspendTenant(tenantId: UUID, operatorId: UUID, reason: String): GovernanceActionResponse {
        val currentStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM tenants WHERE id = ?",
            String::class.java,
            tenantId
        ) ?: throw IllegalArgumentException("Tenant with ID $tenantId not found")

        jdbcTemplate.update(
            "UPDATE tenants SET status = 'SUSPENDED', updated_at = now() WHERE id = ?",
            tenantId
        )

        jdbcTemplate.update(
            """
            INSERT INTO tenant_lifecycle_logs (tenant_id, operator_id, previous_status, new_status, reason)
            VALUES (?, ?, ?, 'SUSPENDED', ?)
            """.trimIndent(),
            tenantId,
            operatorId,
            currentStatus,
            reason
        )

        return GovernanceActionResponse(
            tenantId = tenantId,
            previousStatus = currentStatus,
            newStatus = "SUSPENDED",
            message = "Hotel tenant account has been temporarily frozen."
        )
    }
}