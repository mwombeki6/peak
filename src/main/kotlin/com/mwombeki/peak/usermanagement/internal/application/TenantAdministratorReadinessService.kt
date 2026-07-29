package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.usermanagement.api.TenantAdministratorReadiness
import com.mwombeki.peak.usermanagement.api.TenantAdministratorReadinessPort
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class TenantAdministratorReadinessService(
    private val jdbcTemplate: JdbcTemplate,
) : TenantAdministratorReadinessPort {

    override fun readiness(tenantId: UUID): TenantAdministratorReadiness {
        return jdbcTemplate.query(
            """
            SELECT effective_administrators,
                   pending_initial_invitations,
                   administrator_status
            FROM public.tenant_administrator_readiness(?)
            """.trimIndent(),
            { resultSet, _ ->
                TenantAdministratorReadiness(
                    tenantId = tenantId,
                    effectiveAdministrators = resultSet.getInt("effective_administrators"),
                    pendingInitialInvitations = resultSet.getInt("pending_initial_invitations"),
                    status = resultSet.getString("administrator_status"),
                )
            },
            tenantId,
        ).single()
    }
}
