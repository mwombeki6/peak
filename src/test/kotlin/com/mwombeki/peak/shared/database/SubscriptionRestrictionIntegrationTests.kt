package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * A late invoice must never strand a guest.
 *
 * `tenant_restriction_permits` is the conjunct V91 added to `can_access_module`, so it
 * decides what a restricted or suspended tenant may still do. The tests that matter here
 * are the permissive ones: it would be easy to write a restriction that is thorough and
 * traps a guest at checkout, and much harder to notice.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SubscriptionRestrictionIntegrationTests {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun anUnrestrictedTenantIsUnaffected() {
        val tenantId = seedTenant(lifecycleStatus = "active")

        assertEquals(true, permits(tenantId, "tenant.users.invite"))
        assertEquals(true, permits(tenantId, "frontdesk.checkin"))
    }

    @Test
    fun aTenantWithNoControlStateIsUnaffected() {
        val tenantId = seedTenant(lifecycleStatus = null)

        assertEquals(
            true,
            permits(tenantId, "tenant.users.invite"),
            "Absence of a control row must not read as a restriction",
        )
    }

    @Test
    fun restrictionStopsGrowthAndAdministration() {
        val tenantId = seedTenant(lifecycleStatus = "restricted")

        assertEquals(false, permits(tenantId, "tenant.users.invite"))
        assertEquals(false, permits(tenantId, "property.create"))
        assertEquals(false, permits(tenantId, "pos.configure"))
    }

    @Test
    fun restrictionLeavesTheHotelRunning() {
        val tenantId = seedTenant(lifecycleStatus = "restricted")

        // Every one of these failing would mean a guest cannot leave, a bill cannot be
        // settled, or a legal obligation cannot be met.
        assertEquals(true, permits(tenantId, "frontdesk.checkin"))
        assertEquals(true, permits(tenantId, "checkout.complete"))
        assertEquals(true, permits(tenantId, "folio.charge.post"))
        assertEquals(true, permits(tenantId, "payments.collect"))
        assertEquals(true, permits(tenantId, "fiscal.submit"))
        assertEquals(true, permits(tenantId, "night_audit.run"))
        assertEquals(true, permits(tenantId, "housekeeping.task.update"))
        assertEquals(true, permits(tenantId, "pos.order.manage"))
    }

    @Test
    fun aRestrictedTenantCanStillReachTheRouteThatEndsTheRestriction() {
        val tenantId = seedTenant(lifecycleStatus = "restricted")

        assertEquals(
            true,
            permits(tenantId, "tenant.subscription.purchase"),
            "Blocking the page where they pay would make the restriction unresolvable",
        )
        assertEquals(true, permits(tenantId, "tenant.data.export"))
    }

    @Test
    fun suspensionStillLetsAGuestLeaveAndPay() {
        val tenantId = seedTenant(lifecycleStatus = "suspended")

        assertEquals(true, permits(tenantId, "checkout.complete"))
        assertEquals(true, permits(tenantId, "payments.collect"))
        assertEquals(true, permits(tenantId, "fiscal.submit"))
        assertEquals(true, permits(tenantId, "tenant.subscription.purchase"))
        assertEquals(true, permits(tenantId, "tenant.data.export"))
    }

    @Test
    fun suspensionIsStricterThanRestriction() {
        val suspended = seedTenant(lifecycleStatus = "suspended")
        val restricted = seedTenant(lifecycleStatus = "restricted")

        // Housekeeping keeps a restricted hotel operating but is not one of the
        // non-negotiables once suspended.
        assertEquals(true, permits(restricted, "housekeeping.task.update"))
        assertEquals(false, permits(suspended, "housekeeping.task.update"))
    }

    /**
     * The function is told which tenant to judge, so it must reach that tenant's control
     * row on its own account rather than through whatever the caller's session happens to
     * expose. Getting this wrong fails open — an unreadable row looks like no restriction
     * at all, and a suspended tenant silently regains everything.
     */
    @Test
    fun restrictionHoldsWithoutATenantBoundSession() {
        val tenantId = seedTenant(lifecycleStatus = "suspended")

        jdbcTemplate.execute("SELECT set_config('app.current_tenant_id', '', true)")

        assertEquals(
            false,
            permits(tenantId, "tenant.users.invite"),
            "An unbound session must not be a way around the restriction",
        )
    }

    @Test
    fun canAccessModuleConsultsTheRestrictionState() {
        val definition = jdbcTemplate.queryForObject(
            """
            SELECT pg_catalog.pg_get_functiondef(
                'public.can_access_module(uuid, uuid, uuid, text, text)'::regprocedure
            )
            """.trimIndent(),
            String::class.java,
        )

        assertEquals(
            true,
            definition?.contains("tenant_restriction_permits"),
            "The guard is only enforced if the route choke point actually calls it",
        )
    }

    private fun permits(tenantId: UUID, permissionCode: String): Boolean {
        return jdbcTemplate.queryForObject(
            "SELECT tenant_restriction_permits(?, ?)",
            Boolean::class.java,
            tenantId,
            permissionCode,
        ) == true
    }

    private fun seedTenant(lifecycleStatus: String?): UUID {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId,
            "Restriction Plan $planId",
            "restriction-$planId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            tenantId,
            "Restriction Tenant $tenantId",
            "restr-${tenantId.toString().take(8)}",
            "tenant_${tenantId.toString().replace("-", "")}",
            planId,
        )
        if (lifecycleStatus != null) {
            jdbcTemplate.update(
                "INSERT INTO tenant_control_states (tenant_id, lifecycle_status) VALUES (?, ?)",
                tenantId,
                lifecycleStatus,
            )
        }
        return tenantId
    }
}
