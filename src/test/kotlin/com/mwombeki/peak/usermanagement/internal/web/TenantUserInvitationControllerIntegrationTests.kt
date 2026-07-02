package com.mwombeki.peak.usermanagement.internal.web

import com.jayway.jsonpath.JsonPath
import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt as mockJwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
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
class TenantUserInvitationControllerIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun invitesTenantUserThroughSecuredRoute() {
        val fixture = tenantFixture()
        insertAuthorizedTenantFixture(fixture)

        val result = mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/users/invitations")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-invite")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-invite")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.inviterUserId.toString())
                .content(
                    """
                    {
                      "email": "WebInvite-${fixture.tenantId}@Example.com",
                      "fullName": "Web Invite",
                      "tenantRoleId": "${fixture.invitedRoleId}",
                      "expiresInHours": 24,
                      "metadata": {
                        "source": "mockmvc"
                      }
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", containsString("/users/invitations/")))
            .andExpect(jsonPath("$.tenantId").value(fixture.tenantId.toString()))
            .andExpect(jsonPath("$.email").value("webinvite-${fixture.tenantId}@example.com"))
            .andExpect(jsonPath("$.tenantRoleId").value(fixture.invitedRoleId.toString()))
            .andExpect(jsonPath("$.invitationToken").isString)
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val invitationId = UUID.fromString(JsonPath.read(result.response.contentAsString, "$.invitationId"))
        val invitationToken = JsonPath.read<String>(result.response.contentAsString, "$.invitationToken")

        assertNotNull(invitationToken)
        val row = jdbcTemplate.queryForMap(
            """
            SELECT tenant_id, email, token_hash, status
            FROM tenant_user_invitations
            WHERE id = ?
            """.trimIndent(),
            invitationId,
        )

        assertEquals(fixture.tenantId, row["tenant_id"])
        assertEquals("webinvite-${fixture.tenantId}@example.com", row["email"])
        assertEquals("pending", row["status"])
        assertNotNull(row["token_hash"])
    }

    @Test
    fun deniesTenantInvitationRouteWithoutPermission() {
        val fixture = tenantFixture()
        insertTenantFixtureWithoutPermission(fixture)

        mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/users/invitations")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-invite-denied")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-invite-denied")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.inviterUserId.toString())
                .content(
                    """
                    {
                      "email": "denied-${fixture.tenantId}@example.com",
                      "tenantRoleId": "${fixture.invitedRoleId}"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(containsString("Tenant user lacks required module permission")))
    }

    @Test
    fun acceptsTenantInvitationThroughPublicTokenRoute() {
        val fixture = tenantFixture()
        insertAuthorizedTenantFixture(fixture)

        val inviteResult = mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/users/invitations")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-accept-create")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-accept-create")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.inviterUserId.toString())
                .content(
                    """
                    {
                      "email": "accept-web-${fixture.tenantId}@example.com",
                      "tenantRoleId": "${fixture.invitedRoleId}",
                      "fullName": "Accepted Web"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andReturn()

        val invitationToken = JsonPath.read<String>(
            inviteResult.response.contentAsString,
            "$.invitationToken",
        )
        val invitationId = JsonPath.read<String>(
            inviteResult.response.contentAsString,
            "$.invitationId",
        )

        val acceptResult = mockMvc.perform(
            post("/api/v1/invitations/accept")
                .secure(true)
                .with(
                    oidcJwt(
                        issuer = "https://issuer.example.com/realms/peak",
                        subject = "web-subject-${fixture.tenantId}",
                        email = "ACCEPT-WEB-${fixture.tenantId}@Example.com",
                    ),
                )
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-accept")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-accept")
                .content(
                    """
                    {
                      "invitationToken": "$invitationToken",
                      "issuer": "https://forged.example.com/realms/attacker",
                      "subject": "forged-subject",
                      "email": "forged@example.com",
                      "fullName": "Accepted Web"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.invitationId").value(invitationId))
            .andExpect(jsonPath("$.tenantId").value(fixture.tenantId.toString()))
            .andExpect(jsonPath("$.tenantRoleId").value(fixture.invitedRoleId.toString()))
            .andExpect(jsonPath("$.email").value("accept-web-${fixture.tenantId}@example.com"))
            .andExpect(jsonPath("$.identityLinkId").isString)
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val userId = UUID.fromString(JsonPath.read(acceptResult.response.contentAsString, "$.userId"))
        val roleCount = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM user_tenant_roles
            WHERE tenant_id = ?
              AND user_id = ?
              AND tenant_role_id = ?
            """.trimIndent(),
            Int::class.java,
            fixture.tenantId,
            userId,
            fixture.invitedRoleId,
        )

        assertEquals(1, roleCount)

        val identityLinkId = UUID.fromString(
            JsonPath.read(acceptResult.response.contentAsString, "$.identityLinkId"),
        )
        val identityLink = jdbcTemplate.queryForMap(
            """
            SELECT issuer, subject, email
            FROM identity_links
            WHERE id = ?
            """.trimIndent(),
            identityLinkId,
        )

        assertEquals("https://issuer.example.com/realms/peak", identityLink["issuer"])
        assertEquals("web-subject-${fixture.tenantId}", identityLink["subject"])
        assertEquals("accept-web-${fixture.tenantId}@example.com", identityLink["email"])
    }

    @Test
    fun rejectsInvalidInvitationTokenThroughPublicRoute() {
        mockMvc.perform(
            post("/api/v1/invitations/accept")
                .secure(true)
                .with(
                    oidcJwt(
                        issuer = "https://issuer.example.com/realms/peak",
                        subject = "missing-subject",
                        email = "missing@example.com",
                    ),
                )
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-invalid-token")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-invalid-token")
                .content(
                    """
                    {
                      "invitationToken": "not-a-valid-token"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(containsString("Invitation acceptance was rejected")))
            .andExpect(content().string(not(containsString("ERROR:"))))
    }

    @Test
    fun rejectsInvitationAcceptanceWithoutOidcJwt() {
        mockMvc.perform(
            post("/api/v1/invitations/accept")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-accept-no-jwt")
                .content(
                    """
                    {
                      "invitationToken": "not-a-valid-token"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun rejectsInvitationAcceptanceWithUnverifiedOidcEmail() {
        mockMvc.perform(
            post("/api/v1/invitations/accept")
                .secure(true)
                .with(
                    oidcJwt(
                        issuer = "https://issuer.example.com/realms/peak",
                        subject = "unverified-subject",
                        email = "unverified@example.com",
                        emailVerified = false,
                    ),
                )
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-accept-unverified")
                .content(
                    """
                    {
                      "invitationToken": "not-a-valid-token"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(containsString("JWT email must be verified")))
    }

    private fun tenantFixture(): WebTenantFixture {
        return WebTenantFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            inviterUserId = UUID.randomUUID(),
            inviterRoleId = UUID.randomUUID(),
            invitedRoleId = UUID.randomUUID(),
            permissionId = UUID.randomUUID(),
        )
    }

    private fun insertAuthorizedTenantFixture(fixture: WebTenantFixture) {
        insertTenantFixtureWithoutPermission(fixture)
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
            fixture.inviterRoleId,
            fixture.permissionId,
        )
    }

    private fun insertTenantFixtureWithoutPermission(fixture: WebTenantFixture) {
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
            INSERT INTO users (id, tenant_id, full_name, email, status)
            VALUES (?, ?, ?, ?, 'active')
            """.trimIndent(),
            fixture.inviterUserId,
            fixture.tenantId,
            "Inviter ${fixture.inviterUserId}",
            "inviter-${fixture.inviterUserId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.inviterRoleId,
            fixture.tenantId,
            "Inviter Role ${fixture.inviterRoleId}",
            "inviter-${fixture.inviterRoleId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.invitedRoleId,
            fixture.tenantId,
            "Invited Role ${fixture.invitedRoleId}",
            "invited-${fixture.invitedRoleId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.inviterUserId,
            fixture.tenantId,
            fixture.inviterRoleId,
        )
    }

    private fun oidcJwt(
        issuer: String,
        subject: String,
        email: String,
        emailVerified: Boolean = true,
    ) = mockJwt().jwt { jwt ->
        jwt.claim("iss", issuer)
        jwt.claim("sub", subject)
        jwt.claim("email", email)
        jwt.claim("email_verified", emailVerified)
        jwt.claim("aud", listOf("peak-api"))
    }

    private data class WebTenantFixture(
        val planId: UUID,
        val tenantId: UUID,
        val inviterUserId: UUID,
        val inviterRoleId: UUID,
        val invitedRoleId: UUID,
        val permissionId: UUID,
    )
}
