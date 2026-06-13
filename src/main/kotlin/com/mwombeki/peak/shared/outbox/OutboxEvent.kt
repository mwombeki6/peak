package com.mwombeki.peak.shared.outbox

import java.time.Instant
import java.util.UUID

data class OutboxEvent(
    val id: UUID = UUID.randomUUID(),
    val tenantId: UUID,
    val eventType: String,  // e.g., "RESERVATION_CONFIRMED", "PAYMENT_RECEIVED"
    val paload: String,     // The raw JSON string containing the data payload
    val status: OutboxStatus = OutboxStatus.PENDING,
    val retryCount: Int = 0,
    val maxRetries: Int = 5,
    val createdAt: Instant = Instant.now(),
    val processedAt: Instant? = null,
    val errorMessage: String? = null,

)