package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.payment.api.PaymentRejectedException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The V97/V98 shape: a sandbox payment was initiated, confirmed, and independently
 * recovered by status query. A non-empty label is not that, and Peak does not invent
 * a live sandbox run to fill it in.
 */
internal object SandboxCollectionEvidence {
    fun requireValid(
        raw: String?,
        providerCode: String,
        objectMapper: ObjectMapper,
    ): String {
        val evidence = raw?.trim().orEmpty()
        if (evidence.isEmpty()) {
            throw PaymentRejectedException(
                "sandboxEvidenceRef is required: record a sandbox payment that was " +
                    "initiated, confirmed, and independently recovered by status query",
            )
        }
        val node = try {
            objectMapper.readTree(evidence)
        } catch (_: Exception) {
            throw PaymentRejectedException(
                "sandboxEvidenceRef must be JSON recording initiate, confirm, and " +
                    "status-query recovery — a label is not evidence",
            )
        }
        if (!node.isObject) {
            throw PaymentRejectedException(
                "sandboxEvidenceRef must be a JSON object recording initiate, confirm, " +
                    "and status-query recovery",
            )
        }

        val recordedProvider = node.requiredText("provider")
        if (!recordedProvider.equals(providerCode.trim(), ignoreCase = true)) {
            throw PaymentRejectedException(
                "sandboxEvidenceRef is for $recordedProvider, not $providerCode",
            )
        }

        val collectionFlow = node.requiredText("collection_flow")
        if (providerCode.equals("snippe", ignoreCase = true) &&
            collectionFlow != DIRECT_PUSH
        ) {
            throw PaymentRejectedException(
                "Snippe guest-rail certification requires collection_flow=direct_push",
            )
        }
        if (collectionFlow !in setOf(DIRECT_PUSH, HOSTED_CHECKOUT)) {
            throw PaymentRejectedException(
                "sandboxEvidenceRef.collection_flow must be $DIRECT_PUSH or $HOSTED_CHECKOUT",
            )
        }

        val initiatedReference = node.requiredText("initiated_reference")
        if (initiatedReference.startsWith("PEAK-", ignoreCase = true)) {
            throw PaymentRejectedException(
                "sandboxEvidenceRef.initiated_reference must be the provider-issued " +
                    "reference, not Peak's handle",
            )
        }

        val confirmedStatus = node.requiredText("confirmed_status").lowercase()
        if (confirmedStatus !in setOf("completed", "succeeded")) {
            throw PaymentRejectedException(
                "sandboxEvidenceRef.confirmed_status must be completed — a pending " +
                    "sandbox payment is not certification",
            )
        }

        if (!node.path("recovered_by_status_query").asBoolean(false)) {
            throw PaymentRejectedException(
                "sandboxEvidenceRef must record recovered_by_status_query=true: a " +
                    "callback alone is not enough if the status query did not recover it",
            )
        }

        return evidence
    }

    private fun JsonNode.requiredText(field: String): String {
        val value = path(field).asString("").trim()
        if (value.isEmpty()) {
            throw PaymentRejectedException("sandboxEvidenceRef.$field is required")
        }
        return value
    }

    private const val DIRECT_PUSH = "direct_push"
    private const val HOSTED_CHECKOUT = "hosted_checkout"
}
