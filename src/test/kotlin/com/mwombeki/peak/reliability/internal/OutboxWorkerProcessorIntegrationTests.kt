package com.mwombeki.peak.reliability.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.reliability.api.OutboxWorkerPort
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(
    TestcontainersConfiguration::class,
    OutboxWorkerProcessorIntegrationTests.HandlerConfiguration::class,
)
@SpringBootTest(
    properties = [
        "peak.reliability.outbox.worker.enabled=false",
        "peak.reliability.outbox.worker.batch-size=10",
        "peak.reliability.outbox.worker.max-parallelism=2",
        "peak.reliability.outbox.worker.retry-initial-delay=1s",
        "peak.reliability.outbox.worker.retry-max-delay=5s",
        "peak.reliability.outbox.platform-operations.enabled=false",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class OutboxWorkerProcessorIntegrationTests {

    @Autowired
    private lateinit var processor: OutboxWorkerProcessor

    @Autowired
    private lateinit var outboxPort: OutboxPort

    @Autowired
    private lateinit var outboxWorkerPort: OutboxWorkerPort

    @Autowired
    private lateinit var handler: RecordingPlatformOutboxHandler

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @AfterTest
    fun cleanUp() {
        jdbcTemplate.update("DELETE FROM outbox_events")
        handler.reset()
        requestContextHolder.clear()
    }

    @Test
    fun deliversClaimedEventsThroughRegisteredHandler() {
        runBlocking {
            val eventId = enqueueEvent(
                destination = OutboxDestination.PLATFORM,
                correlationId = "corr-worker-deliver",
            )
            val deliveredBefore = counterValue("peak.outbox.worker.delivered", "platform")

            val result = processor.processBatch(OutboxDestination.PLATFORM)

            assertEquals(1, result.claimed)
            assertEquals(1, result.delivered)
            assertEquals(0, result.failed)
            assertEquals(0, result.deadLettered)
            assertEquals(listOf(eventId), handler.handledEventIds())

            val row = outboxRow(eventId)
            assertEquals("delivered", row["status"])
            assertEquals(null, row["locked_by"])
            assertNotNull(row["delivered_at"])
            assertEquals(deliveredBefore + 1.0, counterValue("peak.outbox.worker.delivered", "platform"))
        }
    }

    @Test
    fun failsClaimedEventWhenHandlerThrows() {
        runBlocking {
            handler.failWith = IllegalStateException("provider unavailable")
            val eventId = enqueueEvent(
                destination = OutboxDestination.PLATFORM,
                correlationId = "corr-worker-fail",
            )
            val failedBefore = counterValue("peak.outbox.worker.failed", "platform")

            val result = processor.processBatch(OutboxDestination.PLATFORM)

            assertEquals(1, result.claimed)
            assertEquals(0, result.delivered)
            assertEquals(1, result.failed)
            assertEquals(0, result.deadLettered)

            val row = outboxRow(eventId)
            assertEquals("failed", row["status"])
            assertEquals(null, row["locked_by"])
            assertEquals(1, row["attempt_count"])
            assertTrue(row["error_message"].toString().contains("provider unavailable"))
            assertNotNull(row["failed_at"])
            assertEquals(failedBefore + 1.0, counterValue("peak.outbox.worker.failed", "platform"))
        }
    }

    @Test
    fun deadLettersClaimedEventWithoutRegisteredHandler() {
        runBlocking {
            val eventId = enqueueEvent(
                destination = OutboxDestination.EMAIL,
                correlationId = "corr-worker-no-handler",
            )
            val deadLetteredBefore = counterValue("peak.outbox.worker.dead_lettered", "email")

            val result = processor.processBatch(OutboxDestination.EMAIL)

            assertEquals(1, result.claimed)
            assertEquals(0, result.delivered)
            assertEquals(0, result.failed)
            assertEquals(1, result.deadLettered)

            val row = outboxRow(eventId)
            assertEquals("dead_letter", row["status"])
            assertEquals(null, row["locked_by"])
            assertTrue(row["error_message"].toString().contains("No outbox handler"))
            assertEquals(
                deadLetteredBefore + 1.0,
                counterValue("peak.outbox.worker.dead_lettered", "email"),
            )
        }
    }

    @Test
    fun reclaimsStaleLocksThroughProcessor() {
        val eventId = enqueueEvent(
            destination = OutboxDestination.PLATFORM,
            correlationId = "corr-worker-reclaim",
        )
        outboxWorkerPort.claim("stale-worker", OutboxDestination.PLATFORM, 10)
        jdbcTemplate.update(
            """
            UPDATE outbox_events
            SET locked_at = now() - interval '30 minutes'
            WHERE id = ?
            """.trimIndent(),
            eventId,
        )
        val reclaimedBefore = counterValue("peak.outbox.worker.reclaimed", "all")

        val reclaimed = processor.reclaimStaleLocks()

        assertEquals(1, reclaimed)
        val row = outboxRow(eventId)
        assertEquals("failed", row["status"])
        assertEquals(null, row["locked_by"])
        assertEquals("Reclaimed after stale worker lock", row["error_message"])
        assertEquals(reclaimedBefore + 1.0, counterValue("peak.outbox.worker.reclaimed", "all"))
    }

    private fun enqueueEvent(
        destination: OutboxDestination,
        correlationId: String,
    ): UUID {
        return requireNotNull(
            transactionTemplate.execute {
                requestContextHolder.set(platformContext(correlationId))
                outboxPort.enqueue(
                    OutboxEventCommand(
                        aggregateType = "tenants",
                        aggregateId = UUID.randomUUID(),
                        eventType = "tenant.created",
                        destination = destination,
                        payload = mapOf("slug" to "peak"),
                        headers = mapOf("schema_version" to 1),
                        priority = 3,
                        maxAttempts = 3,
                    ),
                )
            },
        )
    }

    private fun outboxRow(eventId: UUID): Map<String, Any?> {
        return jdbcTemplate.queryForMap(
            """
            SELECT status, locked_by, delivered_at, failed_at, error_message, attempt_count
            FROM outbox_events
            WHERE id = ?
            """.trimIndent(),
            eventId,
        )
    }

    private fun platformContext(correlationId: String): RequestContext {
        return RequestContext(
            identity = RequestIdentity.Platform(
                platformUserId = UUID.randomUUID(),
                correlationId = correlationId,
            ),
            correlationId = correlationId,
            idempotencyKey = "idem-$correlationId",
            httpMethod = "POST",
            requestPath = "/api/v1/platform/tenants",
        )
    }

    private fun counterValue(name: String, destination: String): Double {
        return meterRegistry.find(name)
            .tag("destination", destination)
            .counter()
            ?.count()
            ?: 0.0
    }

    @TestConfiguration
    class HandlerConfiguration {
        @Bean
        fun recordingPlatformOutboxHandler(): RecordingPlatformOutboxHandler {
            return RecordingPlatformOutboxHandler()
        }
    }

    class RecordingPlatformOutboxHandler : OutboxEventHandler {
        override val destination = OutboxDestination.PLATFORM
        @Volatile
        var failWith: RuntimeException? = null
        private val handled = CopyOnWriteArrayList<UUID>()

        override suspend fun handle(event: ClaimedOutboxEvent) {
            handled.add(event.id)
            failWith?.let { throw it }
        }

        fun handledEventIds(): List<UUID> = handled.toList()

        fun reset() {
            handled.clear()
            failWith = null
        }
    }
}
