package com.mwombeki.peak.reliability.api

import java.time.Duration
import java.time.Instant
import java.util.UUID

interface OutboxPort {
    fun enqueue(command: OutboxEventCommand): UUID
}

interface OutboxWorkerPort {
    fun claim(
        workerId: String,
        destination: OutboxDestination? = null,
        limit: Int = 50,
    ): List<ClaimedOutboxEvent>

    fun complete(
        eventId: UUID,
        workerId: String,
    )

    fun fail(
        eventId: UUID,
        workerId: String,
        errorMessage: String,
        retryDelay: Duration = Duration.ofMinutes(5),
    )

    fun deadLetter(
        eventId: UUID,
        workerId: String,
        errorMessage: String,
    )

    fun reclaimStale(
        lockedBefore: Instant,
        limit: Int = 500,
    ): Int
}
