package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import org.hamcrest.Matchers.containsString
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
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
class RouteGuardBoundaryControllerIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun deniesTenantIdentityOnPlatformControllerRoute() {
        mockMvc.perform(
            get("/api/v1/platform/tenants/${UUID.randomUUID()}")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-route-platform-as-tenant")
                .header(PeakRequestHeaders.TENANT_ID, UUID.randomUUID().toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, UUID.randomUUID().toString()),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(containsString("Platform identity is required")))
    }

    @Test
    fun deniesPlatformIdentityOnTenantControllerRoute() {
        mockMvc.perform(
            get("/api/v1/tenants/${UUID.randomUUID()}/roles")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-route-tenant-as-platform")
                .header(PeakRequestHeaders.PLATFORM_USER_ID, UUID.randomUUID().toString()),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(containsString("Tenant identity is required")))
    }

    @Test
    fun deniesTenantIdentityOnPublicPropertyControllerRoute() {
        mockMvc.perform(
            post("/api/v1/public/properties/${UUID.randomUUID()}/booking-engine/payments/initiate")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-route-public-as-tenant")
                .header(PeakRequestHeaders.TENANT_ID, UUID.randomUUID().toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, UUID.randomUUID().toString()),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(containsString("Public identity is required")))
    }

    @Test
    fun deniesPublicPropertyRouteWhenHeaderScopeConflictsWithPath() {
        val fixture = publicPropertyFixture()
        insertPublicPropertyFixture(fixture)

        mockMvc.perform(
            post("/api/v1/public/properties/${fixture.propertyId}/booking-engine/payments/initiate")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-route-public-scope-mismatch")
                .header(PeakRequestHeaders.PUBLIC_PROPERTY_ID, UUID.randomUUID().toString()),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(containsString("Requested property does not match public identity")))
    }

    private fun publicPropertyFixture(): PublicPropertyFixture {
        return PublicPropertyFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
        )
    }

    private fun insertPublicPropertyFixture(fixture: PublicPropertyFixture) {
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
            INSERT INTO properties (id, tenant_id, name, code, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            fixture.propertyId,
            fixture.tenantId,
            "Property ${fixture.propertyId}",
            "P${fixture.propertyId.toString().take(8)}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
            VALUES (?, 'booking_engine', true, true)
            """.trimIndent(),
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO property_modules (tenant_id, property_id, module_id, is_enabled, is_configured)
            VALUES (?, ?, 'booking_engine', true, true)
            """.trimIndent(),
            fixture.tenantId,
            fixture.propertyId,
        )
    }

    private data class PublicPropertyFixture(
        val planId: UUID,
        val tenantId: UUID,
        val propertyId: UUID,
    )
}
