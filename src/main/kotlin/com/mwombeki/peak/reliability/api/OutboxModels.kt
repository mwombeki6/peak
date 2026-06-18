package com.mwombeki.peak.reliability.api

import java.time.Instant
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
data class OutboxEventCommand(
    val aggregateType: String,
    val eventType: String,
    val destination: OutboxDestination,
    val payload: Any,
    val aggregateId: UUID? = null,
    val tenantId: UUID? = null,
    val propertyId: UUID? = null,
    val headers: Map<String, Any?> = emptyMap(),
    val idempotencyKeyId: UUID? = null,
    val priority: Int = 5,
    val maxAttempts: Int = 10,
) {
    init {
        require(aggregateType.isNotBlank()) {
            "Outbox aggregate type is required"
        }
        require(eventType.isNotBlank()) {
            "Outbox event type is required"
        }
        require(priority in 1..10) {
            "Outbox priority must be between 1 and 10"
        }
        require(maxAttempts > 0) {
            "Outbox max attempts must be positive"
        }
    }
}

@NamedInterface("api")
data class ClaimedOutboxEvent(
    val id: UUID,
    val tenantId: UUID?,
    val propertyId: UUID?,
    val aggregateType: String,
    val aggregateId: UUID?,
    val eventType: String,
    val destination: OutboxDestination,
    val payload: String,
    val headers: String,
    val correlationId: String?,
    val idempotencyKeyId: UUID?,
    val status: OutboxStatus,
    val priority: Int,
    val attemptCount: Int,
    val maxAttempts: Int,
    val nextAttemptAt: Instant,
    val lockedBy: String?,
    val lockedAt: Instant?,
    val deliveredAt: Instant?,
    val failedAt: Instant?,
    val errorMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@NamedInterface("api")
enum class OutboxDestination(val databaseValue: String) {
    FISCAL("fiscal"),
    PAYMENT("payment"),
    NOTIFICATION("notification"),
    ANALYTICS("analytics"),
    AUDIT("audit"),
    EDGE_SYNC("edge_sync"),
    WEBHOOK("webhook"),
    EMAIL("email"),
    SMS("sms"),
    WHATSAPP("whatsapp"),
    PLATFORM("platform"),
}

@NamedInterface("api")
enum class OutboxStatus(val databaseValue: String) {
    PENDING("pending"),
    LOCKED("locked"),
    DELIVERED("delivered"),
    FAILED("failed"),
    DEAD_LETTER("dead_letter"),
    CANCELLED("cancelled"),
}
