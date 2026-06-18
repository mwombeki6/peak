package com.mwombeki.peak.reliability.api

import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface OutboxEventHandler {
    val destination: OutboxDestination

    fun supports(event: ClaimedOutboxEvent): Boolean {
        return event.destination == destination
    }

    suspend fun handle(event: ClaimedOutboxEvent)
}
