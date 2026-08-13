package com.mwombeki.peak.platformbilling

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every writer of the commercial control plane, classified by the authority it acts under.
 *
 * The route tests prove that a tenant's HTTP request passes through the restriction. They
 * say nothing about the other ways these tables get written, and that is where a bypass
 * would actually hide — a worker loop, an internal event handler, a service reached through
 * an interface rather than by name.
 *
 * The invariant is deliberately **not** "everything must call `can_access_module`". A
 * reconciler that asked permission of the tenant it is converging would be nonsense, and a
 * settlement handler that could be blocked by the very restriction the payment lifts would
 * be a deadlock. The real invariant is:
 *
 * > Every bypass is intentional, narrowly scoped, and attributable to a system or operator
 * > authority.
 *
 * So this is an inventory with a reason attached to each entry, and it fails when a writer
 * appears that nobody has classified. Searching for these by class name is not enough —
 * `TenantOwnedMutationService` has no references to its own name anywhere in the codebase
 * and is reached entirely through two interfaces, which is exactly the shape a bypass takes.
 */
class ControlPlaneWriterInventoryTests {

    /**
     * What may act on a writer, and why it is allowed to.
     */
    private enum class Authority(val rationale: String) {
        /**
         * Reached only through a `staff_permission` route, so `can_access_module` and with
         * it `tenant_restriction_permits` are on the path. Proven end to end by
         * `RestrictedTenantRouteIntegrationTests`.
         */
        TENANT_REQUEST("a customer acting on their own tenant, subject to restriction"),

        /**
         * Reached only through a `platform_permission` route. Governed by platform
         * permissions rather than by tenant restriction — an operator must be able to act
         * on a suspended tenant, since that is often the point.
         */
        PLATFORM_OPERATOR("Peak staff acting under platform permissions"),

        /**
         * A worker converging state the customer already paid for. Asking the tenant's
         * permission would be incoherent: the reconciler exists precisely to act when the
         * tenant's own entitlements have changed underneath them.
         */
        SYSTEM_CONVERGENCE("the reconciler making capability match entitlement"),

        /**
         * A worker applying a settled payment. Must not be restrictable: the restriction is
         * what the payment lifts, so letting it block settlement would deadlock a paying
         * customer inside suspension.
         */
        SYSTEM_SETTLEMENT("applying a payment that has already been made"),
    }

    /**
     * The classification. A new writer must be added here deliberately, with a reason, or
     * the build fails — which is the point.
     */
    private val inventory: Map<String, Authority> = mapOf(
        // --- tenant-facing, restriction applies ---
        "tenantmanagement/internal/application/TenantAdministrationService.kt"
            to Authority.TENANT_REQUEST,
        "tenantmanagement/internal/application/TenantTrustControlService.kt"
            to Authority.TENANT_REQUEST,
        // Reached through TenantLifecycleMutationPort and TenantModuleConfigurationPort,
        // never by its own name. Consumers are PropertyManagementService (tenant HTTP) and
        // TenantGovernanceService (platform).
        "tenantmanagement/internal/application/TenantOwnedMutationService.kt"
            to Authority.TENANT_REQUEST,
        "property/internal/PropertyManagementService.kt"
            to Authority.TENANT_REQUEST,

        // --- platform operators, separate policy ---
        "tenantmanagement/internal/application/PlatformCommercialControlService.kt"
            to Authority.PLATFORM_OPERATOR,
        "tenantmanagement/internal/application/PlatformTenantActivationService.kt"
            to Authority.PLATFORM_OPERATOR,
        "tenantmanagement/internal/application/PlatformTenantControlService.kt"
            to Authority.PLATFORM_OPERATOR,
        "tenantmanagement/internal/application/TenantOnboardingService.kt"
            to Authority.PLATFORM_OPERATOR,

        // --- system, deliberately unrestricted ---
        "tenantmanagement/internal/application/JdbcTenantEntitlementProjection.kt"
            to Authority.SYSTEM_CONVERGENCE,
        "property/internal/JdbcPropertyModuleProjection.kt"
            to Authority.SYSTEM_CONVERGENCE,
        "platformbilling/internal/PurchaseSettlementOutboxHandler.kt"
            to Authority.SYSTEM_SETTLEMENT,
    )

    @Test
    fun everyControlPlaneWriterIsClassified() {
        val discovered = discoverWriters()
        val unclassified = discovered - inventory.keys

        assertTrue(
            unclassified.isEmpty(),
            "these mutate the commercial control plane but no one has said under whose " +
                "authority they act. Add each to the inventory with a reason, or route it " +
                "through an existing writer:\n" + unclassified.sorted().joinToString("\n"),
        )
    }

    @Test
    fun theInventoryHasNoEntriesThatNoLongerWriteAnything() {
        val discovered = discoverWriters()
        val stale = inventory.keys - discovered

        assertTrue(
            stale.isEmpty(),
            "these are listed as control-plane writers but no longer write one. A stale " +
                "inventory is worse than none, because it implies a review that did not " +
                "happen:\n" + stale.sorted().joinToString("\n"),
        )
    }

    /**
     * The two system bypasses are the ones that could do real damage if they multiplied, so
     * they are pinned by name rather than merely counted.
     */
    @Test
    fun onlyTheProjectionsAndSettlementBypassRestriction() {
        val systemWriters = inventory
            .filterValues { it == Authority.SYSTEM_CONVERGENCE || it == Authority.SYSTEM_SETTLEMENT }
            .keys

        assertEquals(
            setOf(
                "tenantmanagement/internal/application/JdbcTenantEntitlementProjection.kt",
                "property/internal/JdbcPropertyModuleProjection.kt",
                "platformbilling/internal/PurchaseSettlementOutboxHandler.kt",
            ),
            systemWriters,
            "a new unrestricted system writer is the single easiest way to undo the whole " +
                "restriction design, so adding one should require changing this test",
        )
    }

    /**
     * The realtime plane goes through `can_access_module` rather than through the route
     * guard, so it would have been an easy thing to miss. V91 rewrote that function to AND
     * in the restriction, which means realtime is covered for free — but only for as long
     * as it keeps calling it.
     */
    @Test
    fun theRealtimePlaneStillAsksTheFunctionThatCarriesTheRestriction() {
        val authorizer = Path.of(
            "src/main/kotlin/com/mwombeki/peak/realtime/internal/RealtimeSubscriptionAuthorizer.kt",
        )
        assertTrue(Files.exists(authorizer), "the realtime authorizer has moved")
        assertTrue(
            Files.readString(authorizer).contains("can_access_module"),
            "realtime subscriptions must be authorized through can_access_module, which is " +
                "where tenant_restriction_permits is applied. A bespoke permission check " +
                "here would let a suspended tenant keep streaming what the HTTP plane denies.",
        )
    }

    private fun discoverWriters(): Set<String> {
        val sourceRoot = Path.of("src/main/kotlin/com/mwombeki/peak")
        return Files.walk(sourceRoot).use { paths ->
            paths.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .filter { path ->
                    val source = Files.readString(path)
                    CONTROL_PLANE_TABLES.any { table ->
                        MUTATION.toRegex().containsMatchIn(source.substringBefore("private companion")) &&
                            Regex(
                                """(INSERT\s+INTO|UPDATE|DELETE\s+FROM)\s+$table\b""",
                                RegexOption.IGNORE_CASE,
                            ).containsMatchIn(source)
                    }
                }
                .map { sourceRoot.relativize(it).toString() }
                .toSet()
        }
    }

    private companion object {
        /**
         * The tables that decide what a tenant may do. Writing any of them changes a
         * customer's capability, so every writer needs an accountable authority.
         */
        val CONTROL_PLANE_TABLES = listOf(
            "tenant_subscriptions",
            "tenant_modules",
            "property_modules",
            "tenant_control_states",
            "peak_product_grants",
        )

        const val MUTATION = """(INSERT\s+INTO|UPDATE|DELETE\s+FROM)"""
    }
}
