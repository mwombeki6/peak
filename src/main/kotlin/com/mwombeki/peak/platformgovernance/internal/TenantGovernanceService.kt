package com.mwombeki.peak.platformgovernance.internal

import com.mwombeki.peak.platformgovernance.api.GovernanceActionResponse
import com.mwombeki.peak.platformgovernance.api.TenantGovernancePort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class TenantGovernanceService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val objectMapper: ObjectMapper,
) : TenantGovernancePort {

    @Transactional
    override fun approveTenant(
        tenantId: UUID,
        operatorId: UUID,
        reason: String,
    ): GovernanceActionResponse {
        bindPlatformContext(operatorId)
        return transitionTenant(
            tenantId = tenantId,
            operatorId = operatorId,
            allowedCurrentStatuses = setOf("trial", "suspended", "frozen"),
            newStatus = "active",
            lifecycleEventType = "activated",
            reason = reason,
            message = "Tenant account has been activated.",
        )
    }

    @Transactional
    override fun suspendTenant(
        tenantId: UUID,
        operatorId: UUID,
        reason: String,
    ): GovernanceActionResponse {
        bindPlatformContext(operatorId)
        return transitionTenant(
            tenantId = tenantId,
            operatorId = operatorId,
            allowedCurrentStatuses = setOf("trial", "active"),
            newStatus = "suspended",
            lifecycleEventType = "suspended",
            reason = reason,
            message = "Tenant account has been suspended.",
        )
    }

    private fun transitionTenant(
        tenantId: UUID,
        operatorId: UUID,
        allowedCurrentStatuses: Set<String>,
        newStatus: String,
        lifecycleEventType: String,
        reason: String,
        message: String,
    ): GovernanceActionResponse {
        require(reason.isNotBlank()) {
            "Governance reason is required"
        }

        val currentStatus = currentTenantStatusForUpdate(tenantId)
            ?: throw IllegalArgumentException("Tenant was not found")

        require(currentStatus in allowedCurrentStatuses) {
            "Tenant cannot move from $currentStatus to $newStatus"
        }

        jdbcTemplate.update(
            """
            UPDATE tenants
            SET status = ?,
                updated_at = now()
            WHERE id = ?
              AND deleted_at IS NULL
            """.trimIndent(),
            newStatus,
            tenantId,
        )

        jdbcTemplate.update(
            """
            INSERT INTO tenant_lifecycle_events (
                tenant_id,
                event_type,
                status,
                reason,
                metadata,
                performed_by_platform_user_id
            )
            VALUES (?, ?, 'completed', ?, ?::jsonb, ?)
            """.trimIndent(),
            tenantId,
            lifecycleEventType,
            reason.trim(),
            objectMapper.writeValueAsString(
                mapOf(
                    "previousStatus" to currentStatus,
                    "newStatus" to newStatus,
                ),
            ),
            operatorId,
        )

        return GovernanceActionResponse(
            tenantId = tenantId,
            previousStatus = currentStatus,
            newStatus = newStatus,
            message = message,
        )
    }

    private fun currentTenantStatusForUpdate(tenantId: UUID): String? {
        return jdbcTemplate.query(
            """
            SELECT status
            FROM tenants
            WHERE id = ?
              AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getString("status") },
            tenantId,
        ).firstOrNull()
    }

    private fun bindPlatformContext(expectedOperatorId: UUID) {
        val identity = requestContextHolder.current().identity
        val platformUserId = when (identity) {
            is RequestIdentity.Platform -> identity.platformUserId
            is RequestIdentity.Support -> identity.platformUserId
            else -> throw IllegalStateException("Platform identity is required")
        }
        require(platformUserId == expectedOperatorId) {
            "Governance operator must match the active request identity"
        }
        databaseSessionContext.bind(identity)
    }
}
