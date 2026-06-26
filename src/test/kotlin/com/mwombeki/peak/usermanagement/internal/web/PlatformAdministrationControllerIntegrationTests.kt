package com.mwombeki.peak.usermanagement.internal.web

import com.jayway.jsonpath.JsonPath
import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import org.hamcrest.Matchers.hasItem
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
class PlatformAdministrationControllerIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun managesPlatformUsersRolesAndIdentityLinksThroughSecuredRoutes() {
        val actorId = insertPlatformSecurityActor()

        val createUserResponse = mockMvc.perform(
            post("/api/v1/platform/users")
                .platform(actorId, "corr-platform-user-create", "idem-platform-user-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "fullName": "Security Operator",
                      "email": "security.operator@example.com",
                      "status": "active"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val platformUserId = UUID.fromString(
            JsonPath.read(createUserResponse.response.contentAsString, "$.platformUserId"),
        )

        mockMvc.perform(
            post("/api/v1/platform/users")
                .platform(actorId, "corr-platform-user-create", "idem-platform-user-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "fullName": "Security Operator",
                      "email": "security.operator@example.com",
                      "status": "active"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.platformUserId").value(platformUserId.toString()))
            .andExpect(jsonPath("$.replayed").value(true))

        mockMvc.perform(
            get("/api/v1/platform/users")
                .platform(actorId, "corr-platform-users-list"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[*].email", hasItem("security.operator@example.com")))

        val roleResponse = mockMvc.perform(
            post("/api/v1/platform/roles")
                .platform(actorId, "corr-platform-role-create", "idem-platform-role-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "code": "incident_manager",
                      "name": "Incident Manager",
                      "description": "Can view audit and monitoring data",
                      "permissionCodes": ["platform.audit.view", "platform.monitoring.view"]
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.changed").value(true))
            .andReturn()

        val platformRoleId = UUID.fromString(
            JsonPath.read(roleResponse.response.contentAsString, "$.platformRoleId"),
        )

        mockMvc.perform(
            post("/api/v1/platform/users/$platformUserId/roles/$platformRoleId/assign")
                .platform(actorId, "corr-platform-role-assign", "idem-platform-role-assign"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.assigned").value(true))
            .andExpect(jsonPath("$.changed").value(true))

        val identityResponse = mockMvc.perform(
            post("/api/v1/platform/users/$platformUserId/identity-links")
                .platform(actorId, "corr-platform-identity-link", "idem-platform-identity-link")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "issuer": "https://keycloak.example.com/realms/peak",
                      "subject": "operator-subject",
                      "email": "security.operator@example.com"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.changed").value(true))
            .andReturn()

        val identityLinkId = UUID.fromString(
            JsonPath.read(identityResponse.response.contentAsString, "$.identityLinkId"),
        )

        mockMvc.perform(
            post("/api/v1/platform/users/$platformUserId/identity-links/$identityLinkId/revoke")
                .platform(actorId, "corr-platform-identity-revoke", "idem-platform-identity-revoke"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.revokedAt").exists())

        mockMvc.perform(
            post("/api/v1/platform/users/$platformUserId/disable")
                .platform(actorId, "corr-platform-user-disable", "idem-platform-user-disable"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("disabled"))
            .andExpect(jsonPath("$.changed").value(true))

        assertPlatformAuditAndOutboxWereRecorded(platformUserId)
    }

    @Test
    fun deniesPlatformAdministrationRouteWithoutPlatformSecurityPermission() {
        val platformUserId = insertPlatformUser()

        mockMvc.perform(
            get("/api/v1/platform/users")
                .platform(platformUserId, "corr-platform-denied"),
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun rejectsSelfLifecycleChange() {
        val actorId = insertPlatformSecurityActor()

        mockMvc.perform(
            post("/api/v1/platform/users/$actorId/disable")
                .platform(actorId, "corr-platform-self-disable", "idem-platform-self-disable"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("Platform operator cannot change own lifecycle state"))
    }

    private fun insertPlatformSecurityActor(): UUID {
        val platformUserId = insertPlatformUser()
        val roleId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO platform_roles (id, code, name, is_system, is_active)
            VALUES (?, ?, ?, false, true)
            """.trimIndent(),
            roleId,
            "security-admin-${roleId.toString().take(8)}",
            "Security Admin",
        )
        val permissionId = jdbcTemplate.queryForObject(
            "SELECT id FROM platform_permissions WHERE code = 'platform.security.manage'",
            UUID::class.java,
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
            VALUES (?, ?)
            """.trimIndent(),
            roleId,
            permissionId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_user_roles (platform_user_id, platform_role_id, assigned_by)
            VALUES (?, ?, ?)
            """.trimIndent(),
            platformUserId,
            roleId,
            platformUserId,
        )
        return platformUserId
    }

    private fun insertPlatformUser(): UUID {
        val platformUserId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, full_name, email, status)
            VALUES (?, ?, ?, 'active')
            """.trimIndent(),
            platformUserId,
            "Platform User",
            "platform-${platformUserId.toString().take(8)}@example.com",
        )
        return platformUserId
    }

    private fun assertPlatformAuditAndOutboxWereRecorded(platformUserId: UUID) {
        val auditCount = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM platform_audit_logs
            WHERE entity_id = ?
              AND action LIKE 'platform.users.%'
            """.trimIndent(),
            Int::class.java,
            platformUserId,
        )
        val outboxCount = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM outbox_events
            WHERE aggregate_id = ?
              AND event_type LIKE 'platform.users.%'
            """.trimIndent(),
            Int::class.java,
            platformUserId,
        )
        check((auditCount ?: 0) > 0) {
            "Expected platform audit events for platform user changes"
        }
        check((outboxCount ?: 0) > 0) {
            "Expected platform outbox events for platform user changes"
        }
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.platform(
        platformUserId: UUID,
        correlationId: String,
        idempotencyKey: String? = null,
    ): org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder {
        header(PeakRequestHeaders.PLATFORM_USER_ID, platformUserId.toString())
        header(PeakRequestHeaders.CORRELATION_ID, correlationId)
        idempotencyKey?.let { header(PeakRequestHeaders.IDEMPOTENCY_KEY, it) }
        return this
    }
}
