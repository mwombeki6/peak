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
}
