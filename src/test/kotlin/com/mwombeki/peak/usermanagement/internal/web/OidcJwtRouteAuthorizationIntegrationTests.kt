package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt as mockJwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OidcJwtRouteAuthorizationIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun authorizesTenantRouteUsingOidcIdentityLinkWithoutIdentityHeaders() {
        val fixture = tenantOidcFixture()
        insertAuthorizedTenantFixture(fixture)

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/roles")
                .secure(true)
                .with(oidcJwt(fixture.issuer, fixture.subject, fixture.email))
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-oidc-route-tenant"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[*].tenantRoleId", hasItem(fixture.actorRoleId.toString())))
            .andExpect(jsonPath("$[*].code", hasItem(fixture.actorRoleCode)))
            .andExpect(content().string(containsString("tenant.users.manage")))
    }

    @Test
    fun deniesTenantRouteWhenOidcIdentityLinkIsRevoked() {
        val fixture = tenantOidcFixture(revoked = true)
        insertAuthorizedTenantFixture(fixture)

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/roles")
                .secure(true)
                .with(oidcJwt(fixture.issuer, fixture.subject, fixture.email))
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-oidc-route-revoked"),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(content().string(containsString("Tenant identity is required")))
    }

    @Test
    fun deniesTenantRouteWhenOidcTenantUserIsDisabled() {
        val fixture = tenantOidcFixture(userStatus = "disabled", userActive = false)
        insertAuthorizedTenantFixture(fixture)

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/roles")
                .secure(true)
                .with(oidcJwt(fixture.issuer, fixture.subject, fixture.email))
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-oidc-route-disabled-user"),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(content().string(containsString("Tenant identity is required")))
    }

    @Test
    fun deniesTenantRouteWhenOidcTenantUserIsLocked() {
        val fixture = tenantOidcFixture(locked = true)
        insertAuthorizedTenantFixture(fixture)

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/roles")
                .secure(true)
                .with(oidcJwt(fixture.issuer, fixture.subject, fixture.email))
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-oidc-route-locked-user"),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(content().string(containsString("Tenant identity is required")))
    }

    @Test
    fun authorizesPlatformRouteUsingOidcIdentityLinkWithoutIdentityHeaders() {
        val fixture = platformOidcFixture()
        insertAuthorizedPlatformFixture(fixture)

        mockMvc.perform(
            get("/api/v1/platform/tenants/${fixture.tenantId}")
                .secure(true)
                .with(oidcJwt(fixture.issuer, fixture.subject, fixture.email))
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-oidc-route-platform"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(fixture.tenantId.toString()))
            .andExpect(jsonPath("$.name").value(fixture.tenantName))
    }

    @Test
    fun deniesPlatformRouteForTenantOidcIdentity() {
        val fixture = tenantOidcFixture()
        insertAuthorizedTenantFixture(fixture)

        mockMvc.perform(
            get("/api/v1/platform/tenants/${fixture.tenantId}")
                .secure(true)
                .with(oidcJwt(fixture.issuer, fixture.subject, fixture.email))
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-oidc-route-tenant-platform"),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(content().string(containsString("Platform identity is required")))
    }

    private fun tenantOidcFixture(
        revoked: Boolean = false,
        userStatus: String = "active",
        userActive: Boolean = true,
        locked: Boolean = false,
    ): TenantOidcFixture {
        val tenantId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val roleId = UUID.randomUUID()
        val subject = "tenant-subject-$userId"
        return TenantOidcFixture(
            planId = UUID.randomUUID(),
            tenantId = tenantId,
            userId = userId,
            actorRoleId = roleId,
            actorRoleCode = "oidc-tenant-role-$roleId",
            permissionId = UUID.randomUUID(),
            identityLinkId = UUID.randomUUID(),
            issuer = "https://issuer.example.com/realms/peak",
            subject = subject,
            email = "$subject@example.com",
            revoked = revoked,
            userStatus = userStatus,
            userActive = userActive,
            locked = locked,
        )
    }

    private fun platformOidcFixture(): PlatformOidcFixture {
        val platformUserId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val subject = "platform-subject-$platformUserId"
        return PlatformOidcFixture(
            planId = UUID.randomUUID(),
            tenantId = tenantId,
            tenantName = "OIDC Platform Tenant $tenantId",
            platformUserId = platformUserId,
            platformRoleId = UUID.randomUUID(),
            identityLinkId = UUID.randomUUID(),
            issuer = "https://issuer.example.com/realms/peak",
            subject = subject,
            email = "$subject@example.com",
        )
    }

    private fun insertAuthorizedTenantFixture(fixture: TenantOidcFixture) {
        insertPlan(fixture.planId)
        insertTenant(fixture.tenantId, fixture.planId)
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
            VALUES (?, 'tenant_admin', true, true)
            """.trimIndent(),
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (
                id,
                tenant_id,
                full_name,
                email,
                status,
                is_active,
                locked_until
            )
            VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() + interval '1 hour' ELSE NULL END)
            """.trimIndent(),
            fixture.userId,
            fixture.tenantId,
            "Tenant OIDC User ${fixture.userId}",
            fixture.email,
            fixture.userStatus,
            fixture.userActive,
            fixture.locked,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.actorRoleId,
            fixture.tenantId,
            "OIDC Tenant Role ${fixture.actorRoleId}",
            fixture.actorRoleCode,
        )
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
        jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.userId,
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
                email,
                revoked_at
            )
            VALUES (?, 'tenant', 'oidc', ?, ?, ?, ?, ?, CASE WHEN ? THEN now() ELSE NULL END)
            """.trimIndent(),
            fixture.identityLinkId,
            fixture.issuer,
            fixture.subject,
            fixture.tenantId,
            fixture.userId,
            fixture.email,
            fixture.revoked,
        )
    }

    private fun insertAuthorizedPlatformFixture(fixture: PlatformOidcFixture) {
        insertPlan(fixture.planId)
        insertTenant(
            tenantId = fixture.tenantId,
            planId = fixture.planId,
            tenantName = fixture.tenantName,
        )
        insertTenantProfile(fixture)
        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, full_name, email, status)
            VALUES (?, ?, ?, 'active')
            """.trimIndent(),
            fixture.platformUserId,
            "Platform OIDC User ${fixture.platformUserId}",
            fixture.email,
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_roles (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.platformRoleId,
            "OIDC Platform Role ${fixture.platformRoleId}",
            "oidc-platform-${fixture.platformRoleId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
            SELECT ?, id
            FROM platform_permissions
            WHERE code IN ('platform.tenants.view', 'platform.tenants.manage')
            """.trimIndent(),
            fixture.platformRoleId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_user_roles (platform_user_id, platform_role_id)
            VALUES (?, ?)
            """.trimIndent(),
            fixture.platformUserId,
            fixture.platformRoleId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO identity_links (
                id,
                identity_mode,
                provider,
                issuer,
                subject,
                platform_user_id,
                email
            )
            VALUES (?, 'platform', 'oidc', ?, ?, ?, ?)
            """.trimIndent(),
            fixture.identityLinkId,
            fixture.issuer,
            fixture.subject,
            fixture.platformUserId,
            fixture.email,
        )
    }

    private fun insertPlan(planId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO plans (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            planId,
            "Plan $planId",
            "plan-${planId.toString().take(8)}",
        )
    }

    private fun insertTenant(
        tenantId: UUID,
        planId: UUID,
        tenantName: String = "Tenant $tenantId",
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, schema_name, plan_id)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            tenantId,
            tenantName,
            "tenant-${tenantId.toString().take(8)}",
            "tenant_${tenantId}".replace("-", "_"),
            planId,
        )
    }

    private fun insertTenantProfile(fixture: PlatformOidcFixture) {
        jdbcTemplate.update(
            """
            INSERT INTO tenant_profiles (
                tenant_id,
                legal_name,
                entity_type,
                registration_country_code,
                business_phone,
                business_email,
                registered_address,
                billing_address
            )
            VALUES (?, ?, 'limited_company', 'TZ', '+255700000000', ?, '{}'::jsonb, '{}'::jsonb)
            """.trimIndent(),
            fixture.tenantId,
            fixture.tenantName,
            "tenant-${fixture.tenantId}@example.com",
        )
    }

    private fun oidcJwt(
        issuer: String,
        subject: String,
        email: String,
    ) = mockJwt().jwt { jwt ->
        jwt.claim("iss", issuer)
        jwt.claim("sub", subject)
        jwt.claim("email", email)
        jwt.claim("email_verified", true)
        jwt.claim("aud", listOf("peak-api"))
    }

    private data class TenantOidcFixture(
        val planId: UUID,
        val tenantId: UUID,
        val userId: UUID,
        val actorRoleId: UUID,
        val actorRoleCode: String,
        val permissionId: UUID,
        val identityLinkId: UUID,
        val issuer: String,
        val subject: String,
        val email: String,
        val revoked: Boolean,
        val userStatus: String,
        val userActive: Boolean,
        val locked: Boolean,
    )

    private data class PlatformOidcFixture(
        val planId: UUID,
        val tenantId: UUID,
        val tenantName: String,
        val platformUserId: UUID,
        val platformRoleId: UUID,
        val identityLinkId: UUID,
        val issuer: String,
        val subject: String,
        val email: String,
    )
}
