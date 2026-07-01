package com.mwombeki.peak.payments.api

import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * ClickPesa payment request DTO.
 */
data class ClickPesaInitiationRequest(
    val amount: BigDecimal,
    val currency: String = "TZS",
    val phoneNumber: String, // Customer phone
    val posSessionId: UUID,
    val folioId: UUID,
    val propertyId: UUID,
    val externalReference: String // Peak's internal ID for this request
)

/**
 * ClickPesa status response.
 */
data class ClickPesaStatusResponse(
    val transactionId: UUID,
    val providerReference: String?,
    val status: String, // CLICKPESA status (e.g. SUCCESS, PENDING)
    val message: String?
)

/**
 * ClickPesa Webhook Payload (simplified for start).
 */
data class ClickPesaWebhookPayload(
    val providerReference: String,
    val externalReference: UUID, // Peak's payment_transaction.id
    val status: String,
    val amount: BigDecimal,
    val currency: String,
    val signature: String? = null
)
