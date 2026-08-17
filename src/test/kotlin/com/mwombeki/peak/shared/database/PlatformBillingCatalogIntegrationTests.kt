package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertFailsWith

/**
 * The catalog is data, so the things worth testing are the ones a bad row would break
 * quietly: a product nobody can buy, a price that contradicts itself, an entitlement
 * naming a module that does not exist, or a callback route the guard will refuse.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PlatformBillingCatalogIntegrationTests {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun everySellableProductHasAPriceForEveryTerm() {
        val gaps = jdbcTemplate.queryForList(
            """
            SELECT product.code || ' @' || term.months AS gap
            FROM peak_products product
            CROSS JOIN (VALUES (1), (3), (6), (12)) AS term(months)
            WHERE product.is_sellable
              AND NOT EXISTS (
                  SELECT 1 FROM peak_product_prices price
                  WHERE price.product_code = product.code
                    AND price.term_months = term.months
              )
            """.trimIndent(),
            String::class.java,
        )

        assertTrue(gaps.isEmpty(), "sellable products missing a price: $gaps")
    }

    @Test
    fun longerTermsCostLessPerMonth() {
        val inversions = jdbcTemplate.queryForList(
            """
            SELECT monthly.product_code || ' ' || longer.term_months || 'mo' AS inversion
            FROM peak_product_prices monthly
            JOIN peak_product_prices longer
              ON longer.product_code = monthly.product_code
             AND longer.term_months > monthly.term_months
            WHERE monthly.term_months = 1
              AND longer.amount >= monthly.amount * longer.term_months
            """.trimIndent(),
            String::class.java,
        )

        assertTrue(
            inversions.isEmpty(),
            "a longer term must be cheaper per month or nobody prepays: $inversions",
        )
    }

    @Test
    fun theAnnualTermIsWorthCommittingTo() {
        val core = jdbcTemplate.queryForObject(
            "SELECT amount FROM peak_product_prices WHERE product_code = 'peak_core' AND term_months = 12",
            BigDecimal::class.java,
        )
        val monthly = jdbcTemplate.queryForObject(
            "SELECT amount FROM peak_product_prices WHERE product_code = 'peak_core' AND term_months = 1",
            BigDecimal::class.java,
        )

        // Mobile money cannot auto-renew, so an annual term is worth real money to Peak:
        // one PIN entry instead of twelve, and cash up front.
        assertTrue(
            requireNotNull(core) <= requireNotNull(monthly).multiply(BigDecimal(9)),
            "annual should save at least three months to be worth one approval",
        )
    }

    @Test
    fun everyGrantedModuleEntitlementNamesARealModule() {
        val unknown = jdbcTemplate.queryForList(
            """
            SELECT DISTINCT entitlement.entitlement_code
            FROM peak_product_entitlements entitlement
            WHERE entitlement.entitlement_code LIKE 'module.%'
              AND NOT EXISTS (
                  SELECT 1 FROM module_catalog catalog
                  WHERE catalog.module_id = replace(entitlement.entitlement_code, 'module.', '')
              )
            """.trimIndent(),
            String::class.java,
        )

        assertTrue(
            unknown.isEmpty(),
            "a product granting a module that does not exist activates nothing: $unknown",
        )
    }

    @Test
    fun baseProductsMapToAPlanAndAddOnsDoNot() {
        val wrong = jdbcTemplate.queryForList(
            """
            SELECT code FROM peak_products
            WHERE (kind = 'base' AND plan_code IS NULL)
               OR (kind = 'addon' AND plan_code IS NOT NULL)
            """.trimIndent(),
            String::class.java,
        )

        assertTrue(wrong.isEmpty(), "tier/plan binding is wrong for: $wrong")
    }

    @Test
    fun overlappingPricesAreUnrepresentable() {
        assertFailsWith<DataIntegrityViolationException> {
            jdbcTemplate.update(
                """
                INSERT INTO peak_product_prices (product_code, term_months, currency, amount)
                VALUES ('peak_core', 1, 'TZS', 99999.00)
                """.trimIndent(),
            )
        }
    }

    /**
     * `authorizePublicToken` requires `RouteScope.PUBLIC` exactly and refuses a route
     * carrying tenant or property variables. `public_property` satisfies the check
     * constraint and would deny every callback at runtime instead.
     */
    @Test
    fun theCallbackRouteUsesAScopeThePublicGuardAccepts() {
        val scope = jdbcTemplate.queryForObject(
            """
            SELECT route_scope FROM module_access_matrix
            WHERE screen_key = 'subscription.webhook'
            """.trimIndent(),
            String::class.java,
        )
        assertEquals("public", scope)

        val permission = jdbcTemplate.queryForList(
            "SELECT permission_code FROM module_access_matrix WHERE screen_key = 'subscription.webhook'",
            String::class.java,
        )
        assertEquals(
            listOf<String?>(null),
            permission,
            "the public token guard refuses a route that requires a permission",
        )
    }

    /**
     * Subscription routes must never sit under a module the reconciler can switch off, or
     * a lapsed tenant could not reach the page that ends the lapse.
     *
     * The screen keys are `subscription.*` rather than `billing.*` because the guest
     * billing module already owns that namespace, and a name that cannot distinguish
     * "the hotel's folios" from "the hotel's subscription to us" makes exactly this
     * check impossible to write.
     */
    @Test
    fun subscriptionRoutesAreNotBehindASellableModule() {
        val modules = jdbcTemplate.queryForList(
            "SELECT DISTINCT module_id FROM module_access_matrix WHERE screen_key LIKE 'subscription.%'",
            String::class.java,
        )

        assertEquals(listOf("tenant_admin"), modules)
    }
}
