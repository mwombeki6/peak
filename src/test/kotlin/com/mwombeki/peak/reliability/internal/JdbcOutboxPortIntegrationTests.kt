package com.mwombeki.peak.reliability.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.reliability.api.OutboxStatus
import com.mwombeki.peak.reliability.api.OutboxWorkerPort
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class JdbcOutboxPortIntegrationTests {

    @Autowired
    private lateinit var outboxPort: OutboxPort

    @Autowired
    private lateinit var outboxWorkerPort: OutboxWorkerPort

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeTest
    fun clearOutbox() {
        jdbcTemplate.update("DELETE FROM outbox_events")
    }

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
    }

    @Test
    fun enqueuesEventInsideTransaction() {
        val aggregateId = UUID.randomUUID()

        val eventId = transactionTemplate.execute {
            requestContextHolder.set(platformContext("corr-outbox-enqueue"))
            outboxPort.enqueue(command(aggregateId))
        }

        val row = jdbcTemplate.queryForMap(
            """
            SELECT aggregate_type, aggregate_id, event_type, destination,
                   payload::text AS payload, headers::text AS headers,
                   correlation_id, status, priority, max_attempts
            FROM outbox_events
            WHERE id = ?
            """.trimIndent(),
            eventId,
        )

        assertEquals("tenants", row["aggregate_type"])
        assertEquals(aggregateId, row["aggregate_id"])
        assertEquals("tenant.created", row["event_type"])
        assertEquals("platform", row["destination"])
        assertEquals("corr-outbox-enqueue", row["correlation_id"])
        assertEquals("pending", row["status"])
        assertEquals(3, row["priority"])
        assertEquals(4, row["max_attempts"])
        assertTrue(row["payload"].toString().contains("peak"))
        assertTrue(row["headers"].toString().contains("corr-outbox-enqueue"))
    }

    @Test
    fun rejectsEnqueueOutsideTransaction() {
        requestContextHolder.set(platformContext("corr-outside-transaction"))

        val error = assertFailsWith<IllegalArgumentException> {
            outboxPort.enqueue(command(UUID.randomUUID()))
        }

        assertEquals(
            "Outbox events must be enqueued inside an active transaction",
            error.message,
        )
    }

    @Test
    fun claimsAndCompletesEvent() {
        val eventId = enqueueEvent("corr-outbox-complete")

        val claimed = outboxWorkerPort.claim(
            workerId = "worker-a",
            destination = OutboxDestination.PLATFORM,
            limit = 10,
        )

        val event = claimed.first { it.id == eventId }
        assertEquals(OutboxStatus.LOCKED, event.status)
        assertEquals("worker-a", event.lockedBy)
        assertEquals(1, event.attemptCount)
        assertNotNull(event.lockedAt)

        outboxWorkerPort.complete(eventId, "worker-a")

        val row = jdbcTemplate.queryForMap(
            "SELECT status, locked_by, delivered_at FROM outbox_events WHERE id = ?",
            eventId,
        )

        assertEquals("delivered", row["status"])
        assertEquals(null, row["locked_by"])
        assertNotNull(row["delivered_at"])
    }

    @Test
    fun parallelWorkersDoNotClaimSameEvent() {
        jdbcTemplate.update("DELETE FROM outbox_events")
        val eventIds = (1..10).map { index ->
            enqueueEvent("corr-outbox-parallel-$index")
        }.toSet()
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        try {
            val futures = listOf("worker-a", "worker-b").map { workerId ->
                executor.submit<List<UUID>> {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    outboxWorkerPort.claim(
                        workerId = workerId,
                        destination = OutboxDestination.PLATFORM,
                        limit = 7,
                    ).map { it.id }
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val claimedByWorkers = futures.map { it.get(10, TimeUnit.SECONDS) }
            val claimedIds = claimedByWorkers.flatten()

            assertEquals(eventIds, claimedIds.toSet())
            assertEquals(claimedIds.size, claimedIds.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun failsEventWithRetryDelay() {
        val eventId = enqueueEvent("corr-outbox-fail")

        outboxWorkerPort.claim("worker-a", OutboxDestination.PLATFORM, 10)
        outboxWorkerPort.fail(
            eventId = eventId,
            workerId = "worker-a",
            errorMessage = "provider unavailable",
            retryDelay = Duration.ofSeconds(30),
        )

        val row = jdbcTemplate.queryForMap(
            "SELECT status, locked_by, error_message, failed_at FROM outbox_events WHERE id = ?",
            eventId,
        )

        assertEquals("failed", row["status"])
        assertEquals(null, row["locked_by"])
        assertEquals("provider unavailable", row["error_message"])
        assertNotNull(row["failed_at"])
    }

    @Test
    fun movesLockedEventToDeadLetter() {
        val eventId = enqueueEvent("corr-outbox-dead-letter")

        outboxWorkerPort.claim("worker-a", OutboxDestination.PLATFORM, 10)
        outboxWorkerPort.deadLetter(eventId, "worker-a", "poison event")

        val row = jdbcTemplate.queryForMap(
            "SELECT status, locked_by, error_message FROM outbox_events WHERE id = ?",
            eventId,
        )

        assertEquals("dead_letter", row["status"])
        assertEquals(null, row["locked_by"])
        assertEquals("poison event", row["error_message"])
    }

    @Test
    fun reclaimsStaleLockedEvent() {
        val eventId = enqueueEvent("corr-outbox-reclaim")

        outboxWorkerPort.claim("worker-a", OutboxDestination.PLATFORM, 10)
        jdbcTemplate.update(
            """
            UPDATE outbox_events
            SET locked_at = now() - interval '30 minutes'
            WHERE id = ?
            """.trimIndent(),
            eventId,
        )

        val reclaimed = outboxWorkerPort.reclaimStale(
            lockedBefore = Instant.now().minus(Duration.ofMinutes(15)),
            limit = 10,
        )

        assertEquals(1, reclaimed)

        val row = jdbcTemplate.queryForMap(
            "SELECT status, locked_by, error_message FROM outbox_events WHERE id = ?",
            eventId,
        )

        assertEquals("failed", row["status"])
        assertEquals(null, row["locked_by"])
        assertEquals("Reclaimed after stale worker lock", row["error_message"])
    }

    private fun enqueueEvent(correlationId: String): UUID {
        return requireNotNull(
            transactionTemplate.execute {
                requestContextHolder.set(platformContext(correlationId))
                outboxPort.enqueue(command(UUID.randomUUID()))
            },
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

    private fun command(aggregateId: UUID): OutboxEventCommand {
        return OutboxEventCommand(
            aggregateType = "tenants",
            aggregateId = aggregateId,
            eventType = "tenant.created",
            destination = OutboxDestination.PLATFORM,
            payload = mapOf("slug" to "peak"),
            headers = mapOf("schema_version" to 1),
            priority = 3,
            maxAttempts = 4,
        )
    }
}
