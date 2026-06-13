package com.mwombeki.peak.reliability.internal

import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import java.time.Duration
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component

@Component
class OutboxEventDispatcher(
    private val handlers: List<OutboxEventHandler>,
) {
    fun supportedDestinations(): List<OutboxDestination> {
        return handlers
            .map { it.destination }
            .distinct()
            .sortedBy { it.databaseValue }
    }

    suspend fun dispatch(
        event: ClaimedOutboxEvent,
        timeout: Duration,
    ) {
        val handler = handlerFor(event)
        withTimeout(timeout.toMillis().coerceAtLeast(1)) {
            handler.handle(event)
        }
    }

    private fun handlerFor(event: ClaimedOutboxEvent): OutboxEventHandler {
        val matches = handlers.filter { it.supports(event) }
        return when (matches.size) {
            0 -> throw NoOutboxEventHandlerException(event)
            1 -> matches.single()
            else -> throw AmbiguousOutboxEventHandlerException(event, matches.size)
        }
    }
}

sealed class OutboxEventDispatchException(message: String) : RuntimeException(message)

class NoOutboxEventHandlerException(
    event: ClaimedOutboxEvent,
) : OutboxEventDispatchException(
    "No outbox handler is registered for destination ${event.destination.databaseValue} " +
            "and event type ${event.eventType}",
)

class AmbiguousOutboxEventHandlerException(
    event: ClaimedOutboxEvent,
    handlerCount: Int,
) : OutboxEventDispatchException(
    "Multiple outbox handlers ($handlerCount) match destination " +
            "${event.destination.databaseValue} and event type ${event.eventType}",
)
