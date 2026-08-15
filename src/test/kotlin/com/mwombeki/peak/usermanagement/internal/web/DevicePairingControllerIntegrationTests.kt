package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import org.hamcrest.Matchers.startsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
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
class DevicePairingControllerIntegrationTests {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun anUnauthenticatedTerminalCanRequestPairing() {
        val publicKey = Base64.getEncoder().encodeToString(
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded,
        )

        mockMvc.perform(
            post("/api/v1/devices/pairing-requests")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-pairing-request")
                .content("""{"publicKey":"$publicKey"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deviceCode").value(startsWith("dev_")))
            .andExpect(jsonPath("$.pairingCode").isString)
            .andExpect(jsonPath("$.fingerprint").isString)
    }

    @Test
    fun aManagerApprovesPairingThroughTheSecuredRoute() {
        val fixture = seedManager()
        val publicKey = Base64.getEncoder().encodeToString(
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded,
        )
        val requested = mockMvc.perform(
            post("/api/v1/devices/pairing-requests")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"publicKey":"$publicKey"}"""),
        )
            .andExpect(status().isOk)
            .andReturn()

        val pairingCode = com.jayway.jsonpath.JsonPath.read<String>(
            requested.response.contentAsString,
            "$.pairingCode",
        )

        mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/devices/pairing-approvals")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-pairing-approve")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.userId.toString())
                .content(
                    """
                    {
                      "pairingCode": "$pairingCode",
                      "propertyId": "${fixture.propertyId}",
                      "terminalName": "Till 1",
                      "mode": "POS"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deviceId").isString)
            .andExpect(jsonPath("$.deviceCode").value(startsWith("dev_")))
    }

    private fun seedManager(): Fixture {
        val fixture = Fixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            roleId = UUID.randomUUID(),
            permissionId = UUID.randomUUID(),
        )
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            fixture.planId, "Plan ${fixture.planId}", "plan-${fixture.planId}",
        )
        jdbcTemplate.update(
            "INSERT INTO tenants (id, name, slug, schema_name, plan_id) VALUES (?, ?, ?, ?, ?)",
            fixture.tenantId, "Tenant ${fixture.tenantId}", "tenant-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"), fixture.planId,
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
            VALUES (?, ?, 'Manager', ?, 'active', true)
            """.trimIndent(),
            fixture.userId, fixture.tenantId, "mgr-${fixture.userId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, code, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            fixture.propertyId, fixture.tenantId, "Property ${fixture.propertyId}",
            "P${fixture.propertyId.toString().take(8)}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO permissions (id, tenant_id, code, description)
            VALUES (?, ?, 'admin.devices.manage', 'Pair devices')
            """.trimIndent(),
            fixture.permissionId, fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.roleId, fixture.tenantId, "Device manager", "device-mgr-${fixture.roleId}",
        )
        jdbcTemplate.update(
            "INSERT INTO tenant_role_permissions (tenant_role_id, permission_id) VALUES (?, ?)",
            fixture.roleId, fixture.permissionId,
        )
        jdbcTemplate.update(
            "INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id) VALUES (?, ?, ?)",
            fixture.userId, fixture.tenantId, fixture.roleId,
        )
        return fixture
    }

    private data class Fixture(
        val planId: UUID,
        val tenantId: UUID,
        val propertyId: UUID,
        val userId: UUID,
        val roleId: UUID,
        val permissionId: UUID,
    )
}
