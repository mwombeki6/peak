package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.tenantmanagement.api.PlatformTenantActivationPort
import com.mwombeki.peak.tenantmanagement.api.TenantActivationAction
import com.mwombeki.peak.tenantmanagement.api.TenantActivationGate
import com.mwombeki.peak.tenantmanagement.api.TenantActivationNotReadyException
import com.mwombeki.peak.tenantmanagement.api.TenantActivationReadiness
import com.mwombeki.peak.usermanagement.api.PlatformAccessPort
import com.mwombeki.peak.usermanagement.api.PlatformAccessRequest
import com.mwombeki.peak.usermanagement.api.TenantAdministratorReadinessPort
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class PlatformTenantActivationService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val platformAccessPort: PlatformAccessPort,
    private val operationalReadinessEvaluator: TenantOperationalReadinessEvaluator,
    private val administratorReadinessPort: TenantAdministratorReadinessPort,
) : PlatformTenantActivationPort {

    override fun readiness(tenantId: UUID): TenantActivationReadiness {
        return requireNotNull(
            transactionTemplate.execute {
                platformAccessPort.requireAuthorized(
                    PlatformAccessRequest(
                        tenantId = tenantId,
                        permissionCode = VIEW_PERMISSION,
                        operation = "platform.tenants.activation.readiness",
                    ),
                )
                evaluateAndReconcile(tenantId)
            },
        )
    }

    override fun requireReady(tenantId: UUID): TenantActivationReadiness {
        val readiness = readiness(tenantId)
        if (!readiness.ready) {
            throw TenantActivationNotReadyException(tenantId, readiness.blockerCodes)
        }
        return readiness
    }

    private fun evaluateAndReconcile(tenantId: UUID): TenantActivationReadiness {
        val state = tenantState(tenantId)
        val operational = operationalReadinessEvaluator.evidence(tenantId)
        val administrator = administratorReadinessPort.readiness(tenantId)
        val commercial = commercialEvidence(tenantId)

        val gates = listOf(
            gate(
                ACCOUNT,
                "Tenant account",
                operational.accountUsable && state.lifecycleStatus in setOf("trial", "active"),
                "Tenant must be an eligible trial account before activation.",
            ),
            gate(
                VERIFICATION,
                "Business verification",
                operational.businessProfileVerified,
                "The legal business profile and contact channels must be verified.",
            ),
            gate(
                ADMINISTRATOR,
                "Initial tenant administrator",
                administrator.status == "ready",
                when (administrator.status) {
                    "invited" -> "The initial administrator invitation is awaiting acceptance."
                    "ready" -> "An effective tenant administrator can authenticate."
                    else -> "An initial tenant administrator must be invited and accept access."
                },
            ),
            gate(
                SUBSCRIPTION,
                "Subscription",
                commercial.subscriptionReady,
                "A current trialing or active subscription must reference an active plan.",
            ),
            gate(
                CONFIGURATION,
                "Entitlements and configuration",
                commercial.configurationReady,
                "Plan assignment and desired configuration must be reconciled.",
            ),
            gate(
                BUSINESS_CONTACT,
                "Responsible business contact",
                operational.requiredBusinessContactPresent,
                "An active owner, signatory, or primary contact is required.",
            ),
            gate(
                REPORTING,
                "Operational reporting",
                operational.operationalReportRecipientPresent,
                "At least one consented operational-report recipient is required.",
            ),
            gate(
                TENANT_ADMIN_MODULE,
                "Tenant administration workspace",
                operational.tenantAdminModuleReady,
                "The tenant administration module must be enabled and configured.",
            ),
        )
        val blockerCodes = gates.filterNot(TenantActivationGate::satisfied).map(TenantActivationGate::code)
        val ready = blockerCodes.isEmpty()

        reconcileControlVerification(tenantId, operational.businessProfileVerified)
        reconcileOnboardingWorkflow(
            tenantId = tenantId,
            verificationReady = operational.businessProfileVerified,
            administratorReady = administrator.status == "ready",
            configurationReady = commercial.subscriptionReady && commercial.configurationReady,
            preActivationReady = ready,
            activated = state.lifecycleStatus == "active",
        )

        return TenantActivationReadiness(
            tenantId = tenantId,
            ready = ready,
            lifecycleStatus = state.lifecycleStatus,
            administratorStatus = administrator.status,
            effectiveAdministrators = administrator.effectiveAdministrators,
            pendingInitialInvitations = administrator.pendingInitialInvitations,
            gates = gates,
            blockerCodes = blockerCodes,
            nextActions = nextActions(tenantId, gates, administrator.status),
            evaluatedAt = Instant.now(),
        )
    }

    private fun tenantState(tenantId: UUID): TenantActivationState {
        return jdbcTemplate.query(
            """
            SELECT tenant.status AS tenant_status,
                   control.lifecycle_status
            FROM tenants tenant
            LEFT JOIN tenant_control_states control
              ON control.tenant_id = tenant.id
            WHERE tenant.id = ?
              AND tenant.deleted_at IS NULL
            """.trimIndent(),
            { resultSet, _ ->
                TenantActivationState(
                    lifecycleStatus = resultSet.getString("lifecycle_status")
                        ?: resultSet.getString("tenant_status"),
                )
            },
            tenantId,
        ).singleOrNull()
            ?: throw IllegalArgumentException("Tenant was not found")
    }

    private fun commercialEvidence(tenantId: UUID): CommercialEvidence {
        return jdbcTemplate.query(
            """
            SELECT
                EXISTS (
                    SELECT 1
                    FROM tenant_subscriptions subscription
                    JOIN plans plan ON plan.id = subscription.plan_id
                    JOIN tenants tenant
                      ON tenant.id = subscription.tenant_id
                     AND tenant.plan_id = subscription.plan_id
                    WHERE subscription.tenant_id = ?
                      AND subscription.status IN ('trialing', 'active')
                      AND plan.is_active = true
                ) AS subscription_ready,
                COALESCE(
                    (
                        SELECT desired_configuration_version =
                               actual_configuration_version
                        FROM tenant_control_states
                        WHERE tenant_id = ?
                    ),
                    false
                ) AS configuration_ready
            """.trimIndent(),
            { resultSet, _ ->
                CommercialEvidence(
                    subscriptionReady = resultSet.getBoolean("subscription_ready"),
                    configurationReady = resultSet.getBoolean("configuration_ready"),
                )
            },
            tenantId,
            tenantId,
        ).single()
    }

    private fun reconcileControlVerification(tenantId: UUID, verified: Boolean) {
        if (!verified) return
        jdbcTemplate.update(
            """
            UPDATE tenant_control_states
            SET verification_status = 'verified',
                version = version + 1,
                updated_at = now()
            WHERE tenant_id = ?
              AND verification_status <> 'verified'
            """.trimIndent(),
            tenantId,
        )
    }

    private fun reconcileOnboardingWorkflow(
        tenantId: UUID,
        verificationReady: Boolean,
        administratorReady: Boolean,
        configurationReady: Boolean,
        preActivationReady: Boolean,
        activated: Boolean,
    ) {
        val workflowId = jdbcTemplate.query(
            """
            SELECT id
            FROM tenant_workflows
            WHERE tenant_id = ?
              AND workflow_type = 'onboarding'
            ORDER BY created_at DESC
            LIMIT 1
            FOR UPDATE
            """.trimIndent(),
            { resultSet, _ -> resultSet.getObject("id", UUID::class.java) },
            tenantId,
        ).singleOrNull() ?: return

        val evidence = linkedMapOf(
            "register_tenant" to true,
            "verify_business" to verificationReady,
            "provision_administrator" to administratorReady,
            "configure_entitlements" to configurationReady,
            "verify_readiness" to preActivationReady,
            "activate" to activated,
        )
        evidence.filterValues { it }.keys.forEach { stepKey ->
            jdbcTemplate.update(
                """
                UPDATE tenant_workflow_steps
                SET status = 'succeeded',
                    attempt_count = GREATEST(attempt_count, 1),
                    started_at = COALESCE(started_at, now()),
                    completed_at = COALESCE(completed_at, now()),
                    error_code = NULL,
                    error_detail = NULL
                WHERE tenant_id = ?
                  AND workflow_id = ?
                  AND step_key = ?
                  AND status <> 'succeeded'
                """.trimIndent(),
                tenantId,
                workflowId,
                stepKey,
            )
        }

        val progress = jdbcTemplate.queryForMap(
            """
            SELECT count(*) FILTER (WHERE status = 'succeeded') AS completed,
                   count(*) AS total,
                   (
                       SELECT pending.step_key
                       FROM tenant_workflow_steps pending
                       WHERE pending.workflow_id = ?
                         AND pending.status <> 'succeeded'
                       ORDER BY pending.sequence
                       LIMIT 1
                   ) AS next_step
            FROM tenant_workflow_steps
            WHERE workflow_id = ?
            """.trimIndent(),
            workflowId,
            workflowId,
        )
        val completed = (progress["completed"] as Number).toInt()
        val total = (progress["total"] as Number).toInt()
        val nextStep = progress["next_step"]?.toString()
        jdbcTemplate.update(
            """
            UPDATE tenant_workflows
            SET completed_steps = ?,
                current_step = COALESCE(?, current_step),
                status = CASE
                    WHEN ? = ? THEN 'succeeded'
                    WHEN status IN ('failed', 'cancelled') THEN status
                    ELSE 'running'
                END,
                completed_at = CASE
                    WHEN ? = ? THEN COALESCE(completed_at, now())
                    ELSE NULL
                END
            WHERE tenant_id = ?
              AND id = ?
            """.trimIndent(),
            completed,
            nextStep,
            completed,
            total,
            completed,
            total,
            tenantId,
            workflowId,
        )
    }

    private fun nextActions(
        tenantId: UUID,
        gates: List<TenantActivationGate>,
        administratorStatus: String,
    ): List<TenantActivationAction> {
        val blocked = gates.filterNot(TenantActivationGate::satisfied).map(TenantActivationGate::code).toSet()
        return buildList {
            if (VERIFICATION in blocked) {
                add(action(
                    VERIFICATION,
                    "Review and verify the business profile",
                    "platform",
                    "POST",
                    "/api/v1/platform/tenants/$tenantId/profile/verify",
                ))
            }
            if (ADMINISTRATOR in blocked) {
                add(
                    if (administratorStatus == "invited") {
                        action(
                            ADMINISTRATOR,
                            "Ask the invited administrator to accept access",
                            "tenant",
                            null,
                            null,
                        )
                    } else {
                        action(
                            ADMINISTRATOR,
                            "Invite the initial tenant administrator",
                            "platform",
                            "POST",
                            "/api/v1/platform/tenants/$tenantId/administrator-invitations",
                        )
                    },
                )
            }
            if (SUBSCRIPTION in blocked || CONFIGURATION in blocked) {
                add(action(
                    SUBSCRIPTION,
                    "Configure the tenant subscription and reconcile entitlements",
                    "platform",
                    "PUT",
                    "/api/v1/platform/tenants/$tenantId/commercial/subscription",
                ))
            }
            if (BUSINESS_CONTACT in blocked) {
                add(action(
                    BUSINESS_CONTACT,
                    "Add an owner, signatory, or primary contact",
                    "tenant",
                    null,
                    null,
                ))
            }
            if (REPORTING in blocked) {
                add(action(
                    REPORTING,
                    "Configure a consented operational-report recipient",
                    "tenant",
                    null,
                    null,
                ))
            }
        }
    }

    private fun gate(code: String, label: String, satisfied: Boolean, detail: String) =
        TenantActivationGate(code, label, satisfied, detail)

    private fun action(
        code: String,
        label: String,
        responsibleParty: String,
        method: String?,
        path: String?,
    ) = TenantActivationAction(code, label, responsibleParty, method, path)

    private data class TenantActivationState(val lifecycleStatus: String)
    private data class CommercialEvidence(
        val subscriptionReady: Boolean,
        val configurationReady: Boolean,
    )

    private companion object {
        const val VIEW_PERMISSION = "platform.tenants.view"
        const val ACCOUNT = "account_eligible"
        const val VERIFICATION = "business_verified"
        const val ADMINISTRATOR = "administrator_ready"
        const val SUBSCRIPTION = "subscription_ready"
        const val CONFIGURATION = "configuration_ready"
        const val BUSINESS_CONTACT = "business_contact_ready"
        const val REPORTING = "operational_reporting_ready"
        const val TENANT_ADMIN_MODULE = "tenant_admin_module_ready"
    }
}
