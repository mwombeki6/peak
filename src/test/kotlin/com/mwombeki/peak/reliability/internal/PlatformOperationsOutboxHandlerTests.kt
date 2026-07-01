package com.mwombeki.peak.reliability.internal

import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxStatus
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory

class PlatformOperationsOutboxHandlerTests {
    @Test
    fun `publishes platform event metric without requiring payload processing`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val beanFactory = StaticListableBeanFactory(mapOf("meterRegistry" to registry))
        val provider = beanFactory.getBeanProvider(
            MeterRegistry::class.java,
        )
        val handler = PlatformOperationsOutboxHandler(provider)
        val event = platformEvent()

        handler.handle(event)

        assertThat(
            registry.counter(
                "peak.outbox.platform.events.published",
                "event_type",
                event.eventType,
            ).count(),
        ).isEqualTo(1.0)
    }

    private fun platformEvent(): ClaimedOutboxEvent {
        val now = Instant.now()
        return ClaimedOutboxEvent(
            id = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            propertyId = null,
            aggregateType = "tenant_contacts",
            aggregateId = UUID.randomUUID(),
            eventType = "communication.contact.created",
            destination = OutboxDestination.PLATFORM,
            payload = """{"email":"must-not-be-logged@example.com"}""",
            headers = "{}",
            correlationId = UUID.randomUUID().toString(),
            idempotencyKeyId = UUID.randomUUID(),
            status = OutboxStatus.LOCKED,
            priority = 5,
            attemptCount = 1,
            maxAttempts = 10,
            nextAttemptAt = now,
            lockedBy = "test-worker",
            lockedAt = now,
            deliveredAt = null,
            failedAt = null,
            errorMessage = null,
            createdAt = now,
            updatedAt = now,
        )
    }
}
