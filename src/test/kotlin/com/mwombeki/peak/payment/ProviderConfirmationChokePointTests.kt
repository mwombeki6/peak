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
    }
}
