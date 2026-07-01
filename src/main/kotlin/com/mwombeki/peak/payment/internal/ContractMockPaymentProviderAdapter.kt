package com.mwombeki.peak.payment.internal

import java.util.UUID
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
@ConditionalOnProperty(
    prefix = "peak.payment.providers.contract-mock",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ContractMockPaymentProviderAdapter(
    private val objectMapper: ObjectMapper,
) : PaymentProviderAdapter {
    override val providerCode = "contract_mock"

    override fun initiate(command: ProviderCollectionCommand): ProviderCollectionResult {
        return ProviderCollectionResult(
            providerReference = "MOCK-${UUID.randomUUID()}",
            status = "pending",
        )
    }

    override fun parseWebhook(payload: String): ProviderWebhookNotification {
        val node = objectMapper.readTree(payload)
        return ProviderWebhookNotification(
            internalReference = node.requiredText("internalReference"),
            providerReference = node.requiredText("providerReference"),
            status = node.requiredText("status").lowercase(),
            amount = node.requiredText("amount").toBigDecimal(),
            feeAmount = node.path("feeAmount").asString("0").toBigDecimal(),
            currency = node.requiredText("currency").uppercase(),
            metadata = mapOf("contractVersion" to node.path("contractVersion").asString("1")),
        )
    }

    private fun tools.jackson.databind.JsonNode.requiredText(field: String): String {
        return path(field).asString().trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Provider payload field $field is required")
    }
}
