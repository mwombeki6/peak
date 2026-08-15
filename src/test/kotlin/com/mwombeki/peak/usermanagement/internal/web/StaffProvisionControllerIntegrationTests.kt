package com.mwombeki.peak.usermanagement.internal.web

import com.jayway.jsonpath.JsonPath
import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import com.mwombeki.peak.usermanagement.internal.application.StaffCredentialService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.hamcrest.Matchers.containsString
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
class StaffProvisionControllerIntegrationTests {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var credentials: StaffCredentialService

    @Test
    fun provisionsStaffWithoutEmailAndDeliversActivationOverSms() {
        val fixture = seedManager()
        val phone = "+2557" + "%08d".format(kotlin.math.abs(fixture.tenantId.hashCode()) % 100_000_000)

        val result = mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/staff")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-staff-provision")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-staff-provision-${fixture.tenantId}")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.managerId.toString())
                .content(
                    """
                    {
                      "fullName": "Amina Hassan",
                      "phoneNumber": "$phone",
                      "propertyId": "${fixture.propertyId}",
                      "propertyRoleId": "${fixture.operationalRoleId}"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.staffNumber").value("0001"))
            .andExpect(jsonPath("$.phoneNumber").value(phone))
            .andExpect(jsonPath("$.activationSecret").isString)
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val userId = UUID.fromString(JsonPath.read(result.response.contentAsString, "$.userId"))
        val staffNumber = JsonPath.read<String>(result.response.contentAsString, "$.staffNumber")
        val secret = JsonPath.read<String>(result.response.contentAsString, "$.activationSecret")

        val email = jdbcTemplate.queryForObject(
            "SELECT email FROM users WHERE id = ?",
            String::class.java,
            userId,
        )
        assertNull(email, "provision must not invent an email address")

        val outbox = jdbcTemplate.queryForMap(
            """
            SELECT destination, event_type, payload::text AS payload
            FROM outbox_events
            WHERE aggregate_id = ? AND event_type = 'staff.credential.activation.issued'
            """.trimIndent(),
            userId,
        )
        assertEquals("notification", outbox["destination"])
        val payload = outbox["payload"].toString()
        assertTrue(payload.contains(phone))
        assertTrue(payload.contains(staffNumber))
        assertTrue(!payload.contains(secret), "activation secret must not sit in the outbox in plaintext")

        mockMvc.perform(
            post("/api/v1/staff/credentials/activate")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-staff-activate")
                .content(
                    """
                    {
                      "tenantId": "${fixture.tenantId}",
                      "staffNumber": "$staffNumber",
                      "secret": "$secret",
                      "pin": "418205"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)

        assertEquals(userId, credentials.verify(fixture.tenantId, staffNumber, "418205"))
        assertNull(
            jdbcTemplate.queryForObject(
                "SELECT pin_hash FROM staff_credentials WHERE user_id = ?",
                String::class.java,
                userId,
            )?.let { hash -> hash.takeIf { it.contains("418205") } },
        )
    }

    @Test
    fun provisionsStaffWithNoPhoneAndHandsTheSecretToTheManager() {
        val fixture = seedManager()

        mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/staff")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-staff-in-person")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-staff-in-person-${fixture.tenantId}")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.managerId.toString())
                .content(
                    """
                    {
                      "fullName": "Juma Ali",
                      "propertyId": "${fixture.propertyId}",
                      "propertyRoleId": "${fixture.operationalRoleId}"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.activationSecret").isString)

        assertNull(
            jdbcTemplate.queryForObject(
                "SELECT phone_number FROM users WHERE tenant_id = ? AND staff_number = '0001'",
                String::class.java,
                fixture.tenantId,
            ),
            "in-person hire has no phone to SMS",
        )

        val smsCount = jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM outbox_events
            WHERE tenant_id = ? AND event_type = 'staff.credential.activation.issued'
            """.trimIndent(),
            Int::class.java,
            fixture.tenantId,
        )
        assertEquals(0, smsCount)
    }

    @Test
    fun refusesAStrongPermissionOnAnOperationalHire() {
        val fixture = seedManager()

        mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/staff")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-staff-strong")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-staff-strong-${fixture.tenantId}")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.managerId.toString())
                .content(
                    """
                    {
                      "fullName": "Should Fail",
                      "propertyId": "${fixture.propertyId}",
                      "propertyRoleId": "${fixture.strongRoleId}"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail", containsString("strong session")))
    }

    @Test
    fun refusesAPropertyFromAnotherTenant() {
        val fixture = seedManager()
        val other = seedManager()

        mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/staff")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-staff-cross")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-staff-cross-${fixture.tenantId}")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.managerId.toString())
                .content(
                    """
                    {
                      "fullName": "Cross Tenant",
                      "propertyId": "${other.propertyId}",
                      "propertyRoleId": "${fixture.operationalRoleId}"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail", containsString("Property was not found")))
    }

    @Test
    fun activationFailuresAreIndistinguishable() {
        val fixture = seedManager()

        mockMvc.perform(
            post("/api/v1/staff/credentials/activate")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-staff-activate-miss")
                .content(
                    """
                    {
                      "tenantId": "${fixture.tenantId}",
                      "staffNumber": "9999",
                      "secret": "000000000",
                      "pin": "418205"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("Activation was not accepted"))
    }

    @Test
    fun deniesProvisionWithoutPermission() {
        val fixture = seedManager(grantManageUsers = false)

        mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/staff")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-staff-denied")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-staff-denied-${fixture.tenantId}")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.managerId.toString())
                .content(
                    """
                    {
                      "fullName": "Denied",
                      "propertyId": "${fixture.propertyId}",
                      "propertyRoleId": "${fixture.operationalRoleId}"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isForbidden)
    }

    private fun seedManager(grantManageUsers: Boolean = true): Fixture {
        val fixture = Fixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            managerId = UUID.randomUUID(),
            managerRoleId = UUID.randomUUID(),
            managePermissionId = UUID.randomUUID(),
            operationalRoleId = UUID.randomUUID(),
            operationalPermissionId = UUID.randomUUID(),
            strongRoleId = UUID.randomUUID(),
            strongPermissionId = UUID.randomUUID(),
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
            fixture.managerId, fixture.tenantId, "mgr-${fixture.managerId}@example.com",
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
            fixture.managerRoleId, fixture.tenantId, "Manager", "mgr-${fixture.managerRoleId}",
        )
        if (grantManageUsers) {
            jdbcTemplate.update(
                """
                INSERT INTO permissions (id, tenant_id, code, description)
                VALUES (?, ?, 'tenant.users.manage', 'Manage tenant users')
                """.trimIndent(),
                fixture.managePermissionId, fixture.tenantId,
            )
            jdbcTemplate.update(
                "INSERT INTO tenant_role_permissions (tenant_role_id, permission_id) VALUES (?, ?)",
                fixture.managerRoleId, fixture.managePermissionId,
            )
        }
        jdbcTemplate.update(
            "INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id) VALUES (?, ?, ?)",
            fixture.managerId, fixture.tenantId, fixture.managerRoleId,
        )
        jdbcTemplate.update(
            "INSERT INTO roles (id, tenant_id, name, is_system, is_active) VALUES (?, ?, 'Waiter', false, true)",
            fixture.operationalRoleId, fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO permissions (id, tenant_id, code, description)
            VALUES (?, ?, 'pos.order.manage', 'Take orders')
            """.trimIndent(),
            fixture.operationalPermissionId, fixture.tenantId,
        )
        jdbcTemplate.update(
            "INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
            fixture.operationalRoleId, fixture.operationalPermissionId,
        )
        jdbcTemplate.update(
            "INSERT INTO roles (id, tenant_id, name, is_system, is_active) VALUES (?, ?, 'Too strong', false, true)",
            fixture.strongRoleId, fixture.tenantId,
        )
        val strongPermissionId = if (grantManageUsers) {
            fixture.managePermissionId
        } else {
            jdbcTemplate.update(
                """
                INSERT INTO permissions (id, tenant_id, code, description)
                VALUES (?, ?, 'tenant.users.manage', 'Strong on a property role')
                """.trimIndent(),
                fixture.strongPermissionId, fixture.tenantId,
            )
            fixture.strongPermissionId
        }
        jdbcTemplate.update(
            "INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
            fixture.strongRoleId, strongPermissionId,
        )
        return fixture
    }

    private data class Fixture(
        val planId: UUID,
        val tenantId: UUID,
        val propertyId: UUID,
        val managerId: UUID,
        val managerRoleId: UUID,
        val managePermissionId: UUID,
        val operationalRoleId: UUID,
        val operationalPermissionId: UUID,
        val strongRoleId: UUID,
        val strongPermissionId: UUID,
    )
}
