package com.mwombeki.peak.payment

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.payment.api.PaymentProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import kotlin.test.Test
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
     * Which adapters actually implement `queryStatus`, read from their source.
     *
     * Reflection cannot answer this, which took two wrong versions of this test to establish.
     * `javaClass` is a CGLIB proxy that declares every overridable method. `getTargetClass`
     * unwraps that and is still wrong, because `PaymentProvider.queryStatus` is a Kotlin
     * interface method with a body, so the compiler emits an override into every implementing
     * class — `contract_mock` and `http_gateway` "declared" it too. Both versions passed while
     * the rail they were written to catch was broken.
     *
     * Calling it would answer the question, since the default throws immediately. But an
     * adapter that does implement it would make a real HTTP request to a payment provider
     * from a test run, which is not a thing a test suite should do.
     */
    private fun providersImplementingStatusQuery(): Set<String> =
        Files.walk(Path.of("src/main/kotlin/com/mwombeki/peak/integrations")).use { paths ->
            paths.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .map { Files.readString(it) }
                .filter { it.contains("override fun queryStatus") }
                .mapNotNull { PROVIDER_CODE.find(it)?.groupValues?.get(1) }
                .toSet()
        }

    private companion object {
        val PROVIDER_CODE = Regex("""override val providerCode\s*=\s*"([^"]+)"""")
    }

    private data class Rail(
        val provider: String,
        val paymentMethod: String,
        val collectionFlow: String?,
    ) {
        override fun toString() = "$provider/$paymentMethod" +
            (collectionFlow?.let { "/$it" } ?: "")
    }
}
