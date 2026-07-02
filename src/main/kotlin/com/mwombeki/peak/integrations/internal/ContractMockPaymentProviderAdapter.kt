package com.mwombeki.peak.integrations.internal

// Non-production contract adapter retained for deterministic local acceptance.
import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.ProviderCollectionCommand
import com.mwombeki.peak.payment.api.ProviderCollectionResult
import com.mwombeki.peak.payment.api.ProviderWebhookNotification
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
) : PaymentProvider {
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
            eventKey = node.path("eventId").asString(
                node.requiredText("providerReference"),
            ),
            eventType = node.path("eventType").asString(
                "collection.updated",
            ),
            internalReference = node.requiredText("internalReference"),
            providerReference = node.requiredText("providerReference"),
            status = node.requiredText("status").lowercase(),
            amount = node.requiredText("amount").toBigDecimal(),
            feeAmount = node.path("feeAmount").asString("0").toBigDecimal(),
            currency = node.requiredText("currency").uppercase(),
            clientId = node.path("clientId").asString(null),
            providerTimestamp = node.path("updatedAt").asString(null)
                ?.let(java.time.Instant::parse),
            checksumMethod = node.path("checksumMethod").asString(null),
            metadata = mapOf("contractVersion" to node.path("contractVersion").asString("1")),
        )
    }

    private fun tools.jackson.databind.JsonNode.requiredText(field: String): String {
        return path(field).asString().trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Provider payload field $field is required")
    }
}
