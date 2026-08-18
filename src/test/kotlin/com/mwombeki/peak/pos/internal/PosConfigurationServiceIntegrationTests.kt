package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.pos.api.PosNotFoundException
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PosConfigurationServiceIntegrationTests {
    @Autowired
    private lateinit var service: PosConfigurationService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
    }

    @Test
    fun `lists outlet menu categories and items for the till catalog`() {
        val fixture = insertFixture()
        bind(fixture)

        val categories = service.listMenuCategories(fixture.propertyId, fixture.outletId)
        assertEquals(1, categories.size)
        assertEquals(fixture.categoryId, categories.single().id)
        assertEquals(fixture.outletId, categories.single().outletId)
        assertEquals("Food", categories.single().name)

        val items = service.listMenuItems(fixture.propertyId, fixture.outletId)
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals(fixture.menuItemId, item.id)
        assertEquals(fixture.categoryId, item.categoryId)
        assertEquals("Lunch", item.name)
        assertEquals(0, BigDecimal("10.00").compareTo(item.price))
        assertEquals(fixture.taxRateId, item.taxRateId)
        assertTrue(item.isAvailable)
    }

    @Test
    fun `does not leak another outlet catalog into the till`() {
        val fixture = insertFixture()
        val otherOutletId = UUID.randomUUID()
        val otherCategoryId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO outlets (id, tenant_id, property_id, name, type, is_active)
            VALUES (?, ?, ?, 'Bar', 'BAR', true)
            """.trimIndent(),
            otherOutletId,
            fixture.tenantId,
            fixture.propertyId,
        )
        jdbcTemplate.update(
            "INSERT INTO menu_categories (id, tenant_id, outlet_id, name) VALUES (?, ?, ?, 'Drinks')",
            otherCategoryId,
            fixture.tenantId,
            otherOutletId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO menu_items (
                id, tenant_id, category_id, name, price, vat_rate,
                is_available, tax_rate_id
            )
            VALUES (?, ?, ?, 'Beer', 6.00, 18.00, true, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            fixture.tenantId,
            otherCategoryId,
            fixture.taxRateId,
        )
        bind(fixture)

        val categories = service.listMenuCategories(fixture.propertyId, fixture.outletId)
        val items = service.listMenuItems(fixture.propertyId, fixture.outletId)
        assertEquals(listOf("Food"), categories.map { it.name })
        assertEquals(listOf("Lunch"), items.map { it.name })
    }

    @Test
    fun `refuses a catalog read for an unknown outlet`() {
        val fixture = insertFixture()
        bind(fixture)

        assertFailsWith<PosNotFoundException> {
            service.listMenuItems(fixture.propertyId, UUID.randomUUID())
        }
        assertFailsWith<PosNotFoundException> {
            service.listMenuCategories(fixture.propertyId, UUID.randomUUID())
        }
    }

    private fun insertFixture(): CatalogFixture {
        val fixture = CatalogFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            outletId = UUID.randomUUID(),
            categoryId = UUID.randomUUID(),
            menuItemId = UUID.randomUUID(),
            taxRateId = UUID.randomUUID(),
        )
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            fixture.planId,
            "POS Plan ${fixture.planId}",
            "pos-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            fixture.tenantId,
            "POS Tenant ${fixture.tenantId}",
            "pos-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        verifyTenantBusiness(fixture.tenantId)
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'POS Operator', ?, 'active', true)
            """.trimIndent(),
            fixture.userId,
            fixture.tenantId,
            "pos-${fixture.userId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, status, is_active, total_rooms)
            VALUES (?, ?, 'POS Property', 'active', true, 0)
            """.trimIndent(),
            fixture.propertyId,
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO outlets (id, tenant_id, property_id, name, type, is_active)
            VALUES (?, ?, ?, 'Restaurant', 'RESTAURANT', true)
            """.trimIndent(),
            fixture.outletId,
            fixture.tenantId,
            fixture.propertyId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO menu_categories (id, tenant_id, outlet_id, name)
            VALUES (?, ?, ?, 'Food')
            """.trimIndent(),
            fixture.categoryId,
            fixture.tenantId,
            fixture.outletId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tax_rates (
                id, tenant_id, name, code, rate, tax_type, applies_to,
                is_inclusive, is_active
            )
            VALUES (?, ?, 'VAT', ?, 0.18, 'vat', ARRAY['food'], false, true)
            """.trimIndent(),
            fixture.taxRateId,
            fixture.tenantId,
            "VAT-${fixture.taxRateId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO menu_items (
                id, tenant_id, category_id, name, price, vat_rate,
                is_available, tax_rate_id
            )
            VALUES (?, ?, ?, 'Lunch', 10.00, 18.00, true, ?)
            """.trimIndent(),
            fixture.menuItemId,
            fixture.tenantId,
            fixture.categoryId,
            fixture.taxRateId,
        )
        return fixture
    }

    /** PosCommandExecutor now requires business verification before any POS command. */
    private fun verifyTenantBusiness(tenantId: UUID) {
        val platformUserId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO platform_users (id, full_name, email) VALUES (?, 'Test Verifier', ?)",
            platformUserId,
            "verifier-$platformUserId@example.test",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_profiles (
                tenant_id, legal_name, entity_type, business_phone, business_email,
                verification_status, verified_at, verified_by_platform_user_id
            ) VALUES (?, 'POS Test Business', 'limited_company', '+255700000000', ?,
                      'verified', now(), ?)
            """.trimIndent(),
            tenantId,
            "business-$tenantId@example.test",
            platformUserId,
        )
    }

    private fun bind(fixture: CatalogFixture) {
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Tenant(fixture.tenantId, fixture.userId),
                correlationId = "corr-pos-catalog",
                idempotencyKey = "pos-catalog",
                httpMethod = "GET",
                requestPath = "/api/v1/properties/${fixture.propertyId}/pos-config/menu-items",
            ),
        )
    }

    private data class CatalogFixture(
        val planId: UUID,
        val tenantId: UUID,
        val userId: UUID,
        val propertyId: UUID,
        val outletId: UUID,
        val categoryId: UUID,
        val menuItemId: UUID,
        val taxRateId: UUID,
    )
}
