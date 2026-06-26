package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class TenantUserRoleManagementControllerIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun listsTenantRolesThroughSecuredRoute() {
        val fixture = roleManagementFixture()
        insertAuthorizedFixture(fixture)

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/roles")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-roles-list")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[*].tenantRoleId", hasItem(fixture.targetRoleId.toString())))
            .andExpect(jsonPath("$[*].code", hasItem(fixture.targetRoleCode)))
            .andExpect(content().string(containsString("reports.view")))
    }

    @Test
    fun assignsTenantUserRoleThroughSecuredRoute() {
        val fixture = roleManagementFixture()
        insertAuthorizedFixture(fixture)

        mockMvc.perform(
            post(
                "/api/v1/tenants/${fixture.tenantId}/users/" +
                        "${fixture.targetUserId}/roles/${fixture.targetRoleId}/assign",
            )
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-role-assign")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-role-assign")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tenantId").value(fixture.tenantId.toString()))
            .andExpect(jsonPath("$.userId").value(fixture.targetUserId.toString()))
            .andExpect(jsonPath("$.tenantRoleId").value(fixture.targetRoleId.toString()))
            .andExpect(jsonPath("$.assigned").value(true))
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.replayed").value(false))

        assertEquals(1, roleAssignmentCount(fixture))
    }

    @Test
    fun createsUpdatesAndDeactivatesDynamicTenantRoleThroughSecuredRoutes() {
        val fixture = roleManagementFixture()
        insertAuthorizedFixture(fixture)
        val roleCode = "dynamic-${UUID.randomUUID()}"

        val createResult = mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/roles")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "code": "$roleCode",
                      "name": "Front Office Supervisor",
                      "description": "Can supervise front office workflows",
                      "permissionCodes": ["reports.view"]
                    }
                    """.trimIndent(),
                )
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-role-create")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-role-create")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tenantId").value(fixture.tenantId.toString()))
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val tenantRoleId = objectMapper.readValue(
            createResult.response.contentAsString,
            TenantRoleMutationHttpResponse::class.java,
        ).tenantRoleId

        mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/roles")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "code": "$roleCode",
                      "name": "Front Office Supervisor",
                      "description": "Can supervise front office workflows",
                      "permissionCodes": ["reports.view"]
                    }
                    """.trimIndent(),
                )
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-role-create-replay")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-role-create")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tenantRoleId").value(tenantRoleId.toString()))
            .andExpect(jsonPath("$.replayed").value(true))

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/roles/$tenantRoleId")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-role-get")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tenantRoleId").value(tenantRoleId.toString()))
            .andExpect(jsonPath("$.code").value(roleCode))
            .andExpect(jsonPath("$.permissionCodes", hasItem("reports.view")))

        mockMvc.perform(
            put("/api/v1/tenants/${fixture.tenantId}/roles/$tenantRoleId")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Front Office Lead",
                      "permissionCodes": ["tenant.users.manage"]
                    }
                    """.trimIndent(),
                )
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-role-update")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-role-update")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tenantRoleId").value(tenantRoleId.toString()))
            .andExpect(jsonPath("$.changed").value(true))

        mockMvc.perform(
            delete("/api/v1/tenants/${fixture.tenantId}/roles/$tenantRoleId")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-role-delete")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-role-delete")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tenantRoleId").value(tenantRoleId.toString()))
            .andExpect(jsonPath("$.isActive").value(false))
            .andExpect(jsonPath("$.changed").value(true))

        assertEquals(1, auditCount(fixture.tenantId, "tenant.roles.created", tenantRoleId))
        assertEquals(1, outboxCount(fixture.tenantId, "tenant.role.created", tenantRoleId))
    }

    @Test
    fun rejectsSystemTenantRoleModificationThroughSecuredRoutes() {
        val fixture = roleManagementFixture()
        insertAuthorizedFixture(fixture)
        jdbcTemplate.update(
            "UPDATE tenant_roles SET is_system = true WHERE id = ?",
            fixture.targetRoleId,
        )

        mockMvc.perform(
            put("/api/v1/tenants/${fixture.tenantId}/roles/${fixture.targetRoleId}")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name": "Escalated System Role"}""")
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-role-system")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-role-system")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("System tenant roles cannot be modified")))
    }

    @Test
    fun deniesTenantRoleRouteWithoutPermission() {
        val fixture = roleManagementFixture()
        insertFixtureWithoutPermission(fixture)

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/roles")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-roles-denied")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(containsString("Tenant user lacks required module permission")))
    }

    private fun roleManagementFixture(): RoleManagementFixture {
        val actorRoleId = UUID.randomUUID()
        val targetRoleId = UUID.randomUUID()
        return RoleManagementFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            actorUserId = UUID.randomUUID(),
            targetUserId = UUID.randomUUID(),
            actorRoleId = actorRoleId,
            targetRoleId = targetRoleId,
            managePermissionId = UUID.randomUUID(),
            reportsPermissionId = UUID.randomUUID(),
            actorRoleCode = "actor-$actorRoleId",
            targetRoleCode = "assignable-$targetRoleId",
            actorRoleName = "Actor Role $actorRoleId",
            targetRoleName = "Assignable Role $targetRoleId",
        )
    }

    private fun insertAuthorizedFixture(fixture: RoleManagementFixture) {
        insertFixtureWithoutPermission(fixture)
        jdbcTemplate.update(
            """
            INSERT INTO permissions (id, tenant_id, code, description)
            VALUES (?, ?, 'tenant.users.manage', 'Manage tenant users')
            """.trimIndent(),
            fixture.managePermissionId,
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
            VALUES (?, ?)
            """.trimIndent(),
            fixture.actorRoleId,
            fixture.managePermissionId,
        )
    }

    private fun insertFixtureWithoutPermission(fixture: RoleManagementFixture) {
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
        insertTenantUser(
            tenantId = fixture.tenantId,
            userId = fixture.actorUserId,
            email = "actor-${fixture.actorUserId}@example.com",
        )
        insertTenantUser(
            tenantId = fixture.tenantId,
            userId = fixture.targetUserId,
            email = "target-${fixture.targetUserId}@example.com",
        )
        insertTenantRole(
            tenantId = fixture.tenantId,
            roleId = fixture.actorRoleId,
            name = fixture.actorRoleName,
            code = fixture.actorRoleCode,
        )
        insertTenantRole(
            tenantId = fixture.tenantId,
            roleId = fixture.targetRoleId,
            name = fixture.targetRoleName,
            code = fixture.targetRoleCode,
        )
        jdbcTemplate.update(
            """
            INSERT INTO permissions (id, tenant_id, code, description)
            VALUES (?, ?, 'reports.view', 'View reports')
            """.trimIndent(),
            fixture.reportsPermissionId,
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
            VALUES (?, ?)
            """.trimIndent(),
            fixture.targetRoleId,
            fixture.reportsPermissionId,
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
    }

    private fun insertTenantUser(
        tenantId: UUID,
        userId: UUID,
        email: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            userId,
            tenantId,
            "User $userId",
            email,
        )
    }

    private fun insertTenantRole(
        tenantId: UUID,
        roleId: UUID,
        name: String,
        code: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            roleId,
            tenantId,
            name,
            code,
        )
    }

    private fun roleAssignmentCount(fixture: RoleManagementFixture): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM user_tenant_roles
                WHERE tenant_id = ?
                  AND user_id = ?
                  AND tenant_role_id = ?
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
                fixture.targetUserId,
                fixture.targetRoleId,
            ),
        )
    }

    private fun auditCount(
        tenantId: UUID,
        action: String,
        entityId: UUID,
    ): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action = ?
                  AND entity_id = ?
                """.trimIndent(),
                Int::class.java,
                tenantId,
                action,
                entityId,
            ),
        )
    }

    private fun outboxCount(
        tenantId: UUID,
        eventType: String,
        aggregateId: UUID,
    ): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM outbox_events
                WHERE tenant_id = ?
                  AND event_type = ?
                  AND aggregate_id = ?
                """.trimIndent(),
                Int::class.java,
                tenantId,
                eventType,
                aggregateId,
            ),
        )
    }

    private data class RoleManagementFixture(
        val planId: UUID,
        val tenantId: UUID,
        val actorUserId: UUID,
        val targetUserId: UUID,
        val actorRoleId: UUID,
        val targetRoleId: UUID,
        val managePermissionId: UUID,
        val reportsPermissionId: UUID,
        val actorRoleCode: String,
        val targetRoleCode: String,
        val actorRoleName: String,
        val targetRoleName: String,
    )
}
