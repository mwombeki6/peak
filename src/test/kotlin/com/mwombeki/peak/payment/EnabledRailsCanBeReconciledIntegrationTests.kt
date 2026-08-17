package com.mwombeki.peak.payment

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.StatusQueryablePaymentProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * A rail may not be switched on unless Peak can find out for itself whether it was paid.
 *
 * ```
 * push sent ──► callback ──X lost
 *                  │
 *                  └──► status query ──► the only thing left that can answer
 * ```
 *
 * Mobile money callbacks are lost routinely — a worker restart, a network partition, a
 * provider's retry budget running out. If the callback is the only source of truth then a
 * customer whose account was debited is told the payment did not happen and invited to pay
 * again. `V97` exists to prevent exactly that, and it only holds if every enabled rail can
 * actually be asked.
 *
 * `peak_payment_method_capabilities.supports_status_query` records the claim, and `V98`
 * refuses to enable a rail that admits it cannot be queried. What nothing checked was whether
 * the claim was true. `azampay/mobile_money` shipped enabled, with the column set to true and
 * the release gate written up as passing in `provider-certification.md` — while
 * `AzamPayPaymentProvider` had no `queryStatus` at all and threw
 * `UnsupportedOperationException` for every attempt. The reconciler catches that and records
 * "unknown", so a lost callback on a live rail became a payment stuck forever awaiting an
 * operator, silently, with the documentation saying it was covered.
 *
 * A migration can only check a row against another row. This is the assertion that makes the
 * column mean something: the data's claim, checked against the code.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class EnabledRailsCanBeReconciledIntegrationTests {

    @Autowired private lateinit var providers: List<PaymentProvider>
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun everyEnabledRailHasAnAdapterThatCanAskTheProvider() {
        val enabled = jdbcTemplate.query(
            """
            SELECT provider, payment_method, collection_flow
            FROM peak_payment_method_capabilities
            WHERE is_enabled = true
            ORDER BY provider, payment_method
            """.trimIndent(),
        ) { rs, _ ->
            Rail(
                provider = rs.getString("provider"),
                paymentMethod = rs.getString("payment_method"),
                collectionFlow = rs.getString("collection_flow"),
            )
        }

        assertTrue(enabled.isNotEmpty(), "no rail is enabled, so this test proves nothing")

        val canAsk = providersImplementingStatusQuery()
        val registered = providers.map { it.providerCode }.toSet()
        val unreconcilable = enabled.filterNot {
            it.provider in registered && it.provider in canAsk
        }

        assertTrue(
            unreconcilable.isEmpty(),
            "these rails are enabled but Peak cannot independently determine whether a " +
                "payment on them succeeded, so a lost callback strands a customer who has " +
                "already been debited: $unreconcilable",
        )
    }

    /**
     * The capability must stay a real distinction.
     *
     * If `PaymentProvider` ever gained a `queryStatus` again — or something made every adapter
     * satisfy the queryable type — `filterIsInstance` would return everything and the check
     * above would pass while enforcing nothing. That is precisely how the two reflective
     * versions of this test failed, so the partition is stated rather than trusted.
     */
    @Test
    fun beingAbleToAskIsStillSomethingAnAdapterHasToEarn() {
        val queryable = providers.filterIsInstance<StatusQueryablePaymentProvider>()
            .map { it.providerCode }.toSet()
        val all = providers.map { it.providerCode }.toSet()

        assertEquals(
            setOf("clickpesa", "snippe", "azampay"),
            queryable,
            "these three carry money and must be answerable about it",
        )
        assertTrue(
            (all - queryable).isNotEmpty(),
            "no adapter is left that cannot be queried, so this test can no longer tell the " +
                "difference between a real capability and a universal one: $all",
        )
    }

    /**
     * The claim only ever gets stronger by accident, so this catches the reverse mistake: an
     * adapter that gained a status query while its capability row still says it has none, which
     * would keep a working rail switched off for no reason anyone would think to look for.
     */
    @Test
    fun noAdapterQuietlyOutgrowsItsCapabilityRow() {
        val understated = jdbcTemplate.query(
            """
            SELECT DISTINCT provider
            FROM peak_payment_method_capabilities
            WHERE supports_status_query = false
            """.trimIndent(),
        ) { rs, _ -> rs.getString("provider") }
            .filter { it in providersImplementingStatusQuery() }

        assertTrue(
            understated.isEmpty(),
            "these adapters can query status but their capability rows still say they " +
                "cannot, which keeps a usable rail disabled: $understated",
        )
    }

    /**
     * Which adapters can be asked, answered by the type system.
     *
     * This was a source scan, because the two obvious reflective versions both lied.
     * `javaClass` is a CGLIB proxy that declares every overridable method; `getTargetClass`
     * unwraps that and is still wrong, because a Kotlin interface method *with a body* makes
     * the compiler emit an override into every implementing class — `contract_mock` and
     * `http_gateway` "declared" `queryStatus` too. Both versions passed while the rail they
     * were written to catch was broken.
     *
     * Splitting the capability into its own interface removes the question rather than
     * answering it more cleverly. `is StatusQueryablePaymentProvider` cannot be fooled by a
     * proxy or by a compiler-emitted override, and an adapter that loses `queryStatus` now
     * fails to compile instead of failing to reconcile.
     */
    private fun providersImplementingStatusQuery(): Set<String> =
        providers.filterIsInstance<StatusQueryablePaymentProvider>()
            .map { it.providerCode }
            .toSet()

    private data class Rail(
        val provider: String,
        val paymentMethod: String,
        val collectionFlow: String?,
    ) {
        override fun toString() = "$provider/$paymentMethod" +
            (collectionFlow?.let { "/$it" } ?: "")
    }
}
