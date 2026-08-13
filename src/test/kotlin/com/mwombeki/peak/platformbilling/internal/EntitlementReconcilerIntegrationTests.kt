package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The tests that decide whether any of this is worth having.
 *
 * A subscription engine that grants but never revokes is a worse liability than no engine
 * at all, and one that fights the customer is a support queue. Both are checked here
 * against `can_access_module`, which is what the running system actually asks.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class EntitlementReconcilerIntegrationTests {

    @Autowired
    private lateinit var reconciler: EntitlementReconciler

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var databaseSessionContext: DatabaseSessionContext

    @AfterTest
    fun resetSession() {
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun aPerPropertyAddOnUnlocksExactlyThePropertiesItWasBoughtFor() {
        val fixture = tenantWithProperties(count = 3)
        grantPos(fixture, fixture.propertyIds.take(2))

        reconciler.reconcileTenant(fixture.tenantId, "corr-pos-partial")

        assertTrue(canAccess(fixture, fixture.propertyIds[0]), "property 1 was paid for")
        assertTrue(canAccess(fixture, fixture.propertyIds[1]), "property 2 was paid for")
        assertFalse(canAccess(fixture, fixture.propertyIds[2]), "property 3 was not paid for")
    }

    @Test
    fun anExpiredGrantActuallyRevokesRatherThanLingering() {
        val fixture = tenantWithProperties(count = 2)
        grantPos(fixture, fixture.propertyIds)
        reconciler.reconcileTenant(fixture.tenantId, "corr-pos-before-expiry")
        assertTrue(canAccess(fixture, fixture.propertyIds[0]))

        jdbcTemplate.update(
            """
            UPDATE peak_product_grants
            SET starts_at = now() - interval '60 days', ends_at = now() - interval '1 day'
            WHERE tenant_id = ?
            """.trimIndent(),
            fixture.tenantId,
        )

        reconciler.reconcileTenant(fixture.tenantId, "corr-pos-after-expiry")

        assertFalse(
            canAccess(fixture, fixture.propertyIds[0]),
            "an expired grant must revoke, or expiry means nothing",
        )
        assertFalse(canAccess(fixture, fixture.propertyIds[1]))
        assertEquals(
            0,
            enabledPropertyModuleCount(fixture.tenantId, "pos"),
            "property_modules must be turned off too, not just the tenant flag",
        )
    }

    @Test
    fun aModuleTheTenantTurnedOffDeliberatelyStaysOff() {
        val fixture = tenantWithProperties(count = 1)
        grantPos(fixture, fixture.propertyIds)
        reconciler.reconcileTenant(fixture.tenantId, "corr-pos-initial")
        assertTrue(canAccess(fixture, fixture.propertyIds[0]))

        // The tenant decides they do not want POS after all, while still paying for it.
        jdbcTemplate.update(
            "UPDATE tenant_modules SET is_enabled = false WHERE tenant_id = ? AND module_id = 'pos'",
            fixture.tenantId,
        )

        reconciler.reconcileTenant(fixture.tenantId, "corr-pos-respect-choice")

        assertFalse(
            moduleEnabled(fixture.tenantId, "pos"),
            "the reconciler must not overrule an administrator who turned a module off",
        )
    }

    @Test
    fun reconcilingTwiceChangesNothingTheSecondTime() {
        val fixture = tenantWithProperties(count = 2)
        grantPos(fixture, fixture.propertyIds)

        val first = reconciler.reconcileTenant(fixture.tenantId, "corr-idem-1")
        val second = reconciler.reconcileTenant(fixture.tenantId, "corr-idem-2")

        assertTrue(first.activated > 0, "the first pass must actually do something")
        assertEquals(0, second.activated, "convergence must be a no-op once converged")
        assertEquals(0, second.deactivated)
        assertFalse(second.failed)
    }

    @Test
    fun tenantAdminSurvivesTheLossOfEveryEntitlement() {
        val fixture = tenantWithProperties(count = 1)
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
            VALUES (?, 'tenant_admin', true, true)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            fixture.tenantId,
        )

        // No grants at all: every entitlement has lapsed.
        reconciler.reconcileTenant(fixture.tenantId, "corr-lockout")

        assertTrue(
            moduleEnabled(fixture.tenantId, "tenant_admin"),
            "revoking tenant_admin would lock the tenant out of the page where they pay, " +
                "which is a trap with no exit",
        )
    }

    @Test
    fun aDanglingGrantForADeletedPropertyDoesNotStopTheRest() {
        val fixture = tenantWithProperties(count = 2)
        grantPos(fixture, fixture.propertyIds)
        // A grant naming a property that no longer exists.
        jdbcTemplate.update(
            """
            INSERT INTO peak_product_grants (
                tenant_id, property_id, product_code, source, status, granted_entitlements
            ) VALUES (?, ?, 'peak_pos', 'purchase', 'active',
                      '{"module.pos": {"is_enabled": true, "auto_activate": true, "value": {}}}'::jsonb)
            """.trimIndent(),
            fixture.tenantId,
            UUID.randomUUID(),
        )

        val outcome = reconciler.reconcileTenant(fixture.tenantId, "corr-dangling")

        assertFalse(outcome.failed, "one bad grant must not fail the tenant's whole convergence")
        assertTrue(canAccess(fixture, fixture.propertyIds[0]))
    }

    /**
     * Asks the question the running system asks. `can_access_module` requires the session
     * to be bound to the *user*, not merely the tenant, so this binds the way a request
     * would rather than setting GUCs by hand.
     */
    private fun canAccess(fixture: TenantFixture, propertyId: UUID): Boolean {
        return transactionTemplate.execute {
            databaseSessionContext.bind(
                RequestIdentity.Tenant(
                    tenantId = fixture.tenantId,
                    tenantUserId = fixture.userId,
                    correlationId = "corr-can-access",
                ),
            )
            jdbcTemplate.queryForObject(
                "SELECT can_access_module(?, ?, ?, 'pos', 'pos.view')",
                Boolean::class.java,
                fixture.userId,
                fixture.tenantId,
                propertyId,
            )
        } == true
    }

    private fun moduleEnabled(tenantId: UUID, moduleId: String): Boolean {
        return jdbcTemplate.queryForObject(
            "SELECT coalesce(bool_or(is_enabled), false) FROM tenant_modules WHERE tenant_id = ? AND module_id = ?",
            Boolean::class.java,
            tenantId,
            moduleId,
        ) == true
    }

    private fun enabledPropertyModuleCount(tenantId: UUID, moduleId: String): Int {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM property_modules WHERE tenant_id = ? AND module_id = ? AND is_enabled = true",
            Int::class.java,
            tenantId,
            moduleId,
        ) ?: 0
    }

    private fun grantPos(fixture: TenantFixture, propertyIds: List<UUID>) {
        propertyIds.forEach { propertyId ->
            jdbcTemplate.update(
                """
                INSERT INTO peak_product_grants (
                    tenant_id, property_id, product_code, source, status,
                    starts_at, ends_at, granted_entitlements
                ) VALUES (?, ?, 'peak_pos', 'purchase', 'active',
                          now() - interval '1 hour', now() + interval '30 days',
                          '{"module.pos": {"is_enabled": true, "auto_activate": true, "value": {}}}'::jsonb)
                """.trimIndent(),
                fixture.tenantId,
                propertyId,
            )
        }
    }

    private fun tenantWithProperties(count: Int): TenantFixture {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val roleId = UUID.randomUUID()
        val permissionId = UUID.randomUUID()

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
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            userId,
            tenantId,
            "Owner $userId",
            "owner-$userId@example.com",
        )
        // The property permission path runs through roles -> role_permissions, which is a
        // different chain from the tenant-wide one (tenant_roles -> tenant_role_permissions).
        jdbcTemplate.update(
            "INSERT INTO roles (id, tenant_id, name) VALUES (?, ?, ?)",
            roleId,
            tenantId,
            "Owner $roleId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO permissions (id, tenant_id, code, description)
            VALUES (?, ?, 'pos.view', 'View POS')
            """.trimIndent(),
            permissionId,
            tenantId,
        )
        jdbcTemplate.update(
            "INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
            roleId,
            permissionId,
        )

        val propertyIds = (1..count).map { index ->
            val propertyId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO properties (id, tenant_id, name, code, type, status, is_active)
                VALUES (?, ?, ?, ?, 'HOTEL', 'active', true)
                """.trimIndent(),
                propertyId,
                tenantId,
                "Property $index",
                "R$index-${propertyId.toString().take(6)}",
            )
            jdbcTemplate.update(
                """
                INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                userId,
                propertyId,
                roleId,
                tenantId,
            )
            propertyId
        }

        return TenantFixture(tenantId, userId, propertyIds)
    }

    private data class TenantFixture(
        val tenantId: UUID,
        val userId: UUID,
        val propertyIds: List<UUID>,
    )
}
