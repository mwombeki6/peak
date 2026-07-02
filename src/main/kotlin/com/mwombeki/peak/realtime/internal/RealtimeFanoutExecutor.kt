package com.mwombeki.peak.realtime.internal

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "peak.realtime.fanout")
data class RealtimeFanoutProperties(
    val parallelism: Int = 4,
    val queueCapacityPerShard: Int = 500,
) {
    init {
        require(parallelism in 1..32)
        require(queueCapacityPerShard in 1..10_000)
    }
}

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RealtimeFanoutExecutor(
    properties: RealtimeFanoutProperties,
    private val meterRegistry: MeterRegistry,
) {
    private val executors = List(properties.parallelism) { shard ->
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(properties.queueCapacityPerShard),
            FanoutThreadFactory(shard),
        ) { task, executor ->
            meterRegistry.counter("peak.realtime.fanout.backpressure.applied").increment()
            if (executor.isShutdown) {
                throw RejectedExecutionException("Realtime fanout executor is shutting down")
            }
            try {
                executor.queue.put(task)
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RejectedExecutionException(
                    "Interrupted while applying realtime fanout backpressure",
                    ex,
                )
            }
        }
    }

    fun execute(event: StoredRealtimeEvent, task: () -> Unit) {
        val shard = Math.floorMod(
            31 * event.tenantId.hashCode() + event.propertyId.hashCode(),
            executors.size,
        )
        executors[shard].execute(task)
    }

    @PreDestroy
    fun shutdown() {
        executors.forEach(ThreadPoolExecutor::shutdown)
        executors.forEach { executor ->
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        }
    }

    private class FanoutThreadFactory(
        private val shard: Int,
    ) : ThreadFactory {
        private val sequence = AtomicInteger(0)

        override fun newThread(task: Runnable): Thread {
            return Thread.ofPlatform()
                .daemon(true)
                .name("realtime-fanout-$shard-${sequence.incrementAndGet()}")
                .unstarted(task)
        }
    }

    private companion object {
        const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
