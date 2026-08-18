package com.mwombeki.peak.usermanagement.internal.web

import com.jayway.jsonpath.JsonPath
import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import com.mwombeki.peak.shared.outbound.EstablishPassword
import com.mwombeki.peak.shared.outbound.IdentityProvisionerPort
import com.mwombeki.peak.shared.outbound.MarkEmailVerified
import com.mwombeki.peak.shared.outbound.ProvisionIdentity
import com.mwombeki.peak.shared.outbound.ProvisionedIdentity
import com.mwombeki.peak.shared.outbound.SendActivationLink
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.hamcrest.Matchers.nullValue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

@Import(
    TestcontainersConfiguration::class,
    AccountActivationControllerIntegrationTests.FakeIdentityConfiguration::class,
)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AccountActivationControllerIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var identities: RecordingIdentityProvisioner

    @Test
    fun unknownInvitationTokenReturnsInvitationNotFoundWithoutLeakingTheAddress() {
        mockMvc.perform(
            get("/api/v1/invitations/not-a-real-token")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-activation-missing"),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("invitation_not_found"))
    }

    @Test
    fun publicCallerLooksUpSendsVerifiesAndSetsAPasswordWithoutASessionCookie() {
        val fixture = tenantFixture()
        insertAuthorizedTenantFixture(fixture)
        val invitationToken = invite(fixture, "activate-${fixture.tenantId}@example.com", "Asha Mwakalinga")

        mockMvc.perform(
            get("/api/v1/invitations/$invitationToken")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-activation-lookup"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.inviteeName").value("Asha Mwakalinga"))
            .andExpect(jsonPath("$.maskedEmail").value("a****@example.com"))
            .andExpect(jsonPath("$.organisationName").value("Tenant ${fixture.tenantId}"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.allowedCredentials[0]").value("password"))

        val send = mockMvc.perform(
            post("/api/v1/invitations/$invitationToken/send-code")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-activation-send"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.maskedEmail").value("a****@example.com"))
            .andExpect(jsonPath("$.debugCode").isString)
            .andReturn()

        val code = JsonPath.read<String>(send.response.contentAsString, "$.debugCode")

        mockMvc.perform(
            post("/api/v1/invitations/$invitationToken/verify-code")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-activation-bad-code")
                .content("""{"code":"000000"}"""),
        )
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.code").value("code_incorrect"))

        val verified = mockMvc.perform(
            post("/api/v1/invitations/$invitationToken/verify-code")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-activation-verify")
                .content("""{"code":"$code"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.setupGrant").isString)
            .andExpect(jsonPath("$.expiresInSeconds").value(300))
            .andReturn()

        val setupGrant = JsonPath.read<String>(verified.response.contentAsString, "$.setupGrant")

        mockMvc.perform(
            post("/api/v1/invitations/$invitationToken/set-credential")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-activation-set")
                .content(
                    """
                    {
                      "setupGrant": "$setupGrant",
                      "password": "a-long-enough-secret"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.signedIn").value(false))
            .andExpect(jsonPath("$.redirectTo").value(nullValue()))

        assertEquals("a-long-enough-secret", identities.lastPassword.get())
        assertNotNull(identities.lastSubject.get())

        val status = jdbcTemplate.queryForObject(
            "SELECT status FROM tenant_user_invitations WHERE token_hash = ?",
            String::class.java,
            com.mwombeki.peak.usermanagement.internal.application.InvitationTokens.hash(invitationToken),
        )
        assertEquals("accepted", status)

        val links = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM identity_links
            WHERE issuer = 'http://localhost:8081/realms/peak-hospitality'
              AND subject = ?
            """.trimIndent(),
            Long::class.java,
            identities.lastSubject.get(),
        )
        assertEquals(1L, links)
    }

    @Test
    fun recoveryStartDoesNotDiscloseWhetherTheAddressExists() {
        mockMvc.perform(
            post("/api/v1/auth/recovery/start")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-recovery-unknown")
                .content("""{"email":"nobody-${UUID.randomUUID()}@example.com"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.maskedEmail").isString)
            .andExpect(jsonPath("$.resendAvailableInSeconds").value(60))
            .andExpect(jsonPath("$.expiresInSeconds").value(600))
    }

    @Test
    fun platformRecoveryEnrolmentIsRefusedUntilAnAuthenticatorExists() {
        mockMvc.perform(
            post("/api/v1/invitations/any-token/confirm-recovery-code")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-recovery-enrol")
                .content("""{"setupGrant":"not-a-grant","code":"123456"}"""),
        )
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.code").value("unknown"))
    }

    private fun invite(fixture: WebTenantFixture, email: String, fullName: String): String {
        val result = mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/users/invitations")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-activation-invite")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-activation-invite-${fixture.tenantId}")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.inviterUserId.toString())
                .content(
                    """
                    {
                      "email": "$email",
                      "fullName": "$fullName",
                      "tenantRoleId": "${fixture.invitedRoleId}"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andReturn()
        return JsonPath.read(result.response.contentAsString, "$.invitationToken")
    }

    private fun tenantFixture() = WebTenantFixture(
        planId = UUID.randomUUID(),
        tenantId = UUID.randomUUID(),
        inviterUserId = UUID.randomUUID(),
        inviterRoleId = UUID.randomUUID(),
        invitedRoleId = UUID.randomUUID(),
        permissionId = UUID.randomUUID(),
    )

    private fun insertAuthorizedTenantFixture(fixture: WebTenantFixture) {
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            fixture.planId,
            "Plan ${fixture.planId}",
            "plan-${fixture.planId}",
        )
        jdbcTemplate.update(
            "INSERT INTO tenants (id, name, slug, schema_name, plan_id) VALUES (?, ?, ?, ?, ?)",
            fixture.tenantId,
            "Tenant ${fixture.tenantId}",
            "tenant-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        jdbcTemplate.update(
            "INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured) VALUES (?, 'tenant_admin', true, true)",
            fixture.tenantId,
        )
        jdbcTemplate.update(
            "INSERT INTO users (id, tenant_id, full_name, email, status) VALUES (?, ?, ?, ?, 'active')",
            fixture.inviterUserId,
            fixture.tenantId,
            "Inviter ${fixture.inviterUserId}",
            "inviter-${fixture.inviterUserId}@example.com",
        )
        jdbcTemplate.update(
            "INSERT INTO tenant_roles (id, tenant_id, name, code) VALUES (?, ?, ?, ?)",
            fixture.inviterRoleId,
            fixture.tenantId,
            "Inviter Role ${fixture.inviterRoleId}",
            "inviter-${fixture.inviterRoleId}",
        )
        jdbcTemplate.update(
            "INSERT INTO tenant_roles (id, tenant_id, name, code) VALUES (?, ?, ?, ?)",
            fixture.invitedRoleId,
            fixture.tenantId,
            "Invited Role ${fixture.invitedRoleId}",
            "invited-${fixture.invitedRoleId}",
        )
        jdbcTemplate.update(
            "INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id) VALUES (?, ?, ?)",
            fixture.inviterUserId,
            fixture.tenantId,
            fixture.inviterRoleId,
        )
        val permissionId = fixture.permissionId
        jdbcTemplate.update(
            "INSERT INTO permissions (id, tenant_id, code, description) VALUES (?, ?, 'tenant.users.manage', 'Manage tenant users')",
            permissionId,
            fixture.tenantId,
        )
        jdbcTemplate.update(
            "INSERT INTO tenant_role_permissions (tenant_role_id, permission_id) VALUES (?, ?)",
            fixture.inviterRoleId,
            permissionId,
        )
    }

    @TestConfiguration
    class FakeIdentityConfiguration {
        @Bean
        fun identityProvisioner(): RecordingIdentityProvisioner = RecordingIdentityProvisioner()
    }

    class RecordingIdentityProvisioner : IdentityProvisionerPort {
        val lastPassword = AtomicReference<String?>(null)
        val lastSubject = AtomicReference<String?>(null)

        override fun isHealthy(): Boolean = true

        override fun provision(command: ProvisionIdentity): ProvisionedIdentity {
            val subject = UUID.randomUUID().toString()
            lastSubject.set(subject)
            return ProvisionedIdentity(subjectId = subject, alreadyExisted = false)
        }

        override fun sendActivationLink(command: SendActivationLink) = Unit

        override fun establishPassword(command: EstablishPassword) {
            lastPassword.set(command.password)
        }

        override fun markEmailVerified(command: MarkEmailVerified) = Unit

        override fun clearRequiredActions(subjectId: String, realm: String?) = Unit

        override fun disable(subjectId: String) = Unit

        override fun delete(subjectId: String, realm: String?) = Unit
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
