package com.mwombeki.peak.platformgovernance.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.platformgovernance.api.TenantGovernancePort
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TenantGovernanceServiceIntegrationTests {

    @Autowired
    private lateinit var tenantGovernancePort: TenantGovernancePort

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
    }

    @Test
    fun activatesAndSuspendsTenantUsingCanonicalLifecycleEvents() {
        val platformUserId = UUID.randomUUID()
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        insertPlatformFixture(platformUserId)
        insertPlan(planId)
        insertTenant(tenantId, planId, "trial")
        requestContextHolder.set(platformContext(platformUserId, "corr-governance"))

        val activation = tenantGovernancePort.approveTenant(
            tenantId = tenantId,
            operatorId = platformUserId,
            reason = "Business verification completed",
        )

        assertEquals("trial", activation.previousStatus)
        assertEquals("active", activation.newStatus)
        assertEquals("active", tenantStatus(tenantId))

        requestContextHolder.set(platformContext(platformUserId, "corr-governance-suspend"))
        val suspension = tenantGovernancePort.suspendTenant(
            tenantId = tenantId,
            operatorId = platformUserId,
            reason = "Subscription payment overdue",
        )

        assertEquals("active", suspension.previousStatus)
        assertEquals("suspended", suspension.newStatus)
        assertEquals("suspended", tenantStatus(tenantId))

        val eventTypes = jdbcTemplate.queryForList(
            """
            SELECT event_type
            FROM tenant_lifecycle_events
            WHERE tenant_id = ?
            ORDER BY created_at
            """.trimIndent(),
            String::class.java,
            tenantId,
        )
        assertEquals(listOf("activated", "suspended"), eventTypes)
    }

    @Test
    fun rejectsSupportGovernanceWhenSessionTenantDoesNotMatchTargetTenant() {
        val platformUserId = UUID.randomUUID()
        val supportTenantId = UUID.randomUUID()
        val targetTenantId = UUID.randomUUID()
        val supportPlanId = UUID.randomUUID()
        val targetPlanId = UUID.randomUUID()
        insertPlatformFixture(platformUserId)
        insertPlan(supportPlanId)
        insertPlan(targetPlanId)
        insertTenant(supportTenantId, supportPlanId, "active")
        insertTenant(targetTenantId, targetPlanId, "active")
        insertActiveBreakGlassGrant(
            platformUserId = platformUserId,
            tenantId = targetTenantId,
            actionCode = "platform.tenants.manage",
        )
        requestContextHolder.set(
            supportContext(
                platformUserId = platformUserId,
                supportTenantId = supportTenantId,
                correlationId = "corr-governance-support-mismatch",
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            tenantGovernancePort.suspendTenant(
                tenantId = targetTenantId,
                operatorId = platformUserId,
                reason = "Regression test mismatch",
            )
        }

        assertEquals("Support session tenant does not match target tenant", error.message)
        assertEquals("active", tenantStatus(targetTenantId))
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

    private fun tenantStatus(tenantId: UUID): String {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT status FROM tenants WHERE id = ?",
                String::class.java,
                tenantId,
            ),
        )
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

    private fun insertTenant(
        tenantId: UUID,
        planId: UUID,
        status: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            tenantId,
            "Governance Tenant $tenantId",
            "governance-${tenantId.toString().take(8)}",
            status,
            "tenant_${tenantId.toString().replace("-", "")}",
            planId,
        )
    }

    private fun insertActiveBreakGlassGrant(
        platformUserId: UUID,
        tenantId: UUID,
        actionCode: String,
    ) {
        val approverId = UUID.randomUUID()
        insertPlatformFixture(approverId)
        val ticketId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO support_tickets (id, tenant_id, ticket_number, subject) VALUES (?, ?, ?, ?)",
            ticketId, tenantId, "SUP-${ticketId.toString().take(8)}", "Break-glass regression",
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_break_glass_access (
                platform_user_id,
                tenant_id,
                support_ticket_id,
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
            platformUserId,
            tenantId,
            ticketId,
            actionCode,
            approverId,
        )
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
        correlationId: String,
    ): RequestContext {
        return RequestContext(
            identity = RequestIdentity.Support(
                platformUserId = platformUserId,
                tenantId = supportTenantId,
                supportSessionId = UUID.randomUUID(),
                correlationId = correlationId,
            ),
            correlationId = correlationId,
            idempotencyKey = "idem-$correlationId",
            httpMethod = "POST",
            requestPath = "/api/v1/platform/tenants",
        )
    }
}
