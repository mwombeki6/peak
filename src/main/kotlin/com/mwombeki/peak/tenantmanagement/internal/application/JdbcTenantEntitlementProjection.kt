package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.tenantmanagement.api.TenantEntitlementProjectionPort
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * The only writer of `tenant_modules` on the billing path.
 *
 * Runs on whatever session the caller has bound — the reconciler binds the tenant before
 * calling — so row-level security still applies. Nothing here elevates.
 */
@Component
class JdbcTenantEntitlementProjection(
    private val jdbcTemplate: JdbcTemplate,
) : TenantEntitlementProjectionPort {

    override fun activateModuleIfNeverActivated(tenantId: UUID, moduleId: String): Boolean {
        // ON CONFLICT DO NOTHING is the whole safety property: if a row exists the tenant
        // has already decided about this module, including having deliberately turned it
        // off, and an automated process must not argue with that.
        return jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
            VALUES (?, ?, true, false)
            ON CONFLICT (tenant_id, module_id) DO NOTHING
            """.trimIndent(),
            tenantId,
            moduleId,
        ) > 0
    }

    override fun deactivateModule(tenantId: UUID, moduleId: String, reason: String): Boolean {
        return jdbcTemplate.update(
            """
            UPDATE tenant_modules
            SET is_enabled = false, updated_at = now()
            WHERE tenant_id = ? AND module_id = ? AND is_enabled = true
            """.trimIndent(),
            tenantId,
            moduleId,
        ) > 0
    }

    override fun enabledModules(tenantId: UUID): Set<String> {
        return jdbcTemplate.query(
            "SELECT module_id FROM tenant_modules WHERE tenant_id = ? AND is_enabled = true",
            { rs, _ -> rs.getString("module_id") },
            tenantId,
        ).toSet()
    }
}
