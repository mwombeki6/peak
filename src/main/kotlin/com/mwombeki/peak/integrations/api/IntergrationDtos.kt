package com.mwombeki.peak.integrations.api

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class PublicBookingSessionRequest(
    val roomTypeId: UUID,
    val checkInDate: LocalDate,
    val checkOutDate: LocalDate,
    val guestName: String,
    val guestEmail: String,
)

data class PublicBookingSessionResponse(
    val sessionId: UUID,
    val status: String,
    val totalAmount: Double,
    val expiresAt: Instant,
)

data class WebhookTriggerRequest(
    val propertyId: UUID,
    val eventType: String, // e.g., "BOOKING_CREATED", "PAYMENT_RECEIVED"
    val payload: String    // JSON string of the event data
)
