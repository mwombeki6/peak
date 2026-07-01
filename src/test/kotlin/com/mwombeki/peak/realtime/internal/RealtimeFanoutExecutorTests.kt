package com.mwombeki.peak.realtime.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RealtimeFanoutExecutorTests {
    @Test
    fun `applies bounded backpressure without reordering one property stream`() {
        val meters = SimpleMeterRegistry()
        val executor = RealtimeFanoutExecutor(
            RealtimeFanoutProperties(parallelism = 1, queueCapacityPerShard = 1),
            meters,
        )
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val submitted = CountDownLatch(1)
        val completed = CountDownLatch(3)
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val event = event()

        try {
            executor.execute(event) {
                started.countDown()
                release.await()
                order += 1
                completed.countDown()
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            executor.execute(event) {
                order += 2
                completed.countDown()
            }
            val submitter = Thread.startVirtualThread {
                executor.execute(event) {
                    order += 3
                    completed.countDown()
                }
                submitted.countDown()
            }

            assertFalse(submitted.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(submitted.await(2, TimeUnit.SECONDS))
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            submitter.join()

            assertEquals(listOf(1, 2, 3), order)
            assertEquals(
                1.0,
                meters.counter("peak.realtime.fanout.backpressure.applied").count(),
            )
        } finally {
            release.countDown()
            executor.shutdown()
            meters.close()
        }
    }

    private fun event(): StoredRealtimeEvent {
        return StoredRealtimeEvent(
            sequenceId = 1,
            eventId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            eventType = "property.room.updated",
            payload = emptyMap(),
            createdAt = Instant.now(),
        )
    }
}
