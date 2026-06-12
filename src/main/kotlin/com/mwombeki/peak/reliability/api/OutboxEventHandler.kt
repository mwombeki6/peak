package com.mwombeki.peak.reliability.api

interface OutboxEventHandler {
    val destination: OutboxDestination

    fun supports(event: ClaimedOutboxEvent): Boolean {
        return event.destination == destination
    }

    suspend fun handle(event: ClaimedOutboxEvent)
}
