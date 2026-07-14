package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.tenantmanagement.api.TenantOnboardingPort
import com.mwombeki.peak.tenantmanagement.api.TenantRegisterRequest
import com.mwombeki.peak.tenantmanagement.api.TenantStatus
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TenantOnboardingServiceIntegrationTests {

    @Autowired
    private lateinit var tenantOnboardingPort: TenantOnboardingPort

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
    }

    @Test
    fun registersTenantUsingCanonicalTenantSchema() {
        val platformUserId = UUID.randomUUID()
        val planId = UUID.randomUUID()
        insertPlatformFixture(platformUserId)
        insertPlan(planId)
        requestContextHolder.set(platformContext(platformUserId, "corr-tenant-register"))

        val response = tenantOnboardingPort.registerNewTenant(
            TenantRegisterRequest(
                name = "Peak Zanzibar",
                slug = "peak-zanzibar",
                planId = planId,
                legalName = "Peak Zanzibar Limited",
                tradingName = "Peak Zanzibar",
                businessRegistrationNumber = "BRELA-12345",
                businessEmail = "Ops@Peak-Zanzibar.example",
                businessPhone = "+255712345678",
                registeredAddress = mapOf(
                    "line1" to "Stone Town",
                    "city" to "Zanzibar",
                    "countryCode" to "TZ",
                ),
            ),
        )

        assertEquals("peak-zanzibar", response.slug)
        assertEquals(TenantStatus.TRIAL, response.status)
        assertEquals("ops@peak-zanzibar.example", response.businessEmail)

        val tenant = jdbcTemplate.queryForMap(
            """
            SELECT slug, status, schema_name, plan_id
            FROM tenants
            WHERE id = ?
            """.trimIndent(),
            response.id,
        )
        assertEquals("peak-zanzibar", tenant["slug"])
        assertEquals("trial", tenant["status"])
        assertEquals(planId, tenant["plan_id"])
        assertTrue(tenant["schema_name"].toString().startsWith("tenant_"))

        val profile = jdbcTemplate.queryForMap(
            """
            SELECT legal_name, business_email, business_phone, registered_address::text AS address
            FROM tenant_profiles
            WHERE tenant_id = ?
            """.trimIndent(),
            response.id,
        )
        assertEquals("Peak Zanzibar Limited", profile["legal_name"])
        assertEquals("ops@peak-zanzibar.example", profile["business_email"])
        assertEquals("+255712345678", profile["business_phone"])
        assertTrue(profile["address"].toString().contains("Stone Town"))

        val lifecycle = jdbcTemplate.queryForMap(
            """
            SELECT event_type, performed_by_platform_user_id
            FROM tenant_lifecycle_events
            WHERE tenant_id = ?
            """.trimIndent(),
            response.id,
        )
        assertEquals("created", lifecycle["event_type"])
        assertEquals(platformUserId, lifecycle["performed_by_platform_user_id"])
    }

    @Test
    fun allowsSupportTenantLookupOnlyForTheExactApprovedSession() {
        val platformUserId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val planId = UUID.randomUUID()
        insertPlatformFixture(platformUserId)
        insertPlan(planId)
        insertTenantWithProfile(tenantId, planId)
        val supportSessionId = insertActiveBreakGlassGrant(
            platformUserId = platformUserId,
            tenantId = tenantId,
            actionCode = "platform.tenants.view",
        )
        requestContextHolder.set(
            supportContext(
                platformUserId = platformUserId,
                supportTenantId = tenantId,
                supportSessionId = supportSessionId,
                correlationId = "corr-support-tenant-lookup-allowed",
            ),
        )

        val response = requireNotNull(tenantOnboardingPort.getTenantById(tenantId))

        assertEquals(tenantId, response.id)
        val auditOutcome = jdbcTemplate.queryForObject(
            """
            SELECT outcome
            FROM platform_audit_logs
            WHERE action = 'platform.support.break_glass.access'
              AND tenant_id = ?
              AND entity_id = ?
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
            String::class.java,
            tenantId,
            supportSessionId,
        )
        assertEquals("success", auditOutcome)
    }

    @Test
    fun rejectsSupportTenantLookupWhenSessionTenantDoesNotMatchTargetTenant() {
        val platformUserId = UUID.randomUUID()
        val supportTenantId = UUID.randomUUID()
        val targetTenantId = UUID.randomUUID()
        val supportPlanId = UUID.randomUUID()
        val targetPlanId = UUID.randomUUID()
        insertPlatformFixture(platformUserId)
        insertPlan(supportPlanId)
        insertPlan(targetPlanId)
        insertTenantWithProfile(supportTenantId, supportPlanId)
        insertTenantWithProfile(targetTenantId, targetPlanId)
        insertActiveBreakGlassGrant(
            platformUserId = platformUserId,
            tenantId = targetTenantId,
            actionCode = "platform.tenants.view",
        )
        requestContextHolder.set(
            supportContext(
                platformUserId = platformUserId,
                supportTenantId = supportTenantId,
                correlationId = "corr-support-tenant-lookup-mismatch",
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            tenantOnboardingPort.getTenantById(targetTenantId)
        }

        assertEquals("Support session tenant does not match target tenant", error.message)
        val auditOutcome = jdbcTemplate.queryForObject(
            """
            SELECT outcome
            FROM platform_audit_logs
            WHERE action = 'platform.support.break_glass.access'
              AND tenant_id = ?
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
            String::class.java,
            targetTenantId,
        )
        assertEquals("denied", auditOutcome)
    }

    private fun insertPlatformFixture(platformUserId: UUID) {
        val roleId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, full_name, email, status)
            VALUES (?, ?, ?, 'active')
            """.trimIndent(),
            platformUserId,
            "Platform Operator $platformUserId",
            "platform-$platformUserId@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_roles (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            roleId,
            "Tenant Governance $roleId",
            "tenant-governance-$roleId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
            SELECT ?, id
            FROM platform_permissions
            WHERE code IN ('platform.tenants.view', 'platform.tenants.manage')
            """.trimIndent(),
            roleId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_user_roles (platform_user_id, platform_role_id)
            VALUES (?, ?)
            """.trimIndent(),
            platformUserId,
            roleId,
        )
    }

    private fun insertPlan(planId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO plans (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            planId,
            "Starter $planId",
            "starter-${planId.toString().take(8)}",
        )
    }

    private fun insertTenantWithProfile(tenantId: UUID, planId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, schema_name, plan_id, status)
            VALUES (?, ?, ?, ?, ?, 'active')
            """.trimIndent(),
            tenantId,
            "Tenant $tenantId",
            "tenant-$tenantId",
            "tenant_${tenantId.toString().replace("-", "")}",
            planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_profiles (
                tenant_id,
                legal_name,
                entity_type,
                business_phone,
                business_email
            )
            VALUES (?, ?, 'limited_company', '+255712345678', ?)
            """.trimIndent(),
            tenantId,
            "Tenant $tenantId Limited",
            "business-$tenantId@example.com",
        )
    }

    private fun insertActiveBreakGlassGrant(
        platformUserId: UUID,
        tenantId: UUID,
        actionCode: String,
        supportSessionId: UUID = UUID.randomUUID(),
    ): UUID {
        val approverId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, full_name, email, status)
            VALUES (?, ?, ?, 'active')
            """.trimIndent(),
            approverId,
            "Break Glass Approver $approverId",
            "approver-$approverId@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_break_glass_access (
                id,
                platform_user_id,
                tenant_id,
                action_code,
                reason,
                status,
                approved_by,
                approved_at,
                activated_at,
                starts_at,
                expires_at
            )
            VALUES (?, ?, ?, ?, 'Regression test support access', 'active', ?, now(), now(), now() - interval '1 minute', now() + interval '1 hour')
            """.trimIndent(),
            supportSessionId,
            platformUserId,
            tenantId,
            actionCode,
            approverId,
        )
        return supportSessionId
    }

    private fun platformContext(
        platformUserId: UUID,
        correlationId: String,
    ): RequestContext {
        return RequestContext(
            identity = RequestIdentity.Platform(
                platformUserId = platformUserId,
                correlationId = correlationId,
            ),
            correlationId = correlationId,
            idempotencyKey = "idem-$correlationId",
            httpMethod = "POST",
            requestPath = "/api/v1/platform/tenants",
        )
    }

    private fun supportContext(
        platformUserId: UUID,
        supportTenantId: UUID,
        supportSessionId: UUID = UUID.randomUUID(),
        correlationId: String,
    ): RequestContext {
        return RequestContext(
            identity = RequestIdentity.Support(
                platformUserId = platformUserId,
                tenantId = supportTenantId,
                supportSessionId = supportSessionId,
                correlationId = correlationId,
            ),
            correlationId = correlationId,
            idempotencyKey = "idem-$correlationId",
            httpMethod = "GET",
            requestPath = "/api/v1/platform/tenants",
        )
    }
}
