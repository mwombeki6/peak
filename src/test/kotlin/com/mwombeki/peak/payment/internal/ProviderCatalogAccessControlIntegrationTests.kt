package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.hamcrest.Matchers.containsString
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
        "peak.security.outbound.allowed-provider-hosts[0]=api.clickpesa.com",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ProviderCatalogAccessControlIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun propertyPaymentConfigureCannotCreateTenantPaymentProviderCatalogEntry() {
        val fixture = fixture()
        insertPropertyOnlyFixture(fixture)

        mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/payments/provider-accounts")
                .secureJson(
                    """
                    {
                      "providerCode": "clickpesa",
                      "providerName": "ClickPesa",
                      "accountName": "Denied Account",
                      "clientId": "MERCHANT-001",
                      "apiKeySecretRef": "literal:payment-test-secret",
                      "checksumKeySecretRef": "literal:webhook-test-secret",
                      "endpointUrl": "https://api.clickpesa.com/third-parties",
                      "isDefault": true
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-provider-catalog-denied", "idem-provider-catalog-denied"),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string(containsString("Tenant-level payment provider catalog permission")))

        val providerCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM payment_providers
            WHERE tenant_id = ? AND provider_code = 'clickpesa'
            """.trimIndent(),
            Int::class.java,
            fixture.tenantId,
        )
        assertEquals(0, providerCount)
    }

    @Test
    fun propertyPaymentConfigureCanUseExistingProviderWithoutRenamingCatalogEntry() {
        val fixture = fixture()
        insertPropertyOnlyFixture(fixture)
        insertPaymentProvider(fixture, name = "Canonical ClickPesa")

        mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/payments/provider-accounts")
                .secureJson(
                    """
                    {
                      "providerCode": "clickpesa",
                      "providerName": "Attempted Rename",
                      "accountName": "Existing Provider Account",
                      "clientId": "MERCHANT-001",
                      "apiKeySecretRef": "literal:payment-test-secret",
                      "checksumKeySecretRef": "literal:webhook-test-secret",
                      "endpointUrl": "https://api.clickpesa.com/third-parties",
                      "isDefault": true
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-provider-catalog-existing", "idem-provider-catalog-existing"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.providerCode").value("clickpesa"))
            .andExpect(jsonPath("$.providerName").value("Canonical ClickPesa"))

        val providerName = jdbcTemplate.queryForObject(
            """
            SELECT name
            FROM payment_providers
            WHERE tenant_id = ? AND provider_code = 'clickpesa'
            """.trimIndent(),
            String::class.java,
            fixture.tenantId,
        )
        assertEquals("Canonical ClickPesa", providerName)
    }

    @Test
    fun propertyFiscalConfigureDoesNotRenameGlobalFiscalProviderCatalogEntry() {
        val fixture = fixture()
        insertPropertyOnlyFixture(fixture)

        mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/fiscal/provider-configs")
                .secureJson(
                    """
                    {
                      "providerCode": "contract_mock",
                      "providerName": "Attempted Fiscal Rename",
                      "authorityName": "Attempted Authority Rename",
                      "environment": "sandbox",
                      "endpointUrl": "https://fiscal.test.invalid",
                      "secretRef": "literal:fiscal-test-secret",
                      "taxpayerIdentifier": "TIN-${fixture.tenantId.toString().take(8)}",
                      "isDefault": true
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-fiscal-provider-catalog", "idem-fiscal-provider-catalog"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.providerCode").value("contract_mock"))
            .andExpect(jsonPath("$.providerName").value("Contract Mock Fiscal Provider"))

        val catalog = jdbcTemplate.queryForMap(
            """
            SELECT name, authority_name
            FROM fiscal_providers
            WHERE provider_code = 'contract_mock'
            """.trimIndent(),
        )
        assertEquals("Contract Mock Fiscal Provider", catalog["name"])
        assertEquals("Tanzania Revenue Authority", catalog["authority_name"])
    }

    private fun insertPropertyOnlyFixture(fixture: Fixture) {
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
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
            INSERT INTO users (id, tenant_id, full_name, email, status)
            VALUES (?, ?, ?, ?, 'active')
            """.trimIndent(),
            fixture.userId,
            fixture.tenantId,
            "Provider Property User ${fixture.userId}",
            "provider-property-${fixture.userId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, code, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            fixture.propertyId,
            fixture.tenantId,
            "Provider Property ${fixture.propertyId}",
            "P${fixture.propertyId.toString().take(8)}",
        )
        listOf("payments", "fiscal").forEach { moduleId ->
            jdbcTemplate.update(
                """
                INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
                VALUES (?, ?, true, true)
                """.trimIndent(),
                fixture.tenantId,
                moduleId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO property_modules (tenant_id, property_id, module_id, is_enabled, is_configured)
                VALUES (?, ?, ?, true, true)
                """.trimIndent(),
                fixture.tenantId,
                fixture.propertyId,
                moduleId,
            )
        }
        jdbcTemplate.update(
            """
            INSERT INTO roles (id, tenant_id, name)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.propertyRoleId,
            fixture.tenantId,
            "Provider Property Role ${fixture.propertyRoleId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.userId,
            fixture.propertyId,
            fixture.propertyRoleId,
            fixture.tenantId,
        )
        listOf("payments.configure", "fiscal.configure").forEach { permissionCode ->
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
                "INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                fixture.propertyRoleId,
                permissionId,
            )
        }
    }

    private fun insertPaymentProvider(fixture: Fixture, name: String) {
        jdbcTemplate.update(
            """
            INSERT INTO payment_providers (
                tenant_id,
                provider_code,
                name,
                provider_type,
                country_code,
                supported_currencies,
                supports_collections,
                is_active
            )
            VALUES (?, 'clickpesa', ?, 'mobile_money', 'TZ', ARRAY['TZS'], true, true)
            """.trimIndent(),
            fixture.tenantId,
            name,
        )
    }

    private fun MockHttpServletRequestBuilder.secureJson(
        json: String,
    ): MockHttpServletRequestBuilder {
        return secure(true)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(json)
    }

    private fun MockHttpServletRequestBuilder.headersFor(
        fixture: Fixture,
        correlationId: String,
        idempotencyKey: String,
    ): MockHttpServletRequestBuilder {
        return header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
            .header(PeakRequestHeaders.TENANT_USER_ID, fixture.userId.toString())
            .header(PeakRequestHeaders.CORRELATION_ID, correlationId)
            .header(PeakRequestHeaders.IDEMPOTENCY_KEY, idempotencyKey)
    }

    private fun fixture(): Fixture {
        return Fixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            propertyRoleId = UUID.randomUUID(),
        )
    }

    private data class Fixture(
        val planId: UUID,
        val tenantId: UUID,
        val propertyId: UUID,
        val userId: UUID,
        val propertyRoleId: UUID,
    )
}
