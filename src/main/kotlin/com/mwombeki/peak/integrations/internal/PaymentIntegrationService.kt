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
import java.math.BigDecimal
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
        val scope = context.requirePublicScope()
        val idempotencyKey = context.idempotencyKey
            ?: throw IllegalArgumentException("Idempotency-Key header is required")

        val reservation = idempotencyPort.reserve(
            IdempotencyCommand(
                operationType = "payment.initiate",
                requestPayload = request,
                resourceType = "booking_payment_attempts"
            )
        )

        return when (reservation) {
            is IdempotencyReservation.Started -> {
                try {
                    val response = processInitiatePayment(
                        request = request,
                        scope = scope,
                        idempotencyKey = idempotencyKey,
                    )
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

    private fun processInitiatePayment(
        request: InitiatePaymentRequest,
        scope: PublicRequestScope,
        idempotencyKey: String,
    ): PaymentStatusResponse {
        validatePaymentRequest(request)

        val sessionExists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM booking_sessions
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND id = ?
                  AND status IN ('active', 'payment_pending')
            )
            """.trimIndent(),
            Boolean::class.java,
            scope.tenantId,
            scope.propertyId,
            request.sessionId,
        ) ?: false

        require(sessionExists) {
            "Booking session is not active for this public property"
        }

        val providerKey = request.provider.name.lowercase().replace("_", "-")
        val providerConfig = properties.providers[providerKey]
            ?: throw IllegalArgumentException("Configuration not found for provider: ${request.provider}")

        logger.info(
            "Initiating {} payment via {} for amount {} using {}",
            request.paymentMethod,
            request.provider,
            request.amount,
            providerConfig.baseUrl,
        )

        val referenceId = "PAY-${UUID.randomUUID().toString().take(8).uppercase()}"

        jdbcTemplate.update(
            """
            INSERT INTO booking_payment_attempts (
                id,
                tenant_id,
                property_id,
                session_id,
                provider,
                provider_payment_id,
                idempotency_key,
                amount,
                status
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending')
            """.trimIndent(),
            UUID.randomUUID(),
            scope.tenantId,
            scope.propertyId,
            request.sessionId,
            request.provider.toBookingAttemptProvider(),
            referenceId,
            idempotencyKey,
            BigDecimal.valueOf(request.amount),
        )

        // In a real implementation, you would use a WebClient or RestTemplate to call config.baseUrl
        // using config.apiKey and config.apiSecret for authentication.

        return PaymentStatusResponse(
            referenceId = referenceId,
            status = "PENDING",
            message = "Payment initiated successfully via ${request.provider}. Please complete the transaction on your device."
        )
    }

    private fun validatePaymentRequest(request: InitiatePaymentRequest) {
        require(request.amount > 0.0) {
            "Payment amount must be greater than zero"
        }

        when (request.paymentMethod) {
            com.mwombeki.peak.integrations.api.PaymentMethod.MOBILE_MONEY -> {
                require(!request.phoneNumber.isNullOrBlank()) {
                    "Phone number is required for mobile money payments"
                }
            }

            com.mwombeki.peak.integrations.api.PaymentMethod.BANK_TRANSFER -> {
                require(!request.accountNumber.isNullOrBlank()) {
                    "Account number is required for bank transfer payments"
                }
            }
        }
    }

    private fun com.mwombeki.peak.integrations.api.PaymentProvider.toBookingAttemptProvider(): String {
        return when (this) {
            com.mwombeki.peak.integrations.api.PaymentProvider.VODACOM_MPESA -> "mpesa"
            com.mwombeki.peak.integrations.api.PaymentProvider.AIRTEL_MONEY -> "airtel_money"
            com.mwombeki.peak.integrations.api.PaymentProvider.TIGO_PESA,
            com.mwombeki.peak.integrations.api.PaymentProvider.HALOPESA,
            com.mwombeki.peak.integrations.api.PaymentProvider.AZAMPESA -> "other"
            com.mwombeki.peak.integrations.api.PaymentProvider.NMB,
            com.mwombeki.peak.integrations.api.PaymentProvider.CRDB,
            com.mwombeki.peak.integrations.api.PaymentProvider.NBC -> "manual"
        }
    }
}
