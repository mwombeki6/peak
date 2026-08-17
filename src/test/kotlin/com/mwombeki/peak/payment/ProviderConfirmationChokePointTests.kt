package com.mwombeki.peak.payment

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Only one place may turn a provider's word into money on a folio.
 *
 * `GuestPaymentConfirmationService.confirm()` refuses unless the provider account, property,
 * internal reference, amount and currency all agree with the transaction's own row. That
 * check is what stops one hotel's merchant context settling its sibling's payment — a real
 * possibility, since a group holds several properties under one tenant.
 *
 * The check is worth nothing if a second handler can post to a folio without passing through
 * it. Someone adding a rail will reach for `billingPort.postConfirmedPayment` directly,
 * because it is right there and it works; this test is what stops that being quiet.
 *
 * **Cash and manual recording are deliberately exempt.** `collectCash` and
 * `recordManualMobileMoney` post payments a human attested to — a receptionist took notes, or
 * read a reference off the guest's phone. There is no provider observation to bind against,
 * so requiring the binding would be requiring evidence that does not exist. They are a
 * different kind of claim, not a loophole.
 */
class ProviderConfirmationChokePointTests {

    private val paymentRoot = Path.of("src/main/kotlin/com/mwombeki/peak/payment")

    @Test
    fun onlyTheConfirmationServiceTurnsAProviderObservationIntoAFolioPayment() {
        val offenders = paymentSources()
            .filter { (name, source) ->
                name != CONFIRMATION_SERVICE &&
                    source.contains(FOLIO_POSTING) &&
                    PROVIDER_EVIDENCE.any { source.contains(it) }
            }
            .map { it.first }
            .toSet()

        assertTrue(
            offenders.isEmpty(),
            "these post to a folio while holding provider evidence, which means they settle " +
                "a payment without the merchant-account, property, reference, amount and " +
                "currency binding that confirm() enforces: $offenders",
        )
    }

    /**
     * Pins the exemption rather than leaving it implicit, so that if a third non-provider
     * posting path appears someone has to say what kind of claim it is.
     */
    @Test
    fun theOnlyOtherPostersAreTheHumanAttestedOnes() {
        val posters = paymentSources()
            .filter { it.second.contains(FOLIO_POSTING) }
            .map { it.first }
            .toSet()

        assertEquals(
            setOf(CONFIRMATION_SERVICE, "internal/PaymentService.kt"),
            posters,
            "a new folio-posting path has appeared. If it settles a provider's word it " +
                "belongs behind confirm(); if a human attested to it, say so here.",
        )
    }

    /**
     * The binding is the point of the funnel, so it must not quietly become optional.
     */
    @Test
    fun theConfirmationServiceStillVerifiesEveryBinding() {
        val source = Files.readString(paymentRoot.resolve(CONFIRMATION_SERVICE))

        listOf(
            "providerAccountId",
            "propertyId",
            "internalReference",
            "amount",
            "currency",
        ).forEach { binding ->
            assertTrue(
                source.contains(binding),
                "confirm() no longer mentions $binding. Every binding must agree before " +
                    "money moves, or one hotel's callback can settle another's payment.",
            )
        }
    }

    /**
     * The path that acts on what a provider said must not know which provider said it.
     *
     * Three separate leaks of one provider's vocabulary into this path made every Snippe and
     * AzamPay callback fail: an event type enumerating ClickPesa's two event names, a status
     * compared against `posted`, and a merchant check reading a field ClickPesa fills with a
     * merchant id and the others fill with the guest's phone number. Each was written when
     * ClickPesa was the only rail, each looked correct, and the suite stayed green because
     * every adapter test asserted the word that adapter itself had invented.
     *
     * The mapping belongs in the adapter. Naming a provider here means it has leaked back.
     *
     * Deliberately scoped to these four files. Elsewhere in the module naming a provider is
     * legitimate — `PaymentService` restricts statement imports to ClickPesa explicitly and
     * says so, and the controller keeps a ClickPesa-shaped route because a callback URL
     * already registered with a provider cannot be changed unilaterally. Those are honest
     * statements about one provider. What must not exist is a general path that quietly
     * assumes one.
     */
    @Test
    fun theConfirmationPathNeverNamesAProvider() {
        val providerCodes = providerCodesFromAdapters()
        assertTrue(
            providerCodes.containsAll(setOf("clickpesa", "snippe", "azampay")),
            "the three guest rails must be discovered, or this test proves nothing: " +
                providerCodes,
        )

        val leaks = PROVIDER_AGNOSTIC_PATH.associateWith { file ->
            val code = stripComments(Files.readString(paymentRoot.resolve("internal/$file")))
            providerCodes.filter { code.contains(it, ignoreCase = true) }
        }.filterValues { it.isNotEmpty() }

        assertTrue(
            leaks.isEmpty(),
            "these decide what a provider's message means while knowing which provider sent " +
                "it, which is how the last three rails-down bugs happened: $leaks",
        )
    }

    /** Read from the adapters, so a new provider is covered without editing this test. */
    private fun providerCodesFromAdapters(): List<String> =
        Files.walk(Path.of("src/main/kotlin/com/mwombeki/peak/integrations")).use { paths ->
            paths.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .flatMap { PROVIDER_CODE.findAll(Files.readString(it)) }
                .map { it.groupValues[1] }
                .toList()
        }

    /** Comments discuss the history on purpose; only code may not name a provider. */
    private fun stripComments(source: String): String =
        source.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

    private fun paymentSources(): List<Pair<String, String>> =
        Files.walk(paymentRoot).use { paths ->
            paths.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .map { paymentRoot.relativize(it).toString() to Files.readString(it) }
                .toList()
        }

    private companion object {
        const val CONFIRMATION_SERVICE = "internal/GuestPaymentConfirmationService.kt"
        const val FOLIO_POSTING = "postConfirmedPayment("

        /** Holding any of these means the code is acting on what a provider said. */
        val PROVIDER_EVIDENCE = listOf(
            "ProviderPaymentObservation",
            "ProviderWebhookNotification",
            "ProviderStatusResult",
        )

        /** Everything that decides what a provider's message means. */
        val PROVIDER_AGNOSTIC_PATH = listOf(
            "PaymentWebhookService.kt",
            "PaymentStatusOutboxHandler.kt",
            "GuestPaymentConfirmationService.kt",
            "PaymentOutboxHandler.kt",
        )

        val PROVIDER_CODE = Regex("""override val providerCode\s*=\s*"([^"]+)"""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//.*""")
    }
}
