package com.mwombeki.peak.platformbilling

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.platformbilling.internal.SubscriptionLifecycleService.BillingLifecycleState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The invariant the whole billing design rests on, asserted as architecture rather than as
 * a property of one service.
 *
 * > A commercial delinquency transition must not remove the service-granting subscription or
 * > the grants required to execute safety-critical hotel operations.
 *
 * Two vocabularies, which must never be allowed to merge:
 *
 * - **Commercial control states** — active, restricted, suspended. "How much of what you own
 *   may you use right now?" Reversible, automatic, driven by whether you have paid.
 * - **Relationship-ending states** — cancelled, expired, terminated. "Do you still own it?"
 *   Deliberate, operator-driven, and not something being a fortnight late can cause.
 *
 * Collapsing them is the single most damaging mistake available here. It would not merely
 * over-restrict: it would route around the restriction allowances entirely, because a tenant
 * with no entitlements fails `is_tenant_module_enabled` long before
 * `tenant_restriction_permits` is ever consulted — so the checkout and data-export
 * allowances written to protect exactly these tenants would become unreachable.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CommercialDelinquencyInvariantTests {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    /**
     * No automatic state may drop a tenant out of the service-granting set. If one ever
     * does, plan entitlements stop resolving and every allowance below becomes dead code.
     */
    @Test
    fun noDelinquencyStateEndsTheCommercialRelationship() {
        BillingLifecycleState.entries.forEach { state ->
            assertTrue(
                state.subscriptionStatus in SERVICE_GRANTING_STATUSES,
                "$state sets subscription_status='${state.subscriptionStatus}'. " +
                    "Being late must not end the relationship — that is what cancelled, " +
                    "expired and terminated are for, and they are operator decisions.",
            )
            assertTrue(
                state.lifecycleStatus in COMMERCIAL_CONTROL_STATES,
                "$state sets lifecycle_status='${state.lifecycleStatus}', which is a " +
                    "relationship-ending state rather than a commercial control state",
            )
        }
    }

    /**
     * The four operations a tenant must retain no matter how overdue they are. Asserted
     * against the seeded allowances rather than against the code that consults them, so a
     * migration that quietly removed one is caught.
     */
    @Test
    fun everySafetyCriticalOperationSurvivesSuspension() {
        SAFETY_CRITICAL_PERMISSIONS.forEach { permission ->
            val covered = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM peak_restriction_allowances
                    WHERE restriction_state = 'suspended'
                      AND ? LIKE permission_pattern
                )
                """.trimIndent(),
                Boolean::class.java,
                permission,
            ) == true

            assertTrue(
                covered,
                "'$permission' is not permitted under suspension. A tenant must never be " +
                    "locked out of paying, and must never be able to strand a guest.",
            )
        }
    }

    /**
     * Suspension is the harsher state, so it must permit a subset of what restriction does.
     *
     * Evaluated over the real permission catalog rather than by comparing patterns, because
     * the patterns are not comparable as strings: `frontdesk.%` under restriction denotes a
     * superset of `frontdesk.checkout%` under suspension while looking nothing like it. The
     * property is about the permission sets, so the test has to be too.
     */
    @Test
    fun restrictionIsAtLeastAsPermissiveAsSuspension() {
        val leaked = jdbcTemplate.queryForList(
            """
            SELECT catalog.code
            FROM permission_catalog catalog
            WHERE EXISTS (
                      SELECT 1 FROM peak_restriction_allowances allowance
                      WHERE allowance.restriction_state = 'suspended'
                        AND catalog.code LIKE allowance.permission_pattern
                  )
              AND NOT EXISTS (
                      SELECT 1 FROM peak_restriction_allowances allowance
                      WHERE allowance.restriction_state = 'restricted'
                        AND catalog.code LIKE allowance.permission_pattern
                  )
            ORDER BY catalog.code
            """.trimIndent(),
            String::class.java,
        )

        assertTrue(
            leaked.isEmpty(),
            "these permissions survive suspension but not restriction, so a tenant would " +
                "regain them by falling further behind: $leaked",
        )
    }

    /**
     * Only the lifecycle service may move a tenant between commercial control states.
     *
     * A source scan because the claim is about every path. If a second writer appears, the
     * guard that keeps billing out of operator-owned states — offboarding, frozen, archived
     * — exists in only one of them.
     */
    @Test
    fun onlyTheLifecycleServiceMovesCommercialControlStates() {
        val moduleRoot = Path.of("src/main/kotlin/com/mwombeki/peak/platformbilling")
        val writers = Files.walk(moduleRoot).use { paths ->
            paths.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .filter { Files.readString(it).contains("applyBillingLifecycle") }
                .map { moduleRoot.relativize(it).toString() }
                .toSet()
        }

        assertEquals(
            setOf("internal/SubscriptionLifecycleService.kt"),
            writers,
            "a second writer would need its own copy of the guard that keeps billing out of " +
                "operator-owned states, and copies drift",
        )
    }

    private companion object {
        /** Statuses that keep `effective_tenant_entitlement` resolving a plan. */
        val SERVICE_GRANTING_STATUSES = setOf("trialing", "active", "past_due", "paused")

        /** States billing may move a tenant between. */
        val COMMERCIAL_CONTROL_STATES = setOf("trial", "active", "restricted", "suspended")

        /**
         * Check out a guest, take their money, get your data out, and pay us. Losing any one
         * of these to non-payment would either strand a guest or make the debt unpayable.
         */
        val SAFETY_CRITICAL_PERMISSIONS = listOf(
            "checkout.complete",
            "payments.collect",
            "fiscal.receipt.issue",
            "tenant.data.export",
            "tenant.subscription.purchase",
            "tenant.subscription.view",
        )
    }
}
