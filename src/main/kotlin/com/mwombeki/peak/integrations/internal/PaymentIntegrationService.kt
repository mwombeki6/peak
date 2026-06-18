package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.integrations.api.InitiatePaymentRequest
import com.mwombeki.peak.integrations.api.PaymentPort
import com.mwombeki.peak.integrations.api.PaymentStatusResponse
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PaymentIntegrationService(
    private val properties: PaymentIntegrationProperties,
    private val jdbcTemplate: JdbcTemplate
) : PaymentPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun initiatePayment(request: InitiatePaymentRequest): PaymentStatusResponse {
        val providerKey = request.provider.name.lowercase().replace("_", "-")
        val config = properties.providers[providerKey]
            ?: throw IllegalArgumentException("Configuration not found for provider: ${request.provider}")

        logger.info("Initiating ${request.paymentMethod} payment via ${request.provider} for amount ${request.amount}")

        val referenceId = "PAY-${UUID.randomUUID().toString().take(8).uppercase()}"

        // Persist the transaction attempt
        jdbcTemplate.update(
            """
            INSERT INTO payment_transactions (id, session_id, reference_id, provider, method, phone_number, account_number, amount, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
            """.trimIndent(),
            UUID.randomUUID(),
            request.sessionId,
            referenceId,
            request.provider.name,
            request.paymentMethod.name,
            request.phoneNumber,
            request.accountNumber,
            request.amount
        )

        // In a real implementation, you would use a WebClient or RestTemplate to call config.baseUrl
        // using config.apiKey and config.apiSecret for authentication.

        return PaymentStatusResponse(
            referenceId = referenceId,
            status = "PENDING",
            message = "Payment initiated successfully via ${request.provider}. Please complete the transaction on your device."
        )
    }
}
