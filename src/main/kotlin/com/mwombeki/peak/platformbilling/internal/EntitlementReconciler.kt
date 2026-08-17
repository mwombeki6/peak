package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.property.api.PropertyModuleProjectionPort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.tenantmanagement.api.TenantEntitlementProjectionPort
import com.mwombeki.peak.usermanagement.api.TenantModulePermissionBootstrapPort
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * Makes what a tenant has paid for and what they can actually do agree.
 *
 * ## Why convergence rather than a one-shot grant
 *
 * `can_access_module` reads `tenant_modules` and `property_modules`; it has never consulted
 * entitlements. Before this, buying a module enabled it once and nothing ever turned it
 * off, so a lapsed subscription revoked precisely nothing. Converging on a loop is what
 * makes expiry mean something.
 *
 * ## Why the convergence is asymmetric
 *
 * A symmetric reconciler — "make actual equal desired" — would fight the customer. An
 * administrator who deliberately turns POS off while holding a live POS grant would find it
 * back on within the minute, forever. So:
 *
 * - entitled, never activated  -> turn on, and remember that we did
 * - entitled, activated before -> **leave alone**, whatever its current state
 * - not entitled               -> turn off, unconditionally
 *
 * It only ever converges toward "must be off" and "has never been decided". Everything in
 * between belongs to the tenant.
 */
@Component
class EntitlementReconciler(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val tenantProjection: TenantEntitlementProjectionPort,
    private val propertyProjection: PropertyModuleProjectionPort,
    private val permissionBootstrap: TenantModulePermissionBootstrapPort,
) {
    private val log = LoggerFactory.getLogger(EntitlementReconciler::class.java)

    /**
     * Converges one tenant. Safe to call repeatedly; a no-op once actual matches desired.
     *
     * Failures are contained per tenant rather than allowed to abort a sweep: a dangling
     * grant for a deleted property must not stop every other tenant from converging.
     */
    fun reconcileTenant(tenantId: UUID, correlationId: String): ReconcileOutcome {
        return try {
            requireNotNull(
                transactionTemplate.execute {
                    databaseSessionContext.bind(
                        RequestIdentity.Public(tenantId = tenantId, correlationId = correlationId),
                    )
                    converge(tenantId)
                },
            )
        } catch (ex: Exception) {
            log.warn("Entitlement reconciliation failed for tenant {}", tenantId, ex)
            recordFailure(tenantId, correlationId, ex)
            ReconcileOutcome(activated = 0, deactivated = 0, failed = true)
        }
    }

    private fun converge(tenantId: UUID): ReconcileOutcome {
        val desired = desiredModules(tenantId)
        var activated = 0
        var deactivated = 0

        desired.forEach { (moduleId, scope) ->
            if (!scope.autoActivate) {
                // A capacity change — a bigger room limit — activates nothing. Without this
                // distinction the reconciler could not tell buying POS from buying headroom.
                return@forEach
            }

            if (tenantProjection.activateModuleIfNeverActivated(tenantId, moduleId)) {
                activated += 1
                recordActivation(tenantId, moduleId)
                // Enabling the module is only half of becoming visible: can_access_module
                // also wants the user to hold a permission inside it. Without this the
                // customer pays, the flag flips, and the screens stay exactly as absent.
                permissionBootstrap.bootstrapModulePermissions(tenantId, moduleId)
            }

            if (scope.propertyIds.isNotEmpty()) {
                scope.propertyIds.forEach { propertyId ->
                    if (propertyProjection.activateModuleIfNeverActivated(
                            tenantId,
                            propertyId,
                            moduleId,
                        )
                    ) {
                        activated += 1
                    }
                }
                // A property dropped from a renewal loses the module, while the ones still
                // covered keep it.
                deactivated += propertyProjection.deactivateModuleExcept(
                    tenantId,
                    moduleId,
                    scope.propertyIds,
                )
            }
        }

        // Anything enabled that is no longer entitled goes off. This is the half that makes
        // a cancelled subscription real.
        val entitledModules = desired.keys
        tenantProjection.enabledModules(tenantId)
            .filter { it !in entitledModules && it !in NEVER_REVOKED_MODULES }
            .forEach { moduleId ->
                if (tenantProjection.deactivateModule(tenantId, moduleId, "entitlement_lapsed")) {
                    deactivated += 1
                    recordDeactivation(tenantId, moduleId, "entitlement_lapsed")
                }
                deactivated += propertyProjection.deactivateModuleEverywhere(tenantId, moduleId)
            }

        return ReconcileOutcome(activated = activated, deactivated = deactivated, failed = false)
    }

    /**
     * What the tenant is entitled to right now, read through the same resolver that
     * `can_access_module` and `assert_tenant_capacity` use. Reading grants directly here
     * would be a second opinion, and the two would eventually disagree.
     */
    private fun desiredModules(tenantId: UUID): Map<String, DesiredModule> {
        val rows = jdbcTemplate.query(
            """
            SELECT DISTINCT
                   substring(entitlement.code from 8) AS module_id,
                   grant_row.property_id,
                   coalesce(
                       (grant_row.granted_entitlements -> entitlement.code ->> 'auto_activate')::boolean,
                       true
                   ) AS auto_activate
            FROM peak_product_grants grant_row
            CROSS JOIN LATERAL jsonb_object_keys(grant_row.granted_entitlements) AS entitlement(code)
            -- A grant may name a property that has since been deleted. Excluded here
            -- rather than caught later: a failed insert aborts the whole transaction in
            -- Postgres, so one dangling grant would take the tenant's entire convergence
            -- down with it, and catching per property would not help.
            LEFT JOIN properties property
              ON property.id = grant_row.property_id
             AND property.tenant_id = grant_row.tenant_id
             AND property.deleted_at IS NULL
            WHERE grant_row.tenant_id = ?
              AND (grant_row.property_id IS NULL OR property.id IS NOT NULL)
              AND grant_row.status = 'active'
              AND grant_row.revoked_at IS NULL
              AND grant_row.starts_at <= now()
              AND (grant_row.ends_at IS NULL OR grant_row.ends_at > now())
              AND entitlement.code LIKE 'module.%'
              AND coalesce(
                      (grant_row.granted_entitlements -> entitlement.code ->> 'is_enabled')::boolean,
                      true
                  )
            """.trimIndent(),
            { rs, _ ->
                Triple(
                    rs.getString("module_id"),
                    rs.getObject("property_id", UUID::class.java),
                    rs.getBoolean("auto_activate"),
                )
            },
            tenantId,
        )

        return rows.groupBy { it.first }.mapValues { (_, group) ->
            DesiredModule(
                autoActivate = group.any { it.third },
                propertyIds = group.mapNotNull { it.second },
            )
        }
    }

    private fun recordActivation(tenantId: UUID, moduleId: String) {
        jdbcTemplate.update(
            """
            INSERT INTO peak_module_activations (tenant_id, module_id)
            VALUES (?, ?)
            ON CONFLICT (tenant_id, module_id) DO NOTHING
            """.trimIndent(),
            tenantId,
            moduleId,
        )
    }

    private fun recordDeactivation(tenantId: UUID, moduleId: String, reason: String) {
        jdbcTemplate.update(
            """
            UPDATE peak_module_activations
            SET last_deactivated_at = now(), last_deactivation_reason = ?, updated_at = now()
            WHERE tenant_id = ? AND module_id = ?
            """.trimIndent(),
            reason,
            tenantId,
            moduleId,
        )
    }

    private fun recordFailure(tenantId: UUID, correlationId: String, ex: Exception) {
        runCatching {
            transactionTemplate.execute {
                databaseSessionContext.bind(
                    RequestIdentity.Public(tenantId = tenantId, correlationId = correlationId),
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO peak_reconciliation_state (
                        tenant_id, consecutive_failures, last_error, next_run_at
                    ) VALUES (?, 1, ?, now() + interval '5 minutes')
                    ON CONFLICT (tenant_id) DO UPDATE
                    SET consecutive_failures = peak_reconciliation_state.consecutive_failures + 1,
                        last_error = excluded.last_error,
                        next_run_at = now() + interval '5 minutes',
                        updated_at = now()
                    """.trimIndent(),
                    tenantId,
                    ex.message?.take(500) ?: ex.javaClass.simpleName,
                )
            }
        }
    }

    data class ReconcileOutcome(
        val activated: Int,
        val deactivated: Int,
        val failed: Boolean,
    )

    private data class DesiredModule(
        val autoActivate: Boolean,
        val propertyIds: List<UUID>,
    )

    private companion object {
        /**
         * Never revoked by billing, whatever the subscription says.
         *
         * `tenant_admin` carries the subscription pages themselves. Switching it off for
         * non-payment would lock the tenant out of the only screen where they could pay,
         * which is a trap with no exit.
         */
        val NEVER_REVOKED_MODULES = setOf("tenant_admin")
    }
}
