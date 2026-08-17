package com.mwombeki.peak.property

import com.jayway.jsonpath.JsonPath
import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
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
        "peak.communication.routing.sms=",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PropertyGoLiveSmsOperatorBlockerIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun smsGapIsAnOperatorBlockerAndDoesNotBlockHotelActivateEvidence() {
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

        mockMvc.perform(
            get("/api/v1/properties/$propertyId/onboarding")
                .secure(true)
                .headersFor(fixture, "corr-sms-operator"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.steps[?(@.key == 'sms_routable')].required", hasItem(false)))
            .andExpect(jsonPath("$.steps[?(@.key == 'sms_routable')].status", hasItem("blocked")))
            .andExpect(jsonPath("$.blockers[*].code", not(hasItem("sms_routable"))))
            .andExpect(jsonPath("$.operatorBlocker.code").value("sms_routable"))
            .andExpect(jsonPath("$.nextAction.step", not("sms_routable")))
    }

    private fun createProperty(fixture: Fixture): UUID {
        val response = mockMvc.perform(
            post("/api/v1/properties")
                .secureJson(
                    """
                    {
                      "name": "SMS Operator Hotel",
                      "code": "SMS-${fixture.tenantId.toString().take(8)}"
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-sms-create", "idem-sms-create-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andReturn()
        return UUID.fromString(JsonPath.read(response.response.contentAsString, "$.propertyId"))
    }

    private fun enableTenantModule(fixture: Fixture, moduleId: String) {
        mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/modules")
                .secureJson("""{"moduleId": "$moduleId"}""")
                .headersFor(
                    fixture,
                    "corr-tenant-module-$moduleId",
                    "idem-tenant-module-$moduleId-${fixture.tenantId}",
                ),
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
            "SmsOp Plan ${fixture.planId}",
            "smsop-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            fixture.tenantId,
            "SmsOp Tenant ${fixture.tenantId}",
            "smsop-${fixture.tenantId}",
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
            "SmsOp User ${fixture.tenantUserId}",
            "smsop-admin-${fixture.tenantId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.tenantRoleId,
            fixture.tenantId,
            "SmsOp Admin ${fixture.tenantRoleId}",
            "smsop-admin-${fixture.tenantRoleId}",
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
