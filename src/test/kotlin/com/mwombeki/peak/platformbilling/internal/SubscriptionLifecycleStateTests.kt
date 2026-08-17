package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.platformbilling.internal.SubscriptionLifecycleService.BillingLifecycleState
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The state machine as arithmetic, driven by a clock rather than by waiting.
 *
 * Kept free of Spring so every boundary can be walked cheaply — the interesting bugs in a
 * lifecycle are all off-by-one-day at a boundary, and those are only worth testing if
 * testing them is cheap enough to do exhaustively.
 */
class SubscriptionLifecycleStateTests {

    private val paidThrough: Instant = Instant.parse("2026-06-01T00:00:00Z")

    private fun stateAt(offset: Duration): BillingLifecycleState =
        SubscriptionLifecycleService.stateFor(paidThrough, paidThrough.plus(offset))

    @Test
    fun coverWithPlentyOfTimeLeftIsSimplyActive() {
        assertEquals(BillingLifecycleState.ACTIVE, stateAt(Duration.ofDays(-90)))
        assertEquals(BillingLifecycleState.ACTIVE, stateAt(Duration.ofDays(-15)))
    }

    @Test
    fun theNoticePeriodOpensExactlyFourteenDaysOut() {
        assertEquals(BillingLifecycleState.ACTIVE, stateAt(Duration.ofDays(-14).minusSeconds(1)))
        assertEquals(BillingLifecycleState.RENEWAL_DUE, stateAt(Duration.ofDays(-14)))
        assertEquals(BillingLifecycleState.RENEWAL_DUE, stateAt(Duration.ofDays(-1)))
    }

    @Test
    fun lapsingMovesToGraceAndNotToAnythingRestrictive() {
        assertEquals(BillingLifecycleState.GRACE, stateAt(Duration.ZERO))
        assertEquals(BillingLifecycleState.GRACE, stateAt(Duration.ofDays(3)))
        assertEquals(BillingLifecycleState.GRACE, stateAt(Duration.ofDays(7).minusSeconds(1)))

        // The property that matters more than the boundary: grace restricts nothing.
        assertEquals(
            "active",
            BillingLifecycleState.GRACE.lifecycleStatus,
            "a hotel three days late must not discover it at 2am",
        )
    }

    @Test
    fun restrictionBeginsAfterSevenDaysAndSuspensionFourteenAfterThat() {
        assertEquals(BillingLifecycleState.RESTRICTED, stateAt(Duration.ofDays(7)))
        assertEquals(BillingLifecycleState.RESTRICTED, stateAt(Duration.ofDays(20)))
        assertEquals(BillingLifecycleState.RESTRICTED, stateAt(Duration.ofDays(21).minusSeconds(1)))
        assertEquals(BillingLifecycleState.SUSPENDED, stateAt(Duration.ofDays(21)))
        assertEquals(BillingLifecycleState.SUSPENDED, stateAt(Duration.ofDays(365)))
    }

    @Test
    fun payingFromAnyStateReturnsTheTenantToActive() {
        // Paying extends cover, which is the same thing as moving paidThrough forward.
        listOf(
            Duration.ofDays(1),
            Duration.ofDays(10),
            Duration.ofDays(30),
            Duration.ofDays(400),
        ).forEach { lapsedFor ->
            val lapsedState = stateAt(lapsedFor)
            assertTrue(
                lapsedState != BillingLifecycleState.ACTIVE,
                "precondition: $lapsedFor should not already be active",
            )

            val now = paidThrough.plus(lapsedFor)
            val renewedThrough = now.plus(Duration.ofDays(30))
            assertEquals(
                BillingLifecycleState.ACTIVE,
                SubscriptionLifecycleService.stateFor(renewedThrough, now),
                "paying must return a tenant to active from $lapsedState",
            )
        }
    }

    /**
     * Only two of the five states restrict anything, and `tenant_restriction_permits` keys
     * off exactly those. If a state's `lifecycleStatus` is ever changed, this is what
     * notices that restriction quietly turned on or off for a whole cohort.
     */
    @Test
    fun onlyRestrictedAndSuspendedCarryARestrictingLifecycleStatus() {
        val restricting = BillingLifecycleState.entries
            .filter { it.lifecycleStatus in setOf("restricted", "suspended") }
            .toSet()

        assertEquals(
            setOf(BillingLifecycleState.RESTRICTED, BillingLifecycleState.SUSPENDED),
            restricting,
        )
    }

    /**
     * Suspension must not expire the subscription row.
     *
     * An expired row leaves the service-granting set, so plan entitlements resolve to
     * nothing, the reconciler disables every module, and `can_access_module` fails at
     * `is_tenant_module_enabled` before the restriction allowances are ever consulted —
     * making checkout and data export unreachable for exactly the tenants they were
     * written to protect.
     */
    @Test
    fun noStateExpiresTheSubscriptionRow() {
        val serviceGranting = setOf("trialing", "active", "past_due", "paused")
        BillingLifecycleState.entries.forEach { state ->
            assertTrue(
                state.subscriptionStatus in serviceGranting,
                "$state sets subscription_status='${state.subscriptionStatus}', which would " +
                    "drop the tenant out of the service-granting set and make the " +
                    "restriction allowances unreachable",
            )
        }
    }

    /**
     * The safety property the whole design rests on: nothing charges without a customer
     * asking, in a live request, at that moment.
     *
     * Mobile money has no mandate — there is no stored instrument and no standing authority
     * — so an automatic renewal would not merely be rude, it would be impossible to perform
     * legitimately. This is a source scan rather than a behavioural test because the claim
     * is about every path, not about one.
     */
    @Test
    fun noBackgroundPathInitiatesACollection() {
        val moduleRoot = Path.of("src/main/kotlin/com/mwombeki/peak/platformbilling")
        val callers = Files.walk(moduleRoot).use { paths ->
            paths.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .filter { Files.readString(it).contains(".initiate(") }
                .map { moduleRoot.relativize(it).toString() }
                .toSet()
        }

        assertEquals(
            setOf("internal/PlatformCollectionService.kt"),
            callers,
            "only the request-scoped collection service may start a collection. Anything " +
                "here that runs in the background would be charging a customer who never " +
                "asked, against an instrument we do not hold.",
        )
    }
}
