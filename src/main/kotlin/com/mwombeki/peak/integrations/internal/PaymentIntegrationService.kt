package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.integrations.api.InitiatePaymentRequest
import com.mwombeki.peak.integrations.api.PaymentPort
import com.mwombeki.peak.integrations.api.PaymentStatusResponse
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.shared.context.RequestContextHolder
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
class PaymentIntegrationService(
    private val properties: PaymentIntegrationProperties,
    private val jdbcTemplate: JdbcTemplate,
    private val idempotencyPort: IdempotencyPort,
    private val requestContextHolder: RequestContextHolder,
    private val objectMapper: ObjectMapper
) : PaymentPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun initiatePayment(request: InitiatePaymentRequest): PaymentStatusResponse {
        val context = requestContextHolder.current()
        val idempotencyKey = context.idempotencyKey

        if (idempotencyKey == null) {
            return processInitiatePayment(request)
        }

        val reservation = idempotencyPort.reserve(
            IdempotencyCommand(
                operationType = "payment.initiate",
                requestPayload = request,
                resourceType = "payment_transactions"
            )
        )

        return when (reservation) {
            is IdempotencyReservation.Started -> {
                try {
                    val response = processInitiatePayment(request)
                    idempotencyPort.markSucceeded(
                        recordId = reservation.recordId,
                        responseCode = 200,
                        responseBody = objectMapper.writeValueAsString(response)
                    )
                    response
                } catch (ex: Exception) {
                    idempotencyPort.markFailed(
                        recordId = reservation.recordId,
                        responseCode = 500,
                        responseBody = ex.message
                    )
                    throw ex
                }
            }
            is IdempotencyReservation.Replay -> {
                if (reservation.responseBody != null) {
                    objectMapper.readValue(reservation.responseBody, PaymentStatusResponse::class.java)
                } else {
                    throw IllegalStateException("Idempotency replay has no response body")
                }
            }
            is IdempotencyReservation.InProgress -> {
                throw IllegalStateException("Payment initiation is already in progress for this idempotency key")
            }
            is IdempotencyReservation.Conflict -> {
                throw IllegalArgumentException("Idempotency key was already used for a different request")
            }
        }
    }

    private fun processInitiatePayment(request: InitiatePaymentRequest): PaymentStatusResponse {
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
