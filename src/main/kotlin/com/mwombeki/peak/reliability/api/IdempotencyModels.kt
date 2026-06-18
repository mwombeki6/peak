package com.mwombeki.peak.reliability.api

import java.time.Duration
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
data class IdempotencyCommand(
    val operationType: String,
    val requestPayload: Any?,
    val resourceType: String? = null,
    val ttl: Duration = Duration.ofHours(24),
) {
    init {
        require(operationType.isNotBlank()) {
            "Idempotency operation type is required"
        }
        require(ttl.toSeconds() > 0) {
            "Idempotency TTL must be positive"
        }
    }
}

@NamedInterface("api")
sealed interface IdempotencyReservation {
    val recordId: UUID

    @NamedInterface("api")
    data class Started(
        override val recordId: UUID,
    ) : IdempotencyReservation

    @NamedInterface("api")
    data class InProgress(
        override val recordId: UUID,
    ) : IdempotencyReservation

    @NamedInterface("api")
    data class Replay(
        override val recordId: UUID,
        val responseCode: Int?,
        val responseBody: String?,
        val status: IdempotencyStatus,
    ) : IdempotencyReservation

    @NamedInterface("api")
    data class Conflict(
        override val recordId: UUID,
    ) : IdempotencyReservation
}

@NamedInterface("api")
enum class IdempotencyStatus(val databaseValue: String) {
    PROCESSING("processing"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    EXPIRED("expired"),
}
