package com.mwombeki.peak.property

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
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
class PropertyGoLiveIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun requiresFrontlinePathWhenPosIsInScopeAndDoesNotDemandEmail() {
        val fixture = seedFixture()
        enableTenantModule(fixture, "property")
        val propertyId = createProperty(fixture)
        enableTenantModule(fixture, "pos")
        mockMvc.perform(
            post("/api/v1/properties/$propertyId/modules")
                .secureJson("""{"moduleId": "pos"}""")
                .headersFor(fixture, "corr-pos-enable", "idem-pos-enable-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))

        mockMvc.perform(
            get("/api/v1/properties/$propertyId/onboarding")
                .secure(true)
                .headersFor(fixture, "corr-frontline-blocked"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.steps[?(@.key == 'frontline_path')].status", hasItem("blocked")))
            .andExpect(jsonPath("$.steps[?(@.key == 'frontline_path')].required", hasItem(true)))
            .andExpect(jsonPath("$.steps[?(@.key == 'sms_routable')].required", hasItem(false)))
            .andExpect(jsonPath("$.steps[?(@.key == 'guest_rail_configured')].required", hasItem(false)))
            .andExpect(jsonPath("$.blockers[*].code", hasItem("frontline_path")))
            .andExpect(jsonPath("$.blockers[*].code", org.hamcrest.Matchers.not(hasItem("sms_routable"))))
            .andExpect(jsonPath("$.blockers[*].code", org.hamcrest.Matchers.not(hasItem("guest_rail_configured"))))
            .andExpect(jsonPath("$.blockers[*].code", org.hamcrest.Matchers.not(hasItem("whatsapp"))))
            .andExpect(jsonPath("$.nextAction.step").exists())
            .andExpect(jsonPath("$.nextAction.method").exists())
            .andExpect(jsonPath("$.nextAction.path").exists())

        hirePhoneFirstStaff(fixture, propertyId)

        mockMvc.perform(
            get("/api/v1/properties/$propertyId/onboarding")
                .secure(true)
                .headersFor(fixture, "corr-frontline-ready"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.steps[?(@.key == 'frontline_path')].status", hasItem("satisfied")))
            .andExpect(jsonPath("$.steps[?(@.key == 'sms_routable')].status", hasItem("satisfied")))
            .andExpect(jsonPath("$.blockers[*].code", org.hamcrest.Matchers.not(hasItem("frontline_path"))))
    }

    @Test
    fun seedsPersistedStepsWhenAPropertyIsCreated() {
        val fixture = seedFixture()
        enableTenantModule(fixture, "property")
        val propertyId = createProperty(fixture)

        val stepCount = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM property_onboarding_steps
            WHERE tenant_id = ? AND property_id = ?
            """.trimIndent(),
            Int::class.java,
            fixture.tenantId,
            propertyId,
        )
        kotlin.test.assertEquals(7, stepCount)
    }

    private fun hirePhoneFirstStaff(fixture: Fixture, propertyId: UUID) {
        val staffId = UUID.randomUUID()
        val roleId = UUID.randomUUID()
        val phone = "+2557" + "%08d".format(kotlin.math.abs(fixture.tenantId.hashCode()) % 100_000_000)
        jdbcTemplate.update(
            """
            INSERT INTO roles (id, tenant_id, name, is_system, is_active)
            VALUES (?, ?, 'Cashier', false, true)
            """.trimIndent(),
            roleId,
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (
                id, tenant_id, full_name, phone_number, staff_number, status, is_active
            )
            VALUES (?, ?, 'Frontline Cashier', ?, '0001', 'active', true)
            """.trimIndent(),
            staffId,
            fixture.tenantId,
            phone,
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            staffId,
            propertyId,
            roleId,
            fixture.tenantId,
        )
    }

    private fun createProperty(fixture: Fixture): UUID {
        val response = mockMvc.perform(
            post("/api/v1/properties")
                .secureJson(
                    """
                    {
                      "name": "Go Live Hotel",
                      "code": "GLH-${fixture.tenantId.toString().take(8)}"
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-golive-create", "idem-golive-create-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andReturn()
        return UUID.fromString(JsonPath.read(response.response.contentAsString, "$.propertyId"))
    }

    private fun enableTenantModule(fixture: Fixture, moduleId: String) {
        mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/modules")
                .secureJson("""{"moduleId": "$moduleId"}""")
                .headersFor(fixture, "corr-tenant-module-$moduleId", "idem-tenant-module-$moduleId-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
    }

    private fun seedFixture(): Fixture {
        val fixture = Fixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            tenantUserId = UUID.randomUUID(),
            tenantRoleId = UUID.randomUUID(),
        )
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            fixture.planId,
            "GoLive Plan ${fixture.planId}",
            "golive-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            fixture.tenantId,
            "GoLive Tenant ${fixture.tenantId}",
            "golive-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        listOf("property", "communications", "pos").forEach { moduleId ->
            jdbcTemplate.update(
                """
                INSERT INTO plan_entitlements (
                    plan_id, entitlement_code, entitlement_value, is_enabled
                ) VALUES (?, ?, jsonb_build_object('moduleId', ?), true)
                """.trimIndent(),
                fixture.planId,
                "module.$moduleId",
                moduleId,
            )
        }
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
            fixture.tenantUserId,
            fixture.tenantId,
            "GoLive User ${fixture.tenantUserId}",
            "golive-admin-${fixture.tenantId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.tenantRoleId,
            fixture.tenantId,
            "GoLive Admin ${fixture.tenantRoleId}",
            "golive-admin-${fixture.tenantRoleId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.tenantUserId,
            fixture.tenantId,
            fixture.tenantRoleId,
        )
        listOf(
            "module.manage",
            "property.manage",
            "property.view",
            "property.lifecycle",
        ).forEach { permissionCode ->
            val permissionId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO permissions (id, tenant_id, code, description)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                permissionId,
                fixture.tenantId,
                permissionCode,
                "Permission $permissionCode",
            )
            jdbcTemplate.update(
                """
                INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
                SELECT ?, ?
                FROM permission_catalog
                WHERE code = ?
                  AND is_tenant_permission = true
                  AND access_scope IN ('tenant', 'both')
                """.trimIndent(),
                fixture.tenantRoleId,
                permissionId,
                permissionCode,
            )
        }
        return fixture
    }

    private fun MockHttpServletRequestBuilder.secureJson(json: String): MockHttpServletRequestBuilder {
        return secure(true)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
    }

    private fun MockHttpServletRequestBuilder.headersFor(
        fixture: Fixture,
        correlationId: String,
        idempotencyKey: String? = null,
    ): MockHttpServletRequestBuilder {
        header(PeakRequestHeaders.CORRELATION_ID, correlationId)
        header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
        header(PeakRequestHeaders.TENANT_USER_ID, fixture.tenantUserId.toString())
        idempotencyKey?.let { header(PeakRequestHeaders.IDEMPOTENCY_KEY, it) }
        return this
    }

    private data class Fixture(
        val planId: UUID,
        val tenantId: UUID,
        val tenantUserId: UUID,
        val tenantRoleId: UUID,
    )
}
