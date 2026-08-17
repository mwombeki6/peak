package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.payment.api.PaymentRejectedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

/**
 * Certifying a rail is recording that a sandbox payment was initiated, confirmed, and
 * independently recovered by status query. A non-empty label is not that.
 */
class SandboxCollectionEvidenceTests {

    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun emptyEvidenceIsRefused() {
        listOf("", "   ", "\n").forEach { blank ->
            val failure = assertFailsWith<PaymentRejectedException> {
                SandboxCollectionEvidence.requireValid(blank, "snippe", objectMapper)
            }
            assertTrue(
                failure.message.contains("sandboxEvidenceRef"),
                failure.message,
            )
        }
    }

    @Test
    fun aLabelIsNotEvidence() {
        val failure = assertFailsWith<PaymentRejectedException> {
            SandboxCollectionEvidence.requireValid("sandbox-run", "snippe", objectMapper)
        }
        assertTrue(
            failure.message.contains("JSON"),
            "a free-text label must not certify a rail: ${failure.message}",
        )
    }

    @Test
    fun recoveryMustHaveBeenIndependentOfTheCallback() {
        val failure = assertFailsWith<PaymentRejectedException> {
            SandboxCollectionEvidence.requireValid(
                """
                {"provider":"snippe","collection_flow":"direct_push",
                 "initiated_reference":"9015c155-9e29-4e8e-8fe6-d5d81553c8e6",
                 "confirmed_status":"completed","recovered_by_status_query":false}
                """.trimIndent(),
                "snippe",
                objectMapper,
            )
        }
        assertTrue(
            failure.message.contains("status query"),
            failure.message,
        )
    }

    @Test
    fun snippeGuestRailEvidenceMustBeDirectPush() {
        val failure = assertFailsWith<PaymentRejectedException> {
            SandboxCollectionEvidence.requireValid(
                snippeEvidence(collectionFlow = "hosted_checkout"),
                "snippe",
                objectMapper,
            )
        }
        assertTrue(
            failure.message.contains("direct_push"),
            failure.message,
        )
    }

    @Test
    fun peaksOwnHandleIsNotTheProviderIssuedReference() {
        val failure = assertFailsWith<PaymentRejectedException> {
            SandboxCollectionEvidence.requireValid(
                snippeEvidence(initiatedReference = "PEAK-ABC1234567890DEF1234"),
                "snippe",
                objectMapper,
            )
        }
        assertTrue(
            failure.message.contains("provider-issued"),
            failure.message,
        )
    }

    @Test
    fun evidenceForADifferentProviderIsRefused() {
        val failure = assertFailsWith<PaymentRejectedException> {
            SandboxCollectionEvidence.requireValid(snippeEvidence(), "clickpesa", objectMapper)
        }
        assertTrue(failure.message.contains("snippe"), failure.message)
    }

    @Test
    fun aCompleteRecoveryRecordIsAccepted() {
        val raw = snippeEvidence()
        assertEquals(raw, SandboxCollectionEvidence.requireValid(raw, "snippe", objectMapper))
    }

    private fun snippeEvidence(
        collectionFlow: String = "direct_push",
        initiatedReference: String = "9015c155-9e29-4e8e-8fe6-d5d81553c8e6",
    ) = """{"provider":"snippe","collection_flow":"$collectionFlow",""" +
        """"initiated_reference":"$initiatedReference",""" +
        """"confirmed_status":"completed","recovered_by_status_query":true}"""
}
