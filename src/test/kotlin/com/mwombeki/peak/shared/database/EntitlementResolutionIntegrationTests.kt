package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * `effective_tenant_entitlement` decides what a tenant may have, and
 * `assert_tenant_entitlement_enabled` and `assert_tenant_capacity` are built on it. V90
 * taught it about purchased products and fixed two bugs in the process; these tests pin
 * both the new precedence and the old behaviour that was wrong.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class EntitlementResolutionIntegrationTests {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Test
    fun aPurchasedGrantEnablesSomethingThePlanDoesNot() {
        val fixture = seedTenantWithSubscription()
        grantProduct(fixture, entitlements = """{"module.pos": {"is_enabled": true, "value": {}}}""")

        val resolved = resolve(fixture.tenantId, "module.pos")

        assertEquals(true, resolved?.enabled)
        assertEquals("grant", resolved?.source)
    }

    @Test
    fun aSupportOverrideStillBeatsAPurchasedGrant() {
        val fixture = seedTenantWithSubscription()
        grantProduct(fixture, entitlements = """{"module.pos": {"is_enabled": true, "value": {}}}""")
        overrideEntitlement(fixture.tenantId, "module.pos", enabled = false)

        val resolved = resolve(fixture.tenantId, "module.pos")

        assertEquals(false, resolved?.enabled)
        assertEquals("override", resolved?.source, "An operator exception must outrank a purchase")
    }

    @Test
    fun anExpiredGrantStopsResolving() {
        val fixture = seedTenantWithSubscription()
        grantProduct(
            fixture,
            entitlements = """{"module.pos": {"is_enabled": true, "value": {}}}""",
            startsAtInterval = "-30 days",
            endsAtInterval = "-1 day",
        )

        assertNull(
            resolve(fixture.tenantId, "module.pos"),
            "A lapsed grant must stop granting; this is what makes expiry mean anything",
        )
    }

    @Test
    fun limitsTakeTheHighestAcrossOverlappingGrants() {
        val fixture = seedTenantWithSubscription()
        grantProduct(fixture, entitlements = """{"limit.rooms": {"is_enabled": true, "value": {"limit": 120}}}""")
        grantProduct(fixture, entitlements = """{"limit.rooms": {"is_enabled": true, "value": {"limit": 400}}}""")

        val resolved = resolve(fixture.tenantId, "limit.rooms")

        assertEquals("grant", resolved?.source)
        assertEquals(
            400L,
            resolved?.limit,
            "Two products that both raise a limit must not leave the tenant on the lower one",
        )
    }

    /**
     * `is_active` governs whether a plan may be sold. Using it during resolution meant
     * that retiring a superseded plan revoked every entitlement from every tenant still
     * on it, all at once.
     */
    @Test
    fun retiringAPlanDoesNotRevokeTheTenantsStillOnIt() {
        val fixture = seedTenantWithSubscription()
        jdbcTemplate.update(
            "INSERT INTO plan_entitlements (plan_id, entitlement_code, entitlement_value, is_enabled) VALUES (?, 'module.reports', '{}'::jsonb, true)",
            fixture.planId,
        )
        jdbcTemplate.update("UPDATE plans SET is_active = false WHERE id = ?", fixture.planId)

        val resolved = resolve(fixture.tenantId, "module.reports")

        assertEquals(true, resolved?.enabled)
        assertEquals("plan", resolved?.source)
    }

    /**
     * The old resolver fell back to `tenants.plan_id` when no service-granting
     * subscription existed, so a cancelled subscription kept everything and expiry was
     * decorative.
     */
    @Test
    fun aCancelledSubscriptionStopsGrantingPlanEntitlements() {
        val fixture = seedTenantWithSubscription()
        jdbcTemplate.update(
            "INSERT INTO plan_entitlements (plan_id, entitlement_code, entitlement_value, is_enabled) VALUES (?, 'module.reports', '{}'::jsonb, true)",
            fixture.planId,
        )
        assertEquals(true, resolve(fixture.tenantId, "module.reports")?.enabled)

        jdbcTemplate.update(
            "UPDATE tenant_subscriptions SET status = 'cancelled' WHERE tenant_id = ?",
            fixture.tenantId,
        )

        assertNull(
            resolve(fixture.tenantId, "module.reports"),
            "tenants.plan_id must not resurrect a cancelled subscription",
        )
    }

    /**
     * The other half of the rule, and the one that is easy to get wrong by over-correcting.
     *
     * Removing the `tenants.plan_id` fallback outright also revoked everything from a
     * tenant that simply had no subscription row yet — the state every tenant is in between
     * `INSERT INTO tenants` and onboarding writing its trialing row. The fallback therefore
     * fires on absence, never on a terminal status.
     */
    @Test
    fun aTenantThatNeverSubscribedStillResolvesItsAssignedPlan() {
        val fixture = seedTenantWithSubscription()
        jdbcTemplate.update(
            "INSERT INTO plan_entitlements (plan_id, entitlement_code, entitlement_value, is_enabled) VALUES (?, 'module.reports', '{}'::jsonb, true)",
            fixture.planId,
        )
        jdbcTemplate.update(
            "DELETE FROM tenant_subscriptions WHERE tenant_id = ?",
            fixture.tenantId,
        )

        assertEquals(
            true,
            resolve(fixture.tenantId, "module.reports")?.enabled,
            "a tenant with no subscription row at all must still get its plan",
        )
        assertEquals("plan", resolve(fixture.tenantId, "module.reports")?.source)
    }

    private data class Resolved(val enabled: Boolean, val source: String, val limit: Long?)

    private fun resolve(tenantId: UUID, code: String): Resolved? {
        return transactionTemplate.execute {
            jdbcTemplate.queryForObject(
                "SELECT set_config('app.current_tenant_id', ?, true)",
                String::class.java,
                tenantId.toString(),
            )
            jdbcTemplate.query(
                """
                SELECT is_enabled, source, (entitlement_value ->> 'limit')::bigint AS resolved_limit
                FROM effective_tenant_entitlement(?, ?)
                """.trimIndent(),
                { rs, _ ->
                    Resolved(
                        enabled = rs.getBoolean("is_enabled"),
                        source = rs.getString("source"),
                        limit = rs.getObject("resolved_limit")?.let { (it as Number).toLong() },
                    )
                },
                tenantId,
                code,
            ).firstOrNull()
        }
    }

    private fun grantProduct(
        fixture: TenantFixture,
        entitlements: String,
        startsAtInterval: String = "-1 hour",
        endsAtInterval: String = "30 days",
    ) {
        val productCode = "test-product-${UUID.randomUUID().toString().take(8)}"
        jdbcTemplate.update(
            // Not sellable: this product exists only to hang a grant off. Leaving it
            // sellable would put a priceless product into the catalog every other test
            // shares, breaking the invariant that anything on sale has a full price grid.
            """
            INSERT INTO peak_products (code, name, kind, is_sellable)
            VALUES (?, ?, 'addon', false)
            """.trimIndent(),
            productCode,
            "Test Product $productCode",
        )
        jdbcTemplate.update(
            """
            INSERT INTO peak_product_grants (
                tenant_id, product_code, source, status, starts_at, ends_at, granted_entitlements
            )
            VALUES (
                ?, ?, 'purchase', 'active',
                now() + interval '$startsAtInterval',
                now() + interval '$endsAtInterval',
                ?::jsonb
            )
            """.trimIndent(),
            fixture.tenantId,
            productCode,
            entitlements,
        )
    }

    private fun overrideEntitlement(tenantId: UUID, code: String, enabled: Boolean) {
        // An override records who approved it, so the test needs a real operator rather
        // than assuming the platform has already been bootstrapped.
        val operatorId = jdbcTemplate.query(
            "SELECT id FROM platform_users ORDER BY created_at LIMIT 1",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
        ).firstOrNull() ?: UUID.randomUUID().also { newOperatorId ->
            jdbcTemplate.update(
                "INSERT INTO platform_users (id, full_name, email) VALUES (?, ?, ?)",
                newOperatorId,
                "Entitlement Test Operator",
                "entitlement-test-$newOperatorId@peak.invalid",
            )
        }
        jdbcTemplate.update(
            """
            INSERT INTO tenant_entitlement_overrides (
                tenant_id, entitlement_code, entitlement_value, is_enabled,
                reason, approved_by_platform_user_id
            )
            VALUES (?, ?, '{}'::jsonb, ?, 'entitlement resolution test', ?)
            """.trimIndent(),
            tenantId,
            code,
            enabled,
            operatorId,
        )
    }

    private fun seedTenantWithSubscription(): TenantFixture {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId,
            "Entitlement Plan $planId",
            "entitlement-$planId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            tenantId,
            "Entitlement Tenant $tenantId",
            "ent-${tenantId.toString().take(8)}",
            "tenant_${tenantId.toString().replace("-", "")}",
            planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_subscriptions (tenant_id, plan_id, status, billing_cycle)
            VALUES (?, ?, 'active', 'monthly')
            """.trimIndent(),
            tenantId,
            planId,
        )
        return TenantFixture(tenantId, planId)
    }

    private data class TenantFixture(val tenantId: UUID, val planId: UUID)
}
