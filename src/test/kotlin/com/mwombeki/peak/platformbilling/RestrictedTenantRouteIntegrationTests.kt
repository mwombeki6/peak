package com.mwombeki.peak.platformbilling

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Proves the restriction is on the path a customer actually takes.
 *
 * `tenant_restriction_permits` is already tested directly, but that only shows the SQL is
 * right. The SQL is only *effective* if every request reaches it, and the way that fails in
 * practice is a route whose guard mode skips `can_access_module` altogether. So these drive
 * real HTTP through the real interceptor chain.
 *
 * Denials assert the mutation did not happen as well as the status code. A 403 with a
 * completed side effect is worse than a 200, because nothing looks wrong.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = ["peak.security.request-context.allow-header-identity=true"],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RestrictedTenantRouteIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun resetSession() {
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun aSuspendedTenantCanStillReachTheBillingPagesThatEndTheSuspension() {
        val fixture = tenantInState("suspended")

        // The escape hatch. If any of these were denied, the debt would be unpayable and the
        // tenant would be trapped in suspension with no route out.
        expectStatus(get(url(fixture, "/billing/catalog")), fixture, 200, "view the catalog")
        expectStatus(get(url(fixture, "/billing/purchases")), fixture, 200, "see what they owe")
        expectStatus(get(url(fixture, "/billing/renewal-offers")), fixture, 200, "see the renewal offer")
    }

    @Test
    fun aSuspendedTenantIsDeniedAdministrativeMutationAndNothingChanges() {
        val fixture = tenantInState("suspended")
        val before = tenantUserCount(fixture.tenantId)

        val status = performStatus(
            post(url(fixture, "/users/invitations"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"blocked-${fixture.tenantId}@example.com",
                     "fullName":"Blocked Invite",
                     "tenantRoleId":"${fixture.invitableRoleId}",
                     "expiresInHours":24}
                    """.trimIndent(),
                ),
            fixture,
            idempotencyKey = "idem-suspended-invite",
        )

        assertEquals(403, status, "inviting a user is growth and must be denied under suspension")
        assertEquals(
            before,
            tenantUserCount(fixture.tenantId),
            "a denial that still created the user would be worse than an allow, " +
                "because nothing would look wrong",
        )
        assertEquals(
            0,
            pendingInvitationCount(fixture.tenantId),
            "no invitation row may survive a denied request",
        )
    }

    @Test
    fun aRestrictedTenantIsDeniedGrowthButKeepsBillingAccess() {
        val fixture = tenantInState("restricted")
        val before = tenantUserCount(fixture.tenantId)

        val inviteStatus = performStatus(
            post(url(fixture, "/users/invitations"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"blocked-r-${fixture.tenantId}@example.com",
                     "fullName":"Blocked Invite",
                     "tenantRoleId":"${fixture.invitableRoleId}",
                     "expiresInHours":24}
                    """.trimIndent(),
                ),
            fixture,
            idempotencyKey = "idem-restricted-invite",
        )

        assertEquals(403, inviteStatus)
        assertEquals(before, tenantUserCount(fixture.tenantId))
        expectStatus(get(url(fixture, "/billing/catalog")), fixture, 200, "billing stays reachable")
    }

    @Test
    fun anActiveTenantIsNotRestrictedAtAll() {
        val fixture = tenantInState("active")

        // The control. Without this, the denials above could be caused by a missing
        // permission rather than by the restriction, and the tests would prove nothing.
        val status = performStatus(
            post(url(fixture, "/users/invitations"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"allowed-${fixture.tenantId}@example.com",
                     "fullName":"Allowed Invite",
                     "tenantRoleId":"${fixture.invitableRoleId}",
                     "expiresInHours":24}
                    """.trimIndent(),
                ),
            fixture,
            idempotencyKey = "idem-active-invite",
        )

        assertTrue(
            status in 200..299,
            "an active tenant must be able to invite users, or the denials above prove " +
                "nothing about restriction — they would just be a missing permission (got $status)",
        )
        assertEquals(1, pendingInvitationCount(fixture.tenantId))
    }

    @Test
    fun theGuardIsWhatDeniesRatherThanTheAbsenceOfAPermission() {
        val fixture = tenantInState("suspended")

        // The same user, same role, same permission — the only difference from the active
        // case is lifecycle_status. Asserted through the guard's own function so a failure
        // here points at the restriction rather than at the fixture.
        assertEquals(
            false,
            jdbcTemplate.queryForObject(
                "SELECT tenant_restriction_permits(?, 'tenant.users.manage')",
                Boolean::class.java,
                fixture.tenantId,
            ),
        )
        assertEquals(
            true,
            jdbcTemplate.queryForObject(
                "SELECT tenant_restriction_permits(?, 'tenant.subscription.view')",
                Boolean::class.java,
                fixture.tenantId,
            ),
        )
    }

    private fun url(fixture: RouteFixture, suffix: String) =
        "/api/v1/tenants/${fixture.tenantId}$suffix"

    private fun expectStatus(
        builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder,
        fixture: RouteFixture,
        expected: Int,
        label: String,
    ) {
        assertEquals(expected, performStatus(builder, fixture), label)
    }

    private fun performStatus(
        builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder,
        fixture: RouteFixture,
        idempotencyKey: String? = null,
    ): Int {
        builder.secure(true)
            .header(PeakRequestHeaders.CORRELATION_ID, "corr-${UUID.randomUUID()}")
            .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
            .header(PeakRequestHeaders.TENANT_USER_ID, fixture.userId.toString())
        idempotencyKey?.let { builder.header(PeakRequestHeaders.IDEMPOTENCY_KEY, it) }
        return mockMvc.perform(builder).andReturn().response.status
    }

    private fun tenantUserCount(tenantId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM users WHERE tenant_id = ?",
            Int::class.java,
            tenantId,
        ) ?: 0

    private fun pendingInvitationCount(tenantId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM tenant_user_invitations WHERE tenant_id = ?",
            Int::class.java,
            tenantId,
        ) ?: 0

    private fun tenantInState(lifecycleStatus: String): RouteFixture {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val roleId = UUID.randomUUID()

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
                tenant_id, lifecycle_status, verification_status, provisioning_status,
                subscription_status, service_status, offboarding_status
            ) VALUES (?, ?, 'verified', 'ready', 'past_due', 'operational', 'none')
            """.trimIndent(),
            tenantId,
            lifecycleStatus,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_subscriptions (
                tenant_id, plan_id, status, billing_cycle, billing_currency,
                provider, current_period_starts_at
            ) VALUES (?, ?, 'past_due', 'monthly', 'TZS', 'manual', now() - interval '60 days')
            """.trimIndent(),
            tenantId,
            planId,
        )
        jdbcTemplate.update(
            "INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured) VALUES (?, 'tenant_admin', true, true)",
            tenantId,
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
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code, is_system)
            VALUES (?, ?, 'Tenant Administrator', 'tenant_admin', true)
            """.trimIndent(),
            roleId,
            tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
            VALUES (?, ?, ?)
            """.trimIndent(),
            userId,
            tenantId,
            roleId,
        )

        // Everything the routes under test require, so a denial can only come from the
        // restriction and never from a missing grant.
        listOf(
            "tenant.subscription.view",
            "tenant.subscription.purchase",
            "tenant.users.manage",
            "tenant.roles.view",
        ).forEach { code ->
            val permissionId = UUID.randomUUID()
            jdbcTemplate.update(
                "INSERT INTO permissions (id, tenant_id, code, description) VALUES (?, ?, ?, ?)",
                permissionId,
                tenantId,
                code,
                code,
            )
            jdbcTemplate.update(
                "INSERT INTO tenant_role_permissions (tenant_role_id, permission_id) VALUES (?, ?)",
                roleId,
                permissionId,
            )
        }

        // A second, non-system role: invitations refuse system roles, and that 400 would
        // otherwise be mistaken for the guard doing its job.
        val invitableRoleId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code, is_system)
            VALUES (?, ?, 'Front Desk', 'front_desk', false)
            """.trimIndent(),
            invitableRoleId,
            tenantId,
        )

        return RouteFixture(tenantId, userId, roleId, invitableRoleId)
    }

    private data class RouteFixture(
        val tenantId: UUID,
        val userId: UUID,
        val roleId: UUID,
        val invitableRoleId: UUID,
    )
}
