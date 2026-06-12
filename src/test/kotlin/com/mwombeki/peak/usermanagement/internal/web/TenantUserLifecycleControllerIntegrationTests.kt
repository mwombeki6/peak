package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.hamcrest.Matchers.containsString
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class TenantUserLifecycleControllerIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun disablesTenantUserThroughSecuredRoute() {
        val fixture = lifecycleFixture()
        insertAuthorizedFixture(fixture)

        mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/users/${fixture.targetUserId}/disable")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-lifecycle-disable")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-lifecycle-disable")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tenantId").value(fixture.tenantId.toString()))
            .andExpect(jsonPath("$.userId").value(fixture.targetUserId.toString()))
            .andExpect(jsonPath("$.action").value("disable"))
            .andExpect(jsonPath("$.status").value("disabled"))
            .andExpect(jsonPath("$.isActive").value(false))
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.replayed").value(false))

        val user = jdbcTemplate.queryForMap(
            """
            SELECT status, is_active
            FROM users
            WHERE tenant_id = ?
              AND id = ?
            """.trimIndent(),
            fixture.tenantId,
            fixture.targetUserId,
        )
        assertEquals("disabled", user["status"])
        assertEquals(false, user["is_active"])
    }

    @Test
    fun deniesTenantUserLifecycleRouteWithoutPermission() {
        val fixture = lifecycleFixture()
        insertFixtureWithoutPermission(fixture)

        mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/users/${fixture.targetUserId}/disable")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-lifecycle-denied")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-lifecycle-denied")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(containsString("Tenant user lacks required module permission")))
    }

    @Test
    fun revokesTenantUserIdentityLinkThroughSecuredRoute() {
        val fixture = lifecycleFixture()
        insertAuthorizedFixture(fixture)

        mockMvc.perform(
            post(
                "/api/v1/tenants/${fixture.tenantId}/users/" +
                        "${fixture.targetUserId}/identity-links/${fixture.identityLinkId}/revoke",
            )
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-lifecycle-link")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-lifecycle-link")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tenantId").value(fixture.tenantId.toString()))
            .andExpect(jsonPath("$.userId").value(fixture.targetUserId.toString()))
            .andExpect(jsonPath("$.identityLinkId").value(fixture.identityLinkId.toString()))
            .andExpect(jsonPath("$.revokedAt").isString)
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.replayed").value(false))

        val revokedAt = jdbcTemplate.queryForObject(
            """
            SELECT revoked_at
            FROM identity_links
            WHERE id = ?
            """.trimIndent(),
            java.time.OffsetDateTime::class.java,
            fixture.identityLinkId,
        )
        assertNotNull(revokedAt)
    }

    private fun lifecycleFixture(): LifecycleFixture {
        val targetUserId = UUID.randomUUID()
        return LifecycleFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            actorUserId = UUID.randomUUID(),
            actorRoleId = UUID.randomUUID(),
            targetUserId = targetUserId,
            identityLinkId = UUID.randomUUID(),
            permissionId = UUID.randomUUID(),
            issuer = "https://issuer.example.com/realms/${UUID.randomUUID()}",
            subject = "target-subject-$targetUserId",
            targetEmail = "target-$targetUserId@example.com",
        )
    }

    private fun insertAuthorizedFixture(fixture: LifecycleFixture) {
        insertFixtureWithoutPermission(fixture)
        jdbcTemplate.update(
            """
            INSERT INTO permissions (id, tenant_id, code, description)
            VALUES (?, ?, 'tenant.users.manage', 'Manage tenant users')
            """.trimIndent(),
            fixture.permissionId,
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
            VALUES (?, ?)
            """.trimIndent(),
            fixture.actorRoleId,
            fixture.permissionId,
        )
    }

    private fun insertFixtureWithoutPermission(fixture: LifecycleFixture) {
        jdbcTemplate.update(
            """
            INSERT INTO plans (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.planId,
            "Plan ${fixture.planId}",
            "plan-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, schema_name, plan_id)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            fixture.tenantId,
            "Tenant ${fixture.tenantId}",
            "tenant-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
            VALUES (?, 'tenant_admin', true, true)
            """.trimIndent(),
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            fixture.actorUserId,
            fixture.tenantId,
            "Actor ${fixture.actorUserId}",
            "actor-${fixture.actorUserId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            fixture.targetUserId,
            fixture.tenantId,
            "Target ${fixture.targetUserId}",
            fixture.targetEmail,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.actorRoleId,
            fixture.tenantId,
            "Actor Role ${fixture.actorRoleId}",
            "actor-${fixture.actorRoleId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.actorUserId,
            fixture.tenantId,
            fixture.actorRoleId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO identity_links (
                id,
                identity_mode,
                provider,
                issuer,
                subject,
                tenant_id,
                user_id,
                email
            )
            VALUES (?, 'tenant', 'oidc', ?, ?, ?, ?, ?)
            """.trimIndent(),
            fixture.identityLinkId,
            fixture.issuer,
            fixture.subject,
            fixture.tenantId,
            fixture.targetUserId,
            fixture.targetEmail,
        )
    }

    private data class LifecycleFixture(
        val planId: UUID,
        val tenantId: UUID,
        val actorUserId: UUID,
        val actorRoleId: UUID,
        val targetUserId: UUID,
        val identityLinkId: UUID,
        val permissionId: UUID,
        val issuer: String,
        val subject: String,
        val targetEmail: String,
    )
}
