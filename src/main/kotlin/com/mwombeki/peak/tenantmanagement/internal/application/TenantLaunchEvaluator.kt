package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.tenantmanagement.api.TenantOnboardingNextAction
import com.mwombeki.peak.tenantmanagement.api.TenantOnboardingResponse
import com.mwombeki.peak.tenantmanagement.api.TenantOnboardingStepView
import com.mwombeki.peak.usermanagement.api.TenantAdministratorReadinessPort
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Short tenant launch machine: registered → Keycloak admin → Pay Peak when
 * unpaid → can create properties. Peak SaaS collection is Peak's own Snippe
 * merchant (`peak.platformbilling`), not a hotel `payment_provider_accounts`
 * row. POS cashier PIN is not this wizard.
 */
@Component
class TenantLaunchEvaluator(
    private val jdbcTemplate: JdbcTemplate,
    private val administratorReadinessPort: TenantAdministratorReadinessPort,
    private val requestContextHolder: RequestContextHolder,
) {
    fun evaluate(tenantId: UUID): TenantOnboardingResponse {
        val registered = tenantExists(tenantId)
        if (!registered) {
            throw IllegalArgumentException("Tenant was not found")
        }
        val administratorStatus = administratorStatus(tenantId)
        val adminReady = administratorStatus == "ready"
        val peakCovered = peakCovered(tenantId)
        val openPurchaseId = openPurchaseId(tenantId)
        val propertyCount = propertyCount(tenantId)
        val propertyModuleEnabled = propertyModuleEnabled(tenantId)
        val canCreateProperties = adminReady && peakCovered

        val steps = listOf(
            TenantOnboardingStepView(
                key = STEP_REGISTERED,
                sequence = 1,
                status = STATUS_SATISFIED,
                required = true,
                detail = "Tenant account is registered.",
            ),
            TenantOnboardingStepView(
                key = STEP_STRONG_ADMIN,
                sequence = 2,
                status = if (adminReady) STATUS_SATISFIED else STATUS_BLOCKED,
                required = true,
                detail = when (administratorStatus) {
                    "ready" -> "A Keycloak-linked tenant administrator can sign in."
                    "invited" -> "The initial administrator invitation is awaiting acceptance."
                    else -> "A Keycloak (email/OIDC) tenant administrator must be linked before this account can create properties."
                },
            ),
            TenantOnboardingStepView(
                key = STEP_PAY_PEAK,
                sequence = 3,
                status = if (peakCovered) STATUS_SATISFIED else STATUS_BLOCKED,
                required = true,
                detail = if (peakCovered) {
                    "Peak cover is current (trial or paid). Collection uses Peak's merchant, not a hotel payment account."
                } else {
                    "Buy Peak. Mobile money is pushed to the payer; Peak collects into its own Snippe merchant."
                },
            ),
            TenantOnboardingStepView(
                key = STEP_CAN_CREATE_PROPERTIES,
                sequence = 4,
                status = if (canCreateProperties) STATUS_SATISFIED else STATUS_PENDING,
                required = true,
                detail = if (canCreateProperties) {
                    "This Keycloak administrator can create hotels."
                } else if (!adminReady) {
                    "Creating a property waits on a Keycloak-linked administrator."
                } else {
                    "Creating a property waits on Peak cover (trial or a paid subscription)."
                },
            ),
        )

        val currentStep = when {
            !adminReady -> STEP_STRONG_ADMIN
            !peakCovered -> STEP_PAY_PEAK
            else -> STEP_CAN_CREATE_PROPERTIES
        }
        val workflowStatus = when {
            canCreateProperties -> "ready"
            else -> "blocked"
        }

        return TenantOnboardingResponse(
            tenantId = tenantId,
            workflowStatus = workflowStatus,
            currentStep = currentStep,
            canCreateProperties = canCreateProperties,
            nextAction = nextAction(
                tenantId = tenantId,
                adminReady = adminReady,
                administratorStatus = administratorStatus,
                peakCovered = peakCovered,
                openPurchaseId = openPurchaseId,
                propertyModuleEnabled = propertyModuleEnabled,
                propertyCount = propertyCount,
            ),
            steps = steps,
        )
    }

    private fun nextAction(
        tenantId: UUID,
        adminReady: Boolean,
        administratorStatus: String,
        peakCovered: Boolean,
        openPurchaseId: UUID?,
        propertyModuleEnabled: Boolean,
        propertyCount: Int,
    ): TenantOnboardingNextAction {
        if (!adminReady) {
            if (administratorStatus == "invited") {
                return TenantOnboardingNextAction(
                    step = STEP_STRONG_ADMIN,
                    title = "Ask the invited administrator to accept access",
                    why = "A Keycloak administrator invitation is outstanding. Owner access is email/OIDC.",
                    method = "GET",
                    path = "/api/v1/platform/tenants/$tenantId/onboarding",
                    bodyHint = null,
                )
            }
            return TenantOnboardingNextAction(
                step = STEP_STRONG_ADMIN,
                title = "Link a Keycloak tenant administrator",
                why = "A STRONG (Keycloak email/OIDC) manager is required before this account can create properties.",
                method = "POST",
                path = "/api/v1/platform/tenants/$tenantId/administrators",
                bodyHint = mapOf(
                    "fullName" to "Hotel owner or GM",
                    "email" to "gm@hotel.example",
                    "issuer" to "https://auth.example/realms/peak",
                    "subject" to "<Keycloak user subject>",
                ),
            )
        }
        if (!peakCovered) {
            // Paying Peak is a tenant-identity action (`staff_permission` on
            // `.../billing/purchases`); a platform session gets a 403 on it. The platform
            // operator's job ends at provisioning the administrator — advertising the real
            // action here would be showing a button that only 403s, which is worse than not
            // showing one. See docs/api-gaps-for-claude.md in peak-platform-web for the
            // reproduction this closes.
            return if (isPlatformSession()) {
                TenantOnboardingNextAction(
                    step = STEP_PAY_PEAK,
                    title = "Waiting on the tenant to pay Peak",
                    why = "Peak cover is billed with tenant identity, after the administrator " +
                        "activates. A platform session cannot execute this step.",
                    method = "GET",
                    path = "/api/v1/platform/tenants/$tenantId/onboarding",
                    bodyHint = null,
                )
            } else {
                payPeak(tenantId, openPurchaseId)
            }
        }
        if (!propertyModuleEnabled) {
            return TenantOnboardingNextAction(
                step = STEP_CAN_CREATE_PROPERTIES,
                title = "Enable the property module",
                why = "The first hotel needs the property module enabled on this tenant.",
                method = "POST",
                path = "/api/v1/tenants/$tenantId/modules",
                bodyHint = mapOf("moduleId" to "property"),
            )
        }
        if (propertyCount == 0) {
            return TenantOnboardingNextAction(
                step = STEP_CAN_CREATE_PROPERTIES,
                title = "Bootstrap the first hotel",
                why = "Create a property with a distinct id and attach this Keycloak administrator. Rooms and rates are not seeded.",
                method = "POST",
                path = "/api/v1/properties/bootstrap",
                bodyHint = mapOf(
                    "name" to "First hotel",
                    "code" to "HTL1",
                ),
            )
        }
        val propertyId = firstPropertyId(tenantId)
        return TenantOnboardingNextAction(
            step = STEP_CAN_CREATE_PROPERTIES,
            title = "Continue hotel go-live",
            why = "A property already exists. Follow that hotel's onboarding nextAction.",
            method = "GET",
            path = "/api/v1/properties/$propertyId/onboarding",
            bodyHint = null,
        )
    }

    private fun payPeak(tenantId: UUID, openPurchaseId: UUID?): TenantOnboardingNextAction {
        if (openPurchaseId != null) {
            return TenantOnboardingNextAction(
                step = STEP_PAY_PEAK,
                title = "Pay Peak",
                why = "An open Peak purchase is waiting. Direct-push mobile money collects into Peak's own merchant. This is not hotel guest collection.",
                method = "POST",
                path = "/api/v1/tenants/$tenantId/billing/purchases/$openPurchaseId/payments",
                bodyHint = mapOf(
                    "payerMsisdn" to "+2557XXXXXXXX",
                    "method" to "MOBILE_MONEY",
                ),
            )
        }
        return TenantOnboardingNextAction(
            step = STEP_PAY_PEAK,
            title = "Pay Peak",
            why = "Buy Peak. Direct-push mobile money collects into Peak's own merchant. Do not configure a hotel payment account for this.",
            method = "POST",
            path = "/api/v1/tenants/$tenantId/billing/purchases",
            bodyHint = mapOf(
                "lines" to listOf(mapOf("productCode" to "peak_core")),
                "termMonths" to 12,
            ),
        )
    }

    private fun administratorStatus(tenantId: UUID): String {
        return if (isPlatformSession()) {
            administratorReadinessPort.readiness(tenantId).status
        } else {
            tenantVisibleAdministratorStatus(tenantId)
        }
    }

    private fun isPlatformSession(): Boolean =
        when (requestContextHolder.currentOrNull()?.identity) {
            is RequestIdentity.Platform, is RequestIdentity.Support -> true
            else -> false
        }

    /**
     * `tenant_administrator_readiness()` is platform-only (it refuses a mixed
     * tenant session). Tenant launch still has to answer the same question from
     * tables the tenant may read.
     */
    private fun tenantVisibleAdministratorStatus(tenantId: UUID): String {
        val ready = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM users tenant_user
                JOIN user_tenant_roles assignment
                  ON assignment.tenant_id = tenant_user.tenant_id
                 AND assignment.user_id = tenant_user.id
                JOIN tenant_roles role
                  ON role.tenant_id = assignment.tenant_id
                 AND role.id = assignment.tenant_role_id
                WHERE tenant_user.tenant_id = ?
                  AND tenant_user.status = 'active'
                  AND tenant_user.is_active = true
                  AND tenant_user.deleted_at IS NULL
                  AND (tenant_user.locked_until IS NULL OR tenant_user.locked_until <= now())
                  AND role.code = 'tenant_admin'
                  AND role.is_system = true
                  AND role.is_active = true
                  AND EXISTS (
                      SELECT 1
                      FROM identity_links identity
                      WHERE identity.tenant_id = tenant_user.tenant_id
                        AND identity.user_id = tenant_user.id
                        AND identity.identity_mode = 'tenant'
                        AND identity.provider = 'oidc'
                        AND identity.revoked_at IS NULL
                  )
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
        ) == true
        if (ready) {
            return "ready"
        }
        return "missing"
    }

    private fun tenantExists(tenantId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM tenants
                WHERE id = ? AND deleted_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
        ) == true
    }

    private fun peakCovered(tenantId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM tenant_subscriptions
                WHERE tenant_id = ?
                  AND status IN ('trialing', 'active')
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
        ) == true
    }

    private fun propertyModuleEnabled(tenantId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM tenant_modules
                WHERE tenant_id = ? AND module_id = 'property' AND is_enabled = true
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
        ) == true
    }

    private fun openPurchaseId(tenantId: UUID): UUID? {
        return jdbcTemplate.query(
            """
            SELECT id FROM peak_purchases
            WHERE tenant_id = ?
              AND status IN ('quoted', 'awaiting_payment')
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            tenantId,
        ).firstOrNull()
    }

    private fun propertyCount(tenantId: UUID): Int {
        return jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM properties
            WHERE tenant_id = ? AND deleted_at IS NULL
            """.trimIndent(),
            Int::class.java,
            tenantId,
        ) ?: 0
    }

    private fun firstPropertyId(tenantId: UUID): UUID {
        return jdbcTemplate.queryForObject(
            """
            SELECT id FROM properties
            WHERE tenant_id = ? AND deleted_at IS NULL
            ORDER BY created_at
            LIMIT 1
            """.trimIndent(),
            UUID::class.java,
            tenantId,
        ) ?: error("Property count was non-zero but no property id was found")
    }

    private companion object {
        const val STEP_REGISTERED = "registered"
        const val STEP_STRONG_ADMIN = "strong_admin"
        const val STEP_PAY_PEAK = "pay_peak"
        const val STEP_CAN_CREATE_PROPERTIES = "can_create_properties"
        const val STATUS_SATISFIED = "satisfied"
        const val STATUS_BLOCKED = "blocked"
        const val STATUS_PENDING = "pending"
    }
}
