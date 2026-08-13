package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.platformbilling.internal.SubscriptionLifecycleService.BillingLifecycleState
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The lifecycle against a real database, where what matters is not which string lands in a
 * column but which permissions survive it.
 *
 * `tenant_restriction_permits` is what turns a state into a consequence, so these assert
 * through it rather than reading `lifecycle_status` back and calling that a result.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SubscriptionLifecycleIntegrationTests {

    @Autowired
    private lateinit var lifecycleService: SubscriptionLifecycleService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun resetSession() {
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun aTenantInsideGraceIsNotRestrictedAtAll() {
        val tenantId = tenantPaidThrough(daysAgo = 3)

        assertEquals(BillingLifecycleState.GRACE, lifecycleService.advance(tenantId, "corr-grace"))

        assertEquals("active", lifecycleStatus(tenantId))
        assertTrue(permits(tenantId, "tenant.users.manage"), "grace must restrict nothing")
        assertTrue(permits(tenantId, "frontdesk.checkin"))
    }

    @Test
    fun aRestrictedTenantKeepsOperatingButCannotGrowOrAdminister() {
        val tenantId = tenantPaidThrough(daysAgo = 10)

        assertEquals(
            BillingLifecycleState.RESTRICTED,
            lifecycleService.advance(tenantId, "corr-restricted"),
        )

        assertEquals("restricted", lifecycleStatus(tenantId))
        assertTrue(permits(tenantId, "frontdesk.checkout"), "a guest must still be able to leave")
        assertTrue(permits(tenantId, "payments.collect"))
        assertTrue(permits(tenantId, "night_audit.run"))
        assertTrue(permits(tenantId, "tenant.subscription.purchase"), "they must be able to pay")
        assertTrue(permits(tenantId, "tenant.data.export"))

        assertTrue(!permits(tenantId, "tenant.users.manage"), "inviting users is growth")
        assertTrue(!permits(tenantId, "tenant.properties.create"), "adding a property is growth")
    }

    @Test
    fun aSuspendedTenantCanStillCheckOutTakeMoneyExportAndPay() {
        val tenantId = tenantPaidThrough(daysAgo = 40)

        assertEquals(
            BillingLifecycleState.SUSPENDED,
            lifecycleService.advance(tenantId, "corr-suspended"),
        )

        assertEquals("suspended", lifecycleStatus(tenantId))
        // The four non-negotiables. A tenant must never be locked out of paying, and must
        // never be able to strand a guest.
        assertTrue(permits(tenantId, "checkout.complete"))
        assertTrue(permits(tenantId, "payments.collect"))
        assertTrue(permits(tenantId, "tenant.data.export"))
        assertTrue(permits(tenantId, "tenant.subscription.purchase"))

        assertTrue(!permits(tenantId, "reservations.create"), "suspension is read-only")
        assertTrue(!permits(tenantId, "housekeeping.assign"))
    }

    @Test
    fun theSubscriptionRowStaysServiceGrantingEvenWhenSuspended() {
        val tenantId = tenantPaidThrough(daysAgo = 40)
        lifecycleService.advance(tenantId, "corr-suspended-row")

        val status = jdbcTemplate.queryForObject(
            "SELECT status FROM tenant_subscriptions WHERE tenant_id = ?",
            String::class.java,
            tenantId,
        )

        assertTrue(
            status in setOf("trialing", "active", "past_due", "paused"),
            "expiring the row would strip plan entitlements, disable every module, and make " +
                "the allowances above unreachable — but status was '$status'",
        )
    }

    @Test
    fun payingReturnsASuspendedTenantToNormalService() {
        val tenantId = tenantPaidThrough(daysAgo = 40)
        lifecycleService.advance(tenantId, "corr-suspend-first")
        assertEquals("suspended", lifecycleStatus(tenantId))

        // A payment extends cover, which is what a settled purchase produces.
        jdbcTemplate.update(
            "UPDATE peak_product_grants SET ends_at = now() + interval '30 days' WHERE tenant_id = ?",
            tenantId,
        )

        assertEquals(BillingLifecycleState.ACTIVE, lifecycleService.advance(tenantId, "corr-recover"))
        assertEquals("active", lifecycleStatus(tenantId))
        assertTrue(permits(tenantId, "tenant.users.manage"), "everything must come back")
    }

    /**
     * Billing owns three states and must not touch the rest. A hotel being offboarded should
     * not be quietly returned to service because a grant happened to still be live.
     */
    @Test
    fun aTenantAnOperatorHasOffboardedIsLeftAlone() {
        val tenantId = tenantPaidThrough(daysAgo = 40)
        jdbcTemplate.update(
            "UPDATE tenant_control_states SET lifecycle_status = 'offboarding' WHERE tenant_id = ?",
            tenantId,
        )

        lifecycleService.advance(tenantId, "corr-offboarding")

        assertEquals(
            "offboarding",
            lifecycleStatus(tenantId),
            "billing must not overturn an operator decision",
        )
    }

    @Test
    fun advancingTwiceRecordsOneTransition() {
        val tenantId = tenantPaidThrough(daysAgo = 10)

        lifecycleService.advance(tenantId, "corr-once")
        lifecycleService.advance(tenantId, "corr-twice")

        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM peak_billing_lifecycle_events WHERE tenant_id = ?",
                Int::class.java,
                tenantId,
            ),
            "a tenant already in the right state is not a transition",
        )
    }

    @Test
    fun aTenantThatHasNeverBoughtAnythingIsNotMoved() {
        val tenantId = tenantPaidThrough(daysAgo = null)

        assertNull(
            lifecycleService.advance(tenantId, "corr-never-bought"),
            "with no cover to have lapsed there is nothing to decide",
        )
        assertEquals("trial", lifecycleStatus(tenantId))
    }

    private fun permits(tenantId: UUID, permissionCode: String): Boolean {
        return jdbcTemplate.queryForObject(
            "SELECT tenant_restriction_permits(?, ?)",
            Boolean::class.java,
            tenantId,
            permissionCode,
        ) == true
    }

    private fun lifecycleStatus(tenantId: UUID): String? {
        return jdbcTemplate.queryForObject(
            "SELECT lifecycle_status FROM tenant_control_states WHERE tenant_id = ?",
            String::class.java,
            tenantId,
        )
    }

    private fun tenantPaidThrough(daysAgo: Int?): UUID {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId,
            "Plan $planId",
            "plan-$planId",
        )
        jdbcTemplate.update(
            "INSERT INTO tenants (id, name, slug, schema_name, plan_id) VALUES (?, ?, ?, ?, ?)",
            tenantId,
            "Tenant $tenantId",
            "tenant-$tenantId",
            "tenant_$tenantId".replace("-", "_"),
            planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_control_states (
                tenant_id, lifecycle_status, verification_status,
                provisioning_status, subscription_status, service_status, offboarding_status
            ) VALUES (?, 'trial', 'verified', 'ready', 'trialing', 'operational', 'none')
            """.trimIndent(),
            tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_subscriptions (
                tenant_id, plan_id, status, billing_cycle, billing_currency,
                provider, current_period_starts_at
            ) VALUES (?, ?, 'active', 'monthly', 'TZS', 'manual', now() - interval '60 days')
            """.trimIndent(),
            tenantId,
            planId,
        )

        if (daysAgo != null) {
            jdbcTemplate.update(
                """
                INSERT INTO peak_product_grants (
                    tenant_id, product_code, source, status, starts_at, ends_at,
                    granted_entitlements
                ) VALUES (?, 'peak_core', 'purchase', 'active',
                          now() - interval '90 days', now() - interval '$daysAgo days',
                          '{"module.frontdesk": {"is_enabled": true, "value": {}}}'::jsonb)
                """.trimIndent(),
                tenantId,
            )
        }

        return tenantId
    }
}
