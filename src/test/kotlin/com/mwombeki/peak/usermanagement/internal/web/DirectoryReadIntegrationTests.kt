package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import org.hamcrest.Matchers.hasSize
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The two reads that complete the onboarding wizard.
 *
 * The cases that matter here are the refusals. A directory endpoint that returns the right rows
 * to the right manager and also returns them to the wrong one is worse than no endpoint, because
 * the wizard makes it look sanctioned.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DirectoryReadIntegrationTests {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun aManagerSeesTheirOwnTenantsPairedDevices() {
        val fixture = seedTenant(viewPermissions = listOf("admin.devices.view"))
        seedPairedDevice(fixture, "Reception 1")
        seedPairedDevice(fixture, "Bar 1")

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/devices")
                .secure(true)
                .headers(identityOf(fixture)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].terminalName").isString)
            .andExpect(jsonPath("$[0].keyFingerprint").isString)
    }

    @Test
    fun aRevokedDeviceStaysVisibleWithItsRevocationTime() {
        val fixture = seedTenant(viewPermissions = listOf("admin.devices.view"))
        val deviceId = seedPairedDevice(fixture, "Retired till")
        jdbcTemplate.update(
            "UPDATE paired_devices SET status = 'revoked', revoked_at = now() WHERE id = ?",
            deviceId,
        )

        // A till that disappears the moment it is revoked leaves a manager unable to confirm the
        // revocation happened at all.
        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/devices")
                .secure(true)
                .headers(identityOf(fixture)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].status").value("revoked"))
            .andExpect(jsonPath("$[0].revokedAt").isString)
    }

    @Test
    fun devicesAreNarrowedToOnePropertyWhenAsked() {
        val fixture = seedTenant(viewPermissions = listOf("admin.devices.view"))
        seedPairedDevice(fixture, "Main house")
        val otherProperty = seedProperty(fixture)
        seedPairedDevice(fixture, "Annexe", propertyId = otherProperty)

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/devices?propertyId=$otherProperty")
                .secure(true)
                .headers(identityOf(fixture)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].terminalName").value("Annexe"))
    }

    @Test
    fun aManagerCannotReadAnotherTenantsDevices() {
        val owner = seedTenant(viewPermissions = listOf("admin.devices.view"))
        val intruder = seedTenant(viewPermissions = listOf("admin.devices.view"))
        seedPairedDevice(owner, "Reception 1")

        // The intruder holds the permission — in their own tenant. Naming someone else's tenant
        // in the path must not carry it across, and this is the failure a WHERE clause alone
        // would not catch, since the clause would happily filter to the tenant it was handed.
        mockMvc.perform(
            get("/api/v1/tenants/${owner.tenantId}/devices")
                .secure(true)
                .headers(identityOf(intruder)),
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun aManagerWithoutTheViewPermissionIsRefusedTheDeviceList() {
        val fixture = seedTenant(viewPermissions = emptyList())
        seedPairedDevice(fixture, "Reception 1")

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/devices")
                .secure(true)
                .headers(identityOf(fixture)),
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun aManagerSeesTheirOwnTenantsStaffDirectory() {
        val fixture = seedTenant(viewPermissions = listOf("tenant.users.view"))

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/staff")
                .secure(true)
                .headers(identityOf(fixture)),
        )
            .andExpect(status().isOk)
            // The seeded manager is themselves a user, and the wizard's manager step exists to
            // resolve exactly this id.
            .andExpect(jsonPath("$[?(@.userId=='${fixture.userId}')]", hasSize<Any>(1)))
    }

    @Test
    fun aManagerCannotReadAnotherTenantsStaffDirectory() {
        val owner = seedTenant(viewPermissions = listOf("tenant.users.view"))
        val intruder = seedTenant(viewPermissions = listOf("tenant.users.view"))

        mockMvc.perform(
            get("/api/v1/tenants/${owner.tenantId}/staff")
                .secure(true)
                .headers(identityOf(intruder)),
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun aManagerWithoutTheViewPermissionIsRefusedTheStaffDirectory() {
        val fixture = seedTenant(viewPermissions = emptyList())

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/staff")
                .secure(true)
                .headers(identityOf(fixture)),
        )
            .andExpect(status().isForbidden)
    }

    private fun identityOf(fixture: Fixture) =
        HttpHeaders().apply {
            add(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
            add(PeakRequestHeaders.TENANT_USER_ID, fixture.userId.toString())
            add(PeakRequestHeaders.CORRELATION_ID, "corr-${UUID.randomUUID()}")
        }

    private fun seedProperty(fixture: Fixture): UUID {
        val propertyId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, code, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            propertyId, fixture.tenantId, "Property $propertyId",
            "P${propertyId.toString().take(8)}",
        )
        return propertyId
    }

    private fun seedPairedDevice(
        fixture: Fixture,
        terminalName: String,
        propertyId: UUID = fixture.propertyId,
    ): UUID {
        val deviceId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO paired_devices (
                id, tenant_id, property_id, device_code, public_key, key_fingerprint,
                terminal_name, mode, status, paired_by
            ) VALUES (?, ?, ?, ?, 'test-public-key', ?, ?, 'POS', 'active', ?)
            """.trimIndent(),
            deviceId, fixture.tenantId, propertyId,
            "dev_${deviceId.toString().take(8)}",
            "fp_${deviceId.toString().take(8)}",
            terminalName, fixture.userId,
        )
        return deviceId
    }

    private fun seedTenant(viewPermissions: List<String>): Fixture {
        val fixture = Fixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            roleId = UUID.randomUUID(),
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
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.roleId, fixture.tenantId, "Directory reader", "dir-${fixture.roleId}",
        )
        jdbcTemplate.update(
            "INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id) VALUES (?, ?, ?)",
            fixture.userId, fixture.tenantId, fixture.roleId,
        )
        viewPermissions.forEach { code ->
            val permissionId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO permissions (id, tenant_id, code, description)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                permissionId, fixture.tenantId, code, "Read $code",
            )
            jdbcTemplate.update(
                "INSERT INTO tenant_role_permissions (tenant_role_id, permission_id) VALUES (?, ?)",
                fixture.roleId, permissionId,
            )
        }
        return fixture
    }

    private data class Fixture(
        val planId: UUID,
        val tenantId: UUID,
        val propertyId: UUID,
        val userId: UUID,
        val roleId: UUID,
    )
}
