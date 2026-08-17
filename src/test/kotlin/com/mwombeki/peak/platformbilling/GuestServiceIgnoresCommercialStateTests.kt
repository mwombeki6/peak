package com.mwombeki.peak.platformbilling

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A hotel that has not paid Peak must still be able to check its guests out.
 *
 * The way that guarantee is actually built is worth stating, because it is not obvious from
 * any one file: **the modules that serve guests do not know suspension exists.** Nothing in
 * `frontdesk`, `billing`, `fiscal`, `housekeeping`, `pos` or `inventory` reads
 * `lifecycle_status`, `tenant_control_states`, `tenant_subscriptions` or
 * `tenant_restriction_permits`. Commercial standing is enforced in exactly one place —
 * `can_access_module`, reached through `JdbcAuthorizationPort` and
 * `RealtimeSubscriptionAuthorizer` — and `peak_restriction_allowances` decides what survives
 * there.
 *
 * That is why a lapsed subscription cannot strand a guest by accident. There is no second
 * code path that could develop its own opinion about a `past_due` tenant and start refusing
 * checkouts, and no branch anyone could add without noticing what they were doing.
 *
 * The failure this prevents is a plausible one: a well-meaning change adds "and the tenant
 * must not be suspended" to a settlement or night-audit query, because that reads like
 * prudence. It would be discovered by a guest at a front desk at 2am, in a country where the
 * alternative to leaving is not leaving.
 *
 * A source scan rather than a journey test, deliberately. A test that walks reservation →
 * check-in → checkout under suspension proves the paths that exist today behave; this proves
 * the property that makes all of them safe, including the ones not written yet.
 */
class GuestServiceIgnoresCommercialStateTests {

    @Test
    fun theModulesThatServeGuestsCannotSeeWhetherTheHotelHasPaid() {
        val offenders = GUEST_SERVING_MODULES.associateWith { module ->
            sourcesIn(module)
                .filter { (_, source) -> COMMERCIAL_STATE.any { source.contains(it) } }
                .map { it.first }
        }.filterValues { it.isNotEmpty() }

        assertTrue(
            offenders.isEmpty(),
            "these read Peak's commercial state from a path that serves guests. Whatever the " +
                "intent, it creates a second place that can decide a hotel behind on its " +
                "subscription may not check someone out: $offenders",
        )
    }

    /**
     * The other half. If the choke point moved or was bypassed, the scan above would still
     * pass while enforcing nothing at all.
     */
    @Test
    fun commercialStandingIsStillEnforcedAtTheOneChokePoint() {
        val callers = Files.walk(Path.of("src/main/kotlin/com/mwombeki/peak")).use { paths ->
            paths.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .filter { Files.readString(it).contains("SELECT can_access_module(") }
                .map { it.fileName.toString() }
                .toList()
        }

        assertTrue(
            callers.containsAll(setOf("JdbcAuthorizationPort.kt", "RealtimeSubscriptionAuthorizer.kt")),
            "the authorization choke point has moved. Every tenant staff route and every " +
                "realtime subscription must pass through can_access_module, which is where " +
                "V91 ANDs in the restriction allowances: $callers",
        )
    }

    private fun sourcesIn(module: String): List<Pair<String, String>> {
        val root = Path.of("src/main/kotlin/com/mwombeki/peak", module)
        return Files.walk(root).use { paths ->
            paths.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .map { root.relativize(it).toString() to Files.readString(it) }
                .toList()
        }
    }

    private companion object {
        /** Everything a guest's stay touches between arrival and departure. */
        val GUEST_SERVING_MODULES = listOf(
            "frontdesk",
            "billing",
            "fiscal",
            "housekeeping",
            "pos",
            "inventory",
        )

        /** Where a tenant's standing with Peak is recorded. */
        val COMMERCIAL_STATE = listOf(
            "lifecycle_status",
            "tenant_control_states",
            "tenant_subscriptions",
            "tenant_restriction_permits",
        )
    }
}
