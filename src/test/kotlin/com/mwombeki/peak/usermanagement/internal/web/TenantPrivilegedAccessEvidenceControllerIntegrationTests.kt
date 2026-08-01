package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The tenant transparency endpoint is the one place a tenant reads records that
 * live in platform-owned tables, so its scoping is worth proving over HTTP and
 * not only in SQL.
 *
 * Two independent controls should stop a cross-tenant read: the route guard
 * rejects a path tenant that does not match the caller's identity, and the
 * evidence view filters on the bound database session rather than on the path
 * parameter. These assert the observable outcome rather than which control
 * fired, so removing either one alone still fails a test.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class TenantPrivilegedAccessEvidenceControllerIntegrationTests @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `a tenant reads only its own privileged access history`() {
        val mine = tenantWithPrivilegedAccess()
        val theirs = tenantWithPrivilegedAccess()

        mockMvc.perform(
            get("/api/v1/tenants/${mine.tenantId}/privileged-access")
                .header(PeakRequestHeaders.TENANT_ID, mine.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, mine.userId.toString())
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-evidence-own"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[*].supportTicketId").value(
                org.hamcrest.Matchers.everyItem(
                    org.hamcrest.Matchers.not(theirs.ticketId.toString()),
                ),
            ))
    }

    /**
     * The path parameter is not the authority. A caller naming another tenant is
     * refused rather than served that tenant's history.
     */
    @Test
    fun `a tenant cannot read another tenant privileged access history`() {
        val mine = tenantWithPrivilegedAccess()
        val theirs = tenantWithPrivilegedAccess()

        mockMvc.perform(
            get("/api/v1/tenants/${theirs.tenantId}/privileged-access")
                .header(PeakRequestHeaders.TENANT_ID, mine.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, mine.userId.toString())
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-evidence-cross"),
        )
            .andExpect(status().isForbidden)
    }

    private data class EvidenceFixture(
        val tenantId: UUID,
        val userId: UUID,
        val ticketId: UUID,
    )

    private fun tenantWithPrivilegedAccess(): EvidenceFixture {
        val suffix = UUID.randomUUID().toString().take(8)
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val roleId = UUID.randomUUID()
        val permissionId = UUID.randomUUID()
        val operatorId = UUID.randomUUID()
        val approverId = UUID.randomUUID()
        val ticketId = UUID.randomUUID()

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId, "Plan $suffix", "plan-$suffix",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            tenantId, "Evidence $suffix", "evidence-$suffix",
            "tenant_${tenantId.toString().replace("-", "")}", planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
            VALUES (?, 'tenant_admin', true, true)
            """.trimIndent(),
            tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            userId, tenantId, "Tenant Admin $suffix", "admin-$suffix@example.test",
        )
        jdbcTemplate.update(
            "INSERT INTO tenant_roles (id, tenant_id, name, code) VALUES (?, ?, ?, ?)",
            roleId, tenantId, "Evidence Reader", "evidence-reader-$suffix",
        )
        jdbcTemplate.update(
            """
            INSERT INTO permissions (id, tenant_id, code, description)
            VALUES (?, ?, 'tenant.privileged_access.view', 'Read staff access history')
            ON CONFLICT ON CONSTRAINT permissions_tenant_id_code_key DO NOTHING
            """.trimIndent(),
            permissionId, tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
            SELECT ?, id FROM permissions
            WHERE tenant_id = ? AND code = 'tenant.privileged_access.view'
            """.trimIndent(),
            roleId, tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
            VALUES (?, ?, ?)
            """.trimIndent(),
            userId, tenantId, roleId,
        )

        // A grant belonging to this tenant, so each fixture has history that
        // must not appear in the other's response.
        listOf(operatorId to "operator", approverId to "approver").forEach { (id, label) ->
            jdbcTemplate.update(
                """
                INSERT INTO platform_users (id, full_name, email, status)
                VALUES (?, ?, ?, 'active')
                """.trimIndent(),
                id, "Platform $label $suffix", "$label-$suffix@example.test",
            )
        }
        jdbcTemplate.update(
            """
            INSERT INTO support_tickets (id, tenant_id, ticket_number, subject, status)
            VALUES (?, ?, ?, 'Evidence scoping', 'open')
            """.trimIndent(),
            ticketId, tenantId, "TCK-$suffix",
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_break_glass_access (
                platform_user_id, tenant_id, support_ticket_id, action_code, reason,
                status, approved_by, approved_at, activated_at, starts_at, expires_at,
                max_uses, assurance_level
            ) VALUES (
                ?, ?, ?, 'platform.tenants.view', 'Evidence scoping fixture',
                'active', ?, now(), now(), now() - interval '1 minute',
                now() + interval '1 hour', 5, 'phishing_resistant'
            )
            """.trimIndent(),
            operatorId, tenantId, ticketId, approverId,
        )

        return EvidenceFixture(tenantId, userId, ticketId)
    }
}
