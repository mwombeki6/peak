package com.mwombeki.peak.tenantmanagement.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
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
class TenantAdministrationControllerIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun enablesListsAndDisablesTenantModuleWithIdempotencyAndSideEffects() {
        val fixture = tenantAdministrationFixture()
        insertAuthorizedFixture(fixture)

        mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/modules")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"moduleId": "property"}""")
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-tenant-module-enable")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-tenant-module-enable")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tenantId").value(fixture.tenantId.toString()))
            .andExpect(jsonPath("$.moduleId").value("property"))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.replayed").value(false))

        mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/modules")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"moduleId": "property"}""")
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-tenant-module-enable-replay")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-tenant-module-enable")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.moduleId").value("property"))
            .andExpect(jsonPath("$.replayed").value(true))

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/modules")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-tenant-modules-list")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[*].moduleId", hasItem("property")))
            .andExpect(jsonPath("$[*].name", hasItem("Property Setup")))

        mockMvc.perform(
            delete("/api/v1/tenants/${fixture.tenantId}/modules/property")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-tenant-module-disable")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-tenant-module-disable")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.moduleId").value("property"))
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.changed").value(true))

        assertEquals(1, auditCount(fixture.tenantId, "tenant.module.enabled"))
        assertEquals(1, outboxCount(fixture.tenantId, "tenant.module.enabled"))
        assertEquals(1, auditCount(fixture.tenantId, "tenant.module.disabled"))
        assertEquals(1, outboxCount(fixture.tenantId, "tenant.module.disabled"))
    }

    @Test
    fun reportsTenantReadinessFromRealProfileContactConsentAndModuleState() {
        val fixture = tenantAdministrationFixture()
        insertAuthorizedFixture(fixture)

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/readiness")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-tenant-readiness-missing")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tenantId").value(fixture.tenantId.toString()))
            .andExpect(jsonPath("$.isReady").value(false))
            .andExpect(jsonPath("$.missingRequirements").isNotEmpty)

        insertReadyTenantOperationalData(fixture)

        mockMvc.perform(
            get("/api/v1/tenants/${fixture.tenantId}/readiness")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-tenant-readiness-ready")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tenantId").value(fixture.tenantId.toString()))
            .andExpect(jsonPath("$.isReady").value(true))
            .andExpect(jsonPath("$.missingRequirements").isEmpty)
    }

    @Test
    fun deniesTenantModuleRouteWithoutModulePermission() {
        val fixture = tenantAdministrationFixture()
        insertFixtureWithoutPermissions(fixture)
        grantPermissionToActor(fixture, "tenant.profile.view")

        mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/modules")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"moduleId": "property"}""")
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-web-tenant-module-denied")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-web-tenant-module-denied")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, fixture.actorUserId.toString()),
        )
            .andExpect(status().isForbidden)
    }

    private fun tenantAdministrationFixture(): TenantAdministrationFixture {
        return TenantAdministrationFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            actorUserId = UUID.randomUUID(),
            actorRoleId = UUID.randomUUID(),
            platformUserId = UUID.randomUUID(),
            contactId = UUID.randomUUID(),
            contactChannelId = UUID.randomUUID(),
            reportSubscriptionId = UUID.randomUUID(),
        )
    }

    private fun insertAuthorizedFixture(fixture: TenantAdministrationFixture) {
        insertFixtureWithoutPermissions(fixture)
        grantPermissionToActor(fixture, "module.view")
        grantPermissionToActor(fixture, "module.manage")
        grantPermissionToActor(fixture, "tenant.profile.view")
    }

    private fun insertFixtureWithoutPermissions(fixture: TenantAdministrationFixture) {
        jdbcTemplate.update(
            """
            INSERT INTO plans (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.planId,
            "Tenant Admin Plan ${fixture.planId}",
            "tenant-admin-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, schema_name, plan_id, status)
            VALUES (?, ?, ?, ?, ?, 'active')
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
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            fixture.actorUserId,
            fixture.tenantId,
            "Tenant Admin ${fixture.actorUserId}",
            "tenant-admin-${fixture.actorUserId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.actorRoleId,
            fixture.tenantId,
            "Tenant Admin Role ${fixture.actorRoleId}",
            "tenant-admin-${fixture.actorRoleId}",
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

    private fun grantPermissionToActor(
        fixture: TenantAdministrationFixture,
        permissionCode: String,
    ) {
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
            VALUES (?, ?)
            """.trimIndent(),
            fixture.actorRoleId,
            permissionId,
        )
    }

    private fun insertReadyTenantOperationalData(fixture: TenantAdministrationFixture) {
        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, full_name, email, status)
            VALUES (?, ?, ?, 'active')
            """.trimIndent(),
            fixture.platformUserId,
            "Verifier ${fixture.platformUserId}",
            "verifier-${fixture.platformUserId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_profiles (
                tenant_id,
                legal_name,
                entity_type,
                business_phone,
                business_email,
                verification_status,
                verified_at,
                verified_by_platform_user_id
            )
            VALUES (?, ?, 'limited_company', '+255712345678', ?, 'verified', now(), ?)
            """.trimIndent(),
            fixture.tenantId,
            "Ready Tenant ${fixture.tenantId} Limited",
            "ops-${fixture.tenantId}@example.com",
            fixture.platformUserId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_contacts (
                id,
                tenant_id,
                full_name,
                job_title,
                status,
                is_primary_contact
            )
            VALUES (?, ?, ?, 'Managing Director', 'active', true)
            """.trimIndent(),
            fixture.contactId,
            fixture.tenantId,
            "Managing Director ${fixture.contactId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_contact_roles (
                tenant_id,
                contact_id,
                role_code,
                is_primary_for_role,
                created_by
            )
            VALUES (?, ?, 'owner_managing_director', true, ?)
            """.trimIndent(),
            fixture.tenantId,
            fixture.contactId,
            fixture.actorUserId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO contact_channels (
                id,
                tenant_id,
                contact_id,
                channel_type,
                address,
                normalized_address,
                is_primary,
                verification_status
            )
            VALUES (?, ?, ?, 'email', ?, ?, true, 'verified')
            """.trimIndent(),
            fixture.contactChannelId,
            fixture.tenantId,
            fixture.contactId,
            "ops-${fixture.tenantId}@example.com",
            "ops-${fixture.tenantId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO communication_consents (
                tenant_id,
                contact_id,
                contact_channel_id,
                purpose,
                status,
                policy_version,
                capture_source,
                captured_by
            )
            VALUES (?, ?, ?, 'operational_reports', 'active', 'v1', 'api', ?)
            """.trimIndent(),
            fixture.tenantId,
            fixture.contactId,
            fixture.contactChannelId,
            fixture.actorUserId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO report_subscriptions (
                id,
                tenant_id,
                report_code,
                subscription_name,
                scope,
                frequency,
                created_by
            )
            VALUES (?, ?, 'monthly_executive_summary', ?, 'tenant', 'monthly', ?)
            """.trimIndent(),
            fixture.reportSubscriptionId,
            fixture.tenantId,
            "Monthly Executive Summary ${fixture.tenantId}",
            fixture.actorUserId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO report_subscription_recipients (
                tenant_id,
                subscription_id,
                contact_id,
                contact_channel_id,
                delivery_format,
                is_enabled
            )
            VALUES (?, ?, ?, ?, 'pdf', true)
            """.trimIndent(),
            fixture.tenantId,
            fixture.reportSubscriptionId,
            fixture.contactId,
            fixture.contactChannelId,
        )
    }

    private fun auditCount(
        tenantId: UUID,
        action: String,
    ): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action = ?
                  AND entity_type = 'tenant_modules'
                  AND entity_id = ?
                """.trimIndent(),
                Int::class.java,
                tenantId,
                action,
                tenantId,
            ),
        )
    }

    private fun outboxCount(
        tenantId: UUID,
        eventType: String,
    ): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM outbox_events
                WHERE tenant_id = ?
                  AND event_type = ?
                  AND aggregate_type = 'tenant_modules'
                  AND aggregate_id = ?
                """.trimIndent(),
                Int::class.java,
                tenantId,
                eventType,
                tenantId,
            ),
        )
    }

    private data class TenantAdministrationFixture(
        val planId: UUID,
        val tenantId: UUID,
        val actorUserId: UUID,
        val actorRoleId: UUID,
        val platformUserId: UUID,
        val contactId: UUID,
        val contactChannelId: UUID,
        val reportSubscriptionId: UUID,
    )
}
