package com.mwombeki.peak.platformgovernance.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.platformgovernance.api.AssignPlatformReleaseCommand
import com.mwombeki.peak.platformgovernance.api.ChangePlatformReleaseCommand
import com.mwombeki.peak.platformgovernance.api.CompletePlatformJobRunCommand
import com.mwombeki.peak.platformgovernance.api.CreatePlatformIncidentCommand
import com.mwombeki.peak.platformgovernance.api.CreatePlatformReleaseCommand
import com.mwombeki.peak.platformgovernance.api.FeatureControlPort
import com.mwombeki.peak.platformgovernance.api.FleetControlPort
import com.mwombeki.peak.platformgovernance.api.OpenSupportTicketCommand
import com.mwombeki.peak.platformgovernance.api.RecordServiceHealthCommand
import com.mwombeki.peak.platformgovernance.api.RegisterPlatformJobCommand
import com.mwombeki.peak.platformgovernance.api.RegisterPlatformServiceCommand
import com.mwombeki.peak.platformgovernance.api.ReleaseAction
import com.mwombeki.peak.platformgovernance.api.ReleaseControlPort
import com.mwombeki.peak.platformgovernance.api.RunPlatformJobCommand
import com.mwombeki.peak.platformgovernance.api.SupportControlPort
import com.mwombeki.peak.platformgovernance.api.UpdateSupportTicketCommand
import com.mwombeki.peak.platformgovernance.api.UpsertFeatureFlagCommand
import com.mwombeki.peak.property.api.AddPortfolioRevisionCommand
import com.mwombeki.peak.property.api.AssignPortfolioPropertyCommand
import com.mwombeki.peak.property.api.ChangePortfolioTemplateCommand
import com.mwombeki.peak.property.api.CreateOrganizationUnitCommand
import com.mwombeki.peak.property.api.CreatePortfolioTemplateCommand
import com.mwombeki.peak.property.api.PortfolioControlPort
import com.mwombeki.peak.property.api.PortfolioRolloutTarget
import com.mwombeki.peak.property.api.PortfolioTemplateAction
import com.mwombeki.peak.property.api.RolloutPortfolioConfigCommand
import com.mwombeki.peak.property.api.UpdatePortfolioRolloutCommand
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.tenantmanagement.api.AddVerificationDocumentCommand
import com.mwombeki.peak.tenantmanagement.api.CreateLegalHoldCommand
import com.mwombeki.peak.tenantmanagement.api.CreatePrivacyRequestCommand
import com.mwombeki.peak.tenantmanagement.api.CreateVerificationCaseCommand
import com.mwombeki.peak.tenantmanagement.api.IdentityConnectionReviewAction
import com.mwombeki.peak.tenantmanagement.api.PlatformCommercialControlPort
import com.mwombeki.peak.tenantmanagement.api.PlatformTenantControlPort
import com.mwombeki.peak.tenantmanagement.api.PrivacyRequestAction
import com.mwombeki.peak.tenantmanagement.api.ProcessPrivacyRequestCommand
import com.mwombeki.peak.tenantmanagement.api.ReviewIdentityConnectionCommand
import com.mwombeki.peak.tenantmanagement.api.ReviewVerificationCaseCommand
import com.mwombeki.peak.tenantmanagement.api.TenantTrustControlPort
import com.mwombeki.peak.tenantmanagement.api.UpsertIdentityConnectionCommand
import com.mwombeki.peak.tenantmanagement.api.VerificationReviewAction
import com.mwombeki.peak.usermanagement.api.BreakGlassAccessPort
import com.mwombeki.peak.usermanagement.api.DecideBreakGlassAccessCommand
import com.mwombeki.peak.usermanagement.api.RequestBreakGlassAccessCommand
import java.time.LocalDate
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class HospitalityPlatformControlPlaneIntegrationTests {
    @Autowired private lateinit var tenantControl: PlatformTenantControlPort
    @Autowired private lateinit var commercial: PlatformCommercialControlPort
    @Autowired private lateinit var trust: TenantTrustControlPort
    @Autowired private lateinit var support: SupportControlPort
    @Autowired private lateinit var breakGlass: BreakGlassAccessPort
    @Autowired private lateinit var fleet: FleetControlPort
    @Autowired private lateinit var releases: ReleaseControlPort
    @Autowired private lateinit var features: FeatureControlPort
    @Autowired private lateinit var portfolio: PortfolioControlPort
    @Autowired private lateinit var contextHolder: RequestContextHolder
    @Autowired private lateinit var jdbc: JdbcTemplate

    @AfterTest
    fun clearContext() = contextHolder.clear()

    @Test
    fun runsTenantTrustSupportFleetReleaseAndPortfolioControlLoops() {
        val root = UUID.randomUUID()
        val approver = UUID.randomUUID()
        val fixture = insertFixture(root, approver)

        platform(root)
        val overview = tenantControl.tenantOverview(fixture.tenantId)
        assertEquals("trial", overview.tenant.lifecycleStatus)
        platform(root)
        assertEquals(1, commercial.captureUsageSnapshot(fixture.tenantId, LocalDate.now()).propertyCount)

        tenant(fixture)
        val verification = trust.createVerificationCase(
            CreateVerificationCaseCommand(fixture.tenantId, "initial_onboarding", "standard"),
        )
        tenant(fixture)
        trust.addVerificationDocument(AddVerificationDocumentCommand(
            fixture.tenantId, verification.caseId, "business_registration", "***1234",
            "verification/${verification.caseId}.pdf", "a".repeat(64), "application/pdf",
            LocalDate.now().minusYears(1), LocalDate.now().plusYears(1),
        ))
        tenant(fixture)
        trust.submitVerificationCase(fixture.tenantId, verification.caseId)
        platform(root)
        trust.reviewVerificationCase(ReviewVerificationCaseCommand(
            fixture.tenantId, verification.caseId, VerificationReviewAction.START_REVIEW,
            null, "low", null,
        ))
        platform(root)
        val approved = trust.reviewVerificationCase(ReviewVerificationCaseCommand(
            fixture.tenantId, verification.caseId, VerificationReviewAction.APPROVE,
            "Evidence verified", "low", null,
        ))
        assertEquals("approved", approved.status)

        tenant(fixture)
        val privacy = trust.createPrivacyRequest(CreatePrivacyRequestCommand(
            fixture.tenantId, "rectification", fixture.tenantUserId.toString(),
        ))
        platform(root)
        assertEquals("identity_verification", trust.processPrivacyRequest(
            ProcessPrivacyRequestCommand(
                fixture.tenantId, privacy.requestId, PrivacyRequestAction.ASSIGN, null,
            ),
        ).status)
        platform(root)
        val hold = trust.createLegalHold(CreateLegalHoldCommand(
            fixture.tenantId, "tenant", null, "Regulatory retention", null,
        ))
        platform(root)
        assertEquals("released", trust.releaseLegalHold(
            fixture.tenantId, hold.holdId, "Regulator released hold",
        ).status)

        tenant(fixture)
        val identity = trust.upsertIdentityConnection(UpsertIdentityConnectionCommand(
            fixture.tenantId, null, "Corporate SSO", "oidc",
            "https://identity.example.com", "hotel.example.com",
            "https://identity.example.com/.well-known/openid-configuration",
            "peak-hotel", "secret://identity/hotel", true, null,
        ))
        platform(root)
        assertEquals("active", trust.reviewIdentityConnection(ReviewIdentityConnectionCommand(
            fixture.tenantId, identity.connectionId, IdentityConnectionReviewAction.VERIFY,
            identity.version, "Discovery and domain verified",
        )).status)

        tenant(fixture)
        val ticket = support.openTicket(OpenSupportTicketCommand(
            fixture.tenantId, fixture.propertyId, "Night audit assistance",
            "Night audit needs an operator review", "high", "operations",
        ))
        platform(root)
        assertEquals("triaged", support.updateTicket(UpdateSupportTicketCommand(
            fixture.tenantId, ticket.ticket.ticketId, "triaged", null, root,
            "Assigned to on-call operator",
        )).ticket.status)

        platform(root)
        val access = breakGlass.requestAccess(RequestBreakGlassAccessCommand(
            fixture.tenantId, ticket.ticket.ticketId, "platform.tenants.view",
            "Inspect tenant control state for ticket", 15, 20, "mfa",
        ))
        platform(approver)
        breakGlass.decideAccess(DecideBreakGlassAccessCommand(
            access.accessId, com.mwombeki.peak.usermanagement.api.BreakGlassDecision.APPROVE,
            "Scope and duration approved",
        ))
        platform(root)
        assertEquals("active", breakGlass.activateAccess(access.accessId).status)

        platform(root)
        val service = fleet.registerService(RegisterPlatformServiceCommand(
            "peak-api-test", "Peak API Test", "api", "platform", true,
        ))
        platform(root)
        fleet.recordHealth(RecordServiceHealthCommand(
            service.serviceId, "degraded", 450, mapOf("reason" to "provider latency"),
        ))
        platform(root)
        val job = fleet.registerJob(RegisterPlatformJobCommand(
            "tenant-reconcile-test", service.serviceId, "Reconcile tenant", null, true,
        ))
        platform(root)
        val run = fleet.runJob(RunPlatformJobCommand(job.jobId, fixture.tenantId, emptyMap()))
        platform(root)
        assertEquals("succeeded", fleet.completeJobRun(CompletePlatformJobRunCommand(
            run.runId, "succeeded", null, mapOf("reconciled" to true),
        )).status)
        platform(root)
        assertNotNull(fleet.createIncident(CreatePlatformIncidentCommand(
            "Provider latency", "sev3", "Payment provider degraded", root,
        )).incidentId)

        platform(root)
        val release = releases.createRelease(CreatePlatformReleaseCommand(
            "v1.71.0-test", "sha256:${"b".repeat(64)}", 71, "Control plane test",
        ))
        platform(approver)
        val approvedRelease = releases.changeRelease(ChangePlatformReleaseCommand(
            release.releaseId, ReleaseAction.APPROVE, "Automated gates passed",
        ))
        assertEquals("approved", approvedRelease.status)
        platform(root)
        assertEquals("scheduled", releases.assignRelease(AssignPlatformReleaseCommand(
            release.releaseId, fixture.tenantId, "canary", null,
        )).status)
        platform(root)
        features.upsertFlag(UpsertFeatureFlagCommand(
            "control.daily_brief", "Daily control brief", "tenant",
            fixture.tenantId, null, true, mapOf("percentage" to 100), "Enable design partner",
        ))
        platform(root)
        assertTrue(features.effectiveFlags(fixture.tenantId, fixture.propertyId).single().enabled)

        tenant(fixture)
        val unit = portfolio.createUnit(CreateOrganizationUnitCommand(
            fixture.tenantId, null, "portfolio", "peak-group", "Peak Group",
        ))
        tenant(fixture)
        portfolio.assignProperty(AssignPortfolioPropertyCommand(
            fixture.tenantId, unit.unitId, fixture.propertyId, true,
        ))
        tenant(fixture)
        val template = portfolio.createTemplate(CreatePortfolioTemplateCommand(
            fixture.tenantId, "Standard Operations", "operations",
        ))
        tenant(fixture)
        val revised = portfolio.addRevision(AddPortfolioRevisionCommand(
            fixture.tenantId, template.templateId,
            mapOf("nightAuditDeadline" to "02:00", "cashVarianceLimit" to 10000),
            "Initial operating standard",
        ))
        tenant(fixture)
        portfolio.changeTemplate(ChangePortfolioTemplateCommand(
            fixture.tenantId, template.templateId, PortfolioTemplateAction.ACTIVATE,
            "Approved operating standard",
        ))
        tenant(fixture)
        val rollout = portfolio.rollout(RolloutPortfolioConfigCommand(
            fixture.tenantId, template.templateId, revised.currentRevision,
            listOf(PortfolioRolloutTarget(unit.unitId, null)), false, null,
        )).single()
        tenant(fixture)
        portfolio.updateRollout(UpdatePortfolioRolloutCommand(
            fixture.tenantId, rollout.assignmentId, "applying", null,
        ))
        tenant(fixture)
        portfolio.updateRollout(UpdatePortfolioRolloutCommand(
            fixture.tenantId, rollout.assignmentId, "applied", null,
        ))
        tenant(fixture)
        val effective = portfolio.effectiveConfig(
            fixture.tenantId, fixture.propertyId, "operations",
        )
        assertEquals("02:00", effective?.config?.get("nightAuditDeadline"))
    }

    private fun insertFixture(root: UUID, approver: UUID): Fixture {
        insertPlatformOperator(root, "root", mfa = true)
        insertPlatformOperator(approver, "approver", mfa = true)
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val tenantUserId = UUID.randomUUID()
        val tenantRoleId = UUID.randomUUID()
        val propertyId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO plans (
                id, name, code, max_properties, max_rooms, max_users, max_outlets
            ) VALUES (?, 'Control Plane', ?, 10, 500, 500, 50)
            """.trimIndent(), planId, "control-${planId.toString().take(8)}",
        )
        jdbc.update(
            """
            INSERT INTO tenants (
                id, name, slug, status, schema_name, plan_id, country_code, currency_code
            ) VALUES (?, 'Control Hotel', ?, 'trial', ?, ?, 'TZ', 'TZS')
            """.trimIndent(), tenantId, "control-${tenantId.toString().take(8)}",
            "tenant_${tenantId.toString().replace("-", "")}", planId,
        )
        jdbc.update(
            """
            INSERT INTO tenant_profiles (
                tenant_id, legal_name, entity_type, business_phone, business_email
            ) VALUES (?, 'Control Hotel Limited', 'limited_company', '+255712345678', ?)
            """.trimIndent(), tenantId, "control-$tenantId@example.com",
        )
        jdbc.update(
            """
            INSERT INTO tenant_control_states (
                tenant_id, lifecycle_status, provisioning_status,
                subscription_status, updated_by_platform_user_id
            ) VALUES (?, 'trial', 'ready', 'trialing', ?)
            """.trimIndent(), tenantId, root,
        )
        jdbc.update(
            """
            INSERT INTO tenant_subscriptions (
                tenant_id, plan_id, status, billing_currency, created_by_platform_user_id
            ) VALUES (?, ?, 'trialing', 'TZS', ?)
            """.trimIndent(), tenantId, planId, root,
        )
        val workflowId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO tenant_workflows (
                id, tenant_id, workflow_type, status, total_steps, completed_steps,
                requested_by_platform_user_id
            ) VALUES (?, ?, 'onboarding', 'running', 1, 0, ?)
            """.trimIndent(), workflowId, tenantId, root,
        )
        jdbc.update(
            """
            INSERT INTO tenant_workflow_steps (
                tenant_id, workflow_id, step_key, sequence, status
            ) VALUES (?, ?, 'verify_business', 1, 'pending')
            """.trimIndent(), tenantId, workflowId,
        )
        jdbc.update(
            "INSERT INTO properties (id, tenant_id, name, code) VALUES (?, ?, 'Control Property', 'CTRL')",
            propertyId, tenantId,
        )
        jdbc.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Tenant Administrator', ?, 'active', true)
            """.trimIndent(), tenantUserId, tenantId, "admin-$tenantId@example.com",
        )
        jdbc.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code, is_system)
            VALUES (?, ?, 'Tenant Administrator', 'tenant_admin', true)
            """.trimIndent(), tenantRoleId, tenantId,
        )
        val permissions = listOf(
            "tenant.profile.manage", "tenant.privacy.manage", "tenant.identity.manage",
            "tenant.support.manage", "tenant.portfolio.view", "tenant.portfolio.manage",
            "tenant.subscription.view",
        )
        permissions.forEach { code ->
            jdbc.update(
                """
                INSERT INTO permissions (tenant_id, code, description)
                SELECT ?, code, description FROM permission_catalog WHERE code = ?
                ON CONFLICT (tenant_id, code) DO NOTHING
                """.trimIndent(), tenantId, code,
            )
        }
        jdbc.update(
            """
            INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
            SELECT ?, id FROM permissions WHERE tenant_id = ? AND code IN (
                'tenant.profile.manage', 'tenant.privacy.manage', 'tenant.identity.manage',
                'tenant.support.manage', 'tenant.portfolio.view', 'tenant.portfolio.manage',
                'tenant.subscription.view'
            )
            """.trimIndent(), tenantRoleId, tenantId,
        )
        jdbc.update(
            "INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id) VALUES (?, ?, ?)",
            tenantUserId, tenantId, tenantRoleId,
        )
        return Fixture(tenantId, tenantUserId, propertyId)
    }

    private fun insertPlatformOperator(id: UUID, label: String, mfa: Boolean) {
        val roleId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO platform_users (id, full_name, email, status, mfa_enabled)
            VALUES (?, ?, ?, 'active', ?)
            """.trimIndent(), id, "Platform $label", "$label-$id@example.com", mfa,
        )
        jdbc.update(
            "INSERT INTO platform_roles (id, name, code) VALUES (?, ?, ?)",
            roleId, "Control $label", "control-$label-${id.toString().take(8)}",
        )
        jdbc.update(
            """
            INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
            SELECT ?, id FROM platform_permissions WHERE code <> 'platform.admin.all'
            """.trimIndent(), roleId,
        )
        jdbc.update(
            "INSERT INTO platform_user_roles (platform_user_id, platform_role_id) VALUES (?, ?)",
            id, roleId,
        )
    }

    private fun platform(userId: UUID) {
        val token = UUID.randomUUID().toString()
        contextHolder.set(RequestContext(
            RequestIdentity.Platform(userId, "corr-$token"), "corr-$token", "idem-$token",
            "POST", "/api/v1/platform/control",
        ))
    }

    private fun tenant(fixture: Fixture) {
        val token = UUID.randomUUID().toString()
        contextHolder.set(RequestContext(
            RequestIdentity.Tenant(fixture.tenantId, fixture.tenantUserId, "corr-$token"),
            "corr-$token", "idem-$token", "POST", "/api/v1/tenants/${fixture.tenantId}",
        ))
    }

    private data class Fixture(
        val tenantId: UUID,
        val tenantUserId: UUID,
        val propertyId: UUID,
    )
}
