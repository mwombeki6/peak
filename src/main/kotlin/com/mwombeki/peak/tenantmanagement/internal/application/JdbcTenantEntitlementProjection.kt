package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.tenantmanagement.api.TenantEntitlementProjectionPort
import java.time.Instant
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

    override fun billingLifecycleState(tenantId: UUID): String? {
        return jdbcTemplate.query(
            "SELECT lifecycle_status FROM tenant_control_states WHERE tenant_id = ?",
            { rs, _ -> rs.getString("lifecycle_status") },
            tenantId,
        ).firstOrNull()
    }

    override fun applyBillingLifecycle(
        tenantId: UUID,
        lifecycleStatus: String,
        subscriptionStatus: String,
        graceEndsAt: Instant?,
    ): String? {
        val current = billingLifecycleState(tenantId) ?: return null

        // The guard that keeps billing in its lane. A tenant being offboarded, frozen by an
        // operator, or already terminated must not be returned to service because a grant
        // happened to still be live.
        if (current !in BILLING_MANAGED_STATES) {
            return null
        }
        if (current == lifecycleStatus) {
            return null
        }

        val updated = jdbcTemplate.update(
            """
            UPDATE tenant_control_states
            SET lifecycle_status = ?,
                subscription_status = ?,
                updated_at = now(),
                version = version + 1
            WHERE tenant_id = ?
              AND lifecycle_status = ?
            """.trimIndent(),
            lifecycleStatus,
            subscriptionStatus,
            tenantId,
            current,
        )
        if (updated == 0) {
            // Someone moved it between the read and the write. Their decision wins.
            return null
        }

        jdbcTemplate.update(
            """
            UPDATE tenant_subscriptions
            SET status = ?, grace_period_ends_at = ?, updated_at = now()
            WHERE tenant_id = ?
              AND status IN ('trialing', 'active', 'past_due', 'paused')
            """.trimIndent(),
            subscriptionStatus,
            graceEndsAt?.let { java.sql.Timestamp.from(it) },
            tenantId,
        )

        return current
    }

    private companion object {
        /**
         * The only states billing may move a tenant between.
         *
         * Deliberately excludes frozen, archived, offboarding, terminated and cancelled:
         * those are operator decisions, and a payment or a lapse must not overturn one.
         */
        val BILLING_MANAGED_STATES = setOf("trial", "active", "restricted", "suspended")
    }
}
