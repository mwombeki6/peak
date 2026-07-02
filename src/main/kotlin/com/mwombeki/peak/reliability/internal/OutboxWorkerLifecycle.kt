package com.mwombeki.peak.reliability.internal

import com.mwombeki.peak.reliability.api.OutboxDestination
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component

@Component
class OutboxWorkerLifecycle(
    private val properties: OutboxWorkerProperties,
    @Value("\${peak.runtime.mode:api}")
    private val runtimeMode: String,
    private val processor: OutboxWorkerProcessor,
    private val idempotencyMaintenance: IdempotencyMaintenance,
    private val workerHeartbeat: WorkerHeartbeat,
) : SmartLifecycle {
    private val running = AtomicBoolean(false)
    private var supervisorJob: Job? = null

    override fun start() {
        if (!shouldRun()) {
            return
        }
        if (!running.compareAndSet(false, true)) {
            return
        }

        val destinations = destinationsToPoll()
        if (destinations.isEmpty()) {
            logger.warn("Outbox worker is enabled but no outbox handlers are registered")
            running.set(false)
            return
        }

        try {
            workerHeartbeat.started(destinations)
        } catch (ex: Exception) {
            running.set(false)
            throw ex
        }

        val job = SupervisorJob()
        supervisorJob = job
        val scope = CoroutineScope(
            job + Dispatchers.IO.limitedParallelism(properties.maxParallelism),
        )

        destinations.forEach { destination ->
            scope.launch(CoroutineName("outbox-worker-${destination.databaseValue}")) {
                pollLoop(destination)
            }
        }

        scope.launch(CoroutineName("outbox-worker-heartbeat")) {
            heartbeatLoop(destinations)
        }

        if (properties.reclaimStaleLocks) {
            scope.launch(CoroutineName("outbox-stale-lock-reclaimer")) {
                staleLockReclaimLoop()
            }
        }

        logger.info(
            "Started outbox worker for destinations={}",
            destinations.joinToString(",") { it.databaseValue },
        )
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }

        supervisorJob?.let { job ->
            runBlocking {
                job.cancelAndJoin()
            }
        }
        supervisorJob = null
        workerHeartbeat.stopped()
        logger.info("Stopped outbox worker")
    }

    override fun stop(callback: Runnable) {
        stop()
        callback.run()
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE

    private suspend fun pollLoop(destination: OutboxDestination) {
        while (currentCoroutineContext().isActive) {
            val delayFor = try {
                val result = processor.processBatch(destination)
                if (result.claimed == 0) {
                    properties.idlePollInterval
                } else {
                    properties.pollInterval
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logger.error(
                    "Outbox worker polling failed for destination={}",
                    destination.databaseValue,
                    ex,
                )
                properties.idlePollInterval
            }

            delay(delayFor.toKotlinDuration())
        }
    }

    private suspend fun staleLockReclaimLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val reclaimed = processor.reclaimStaleLocks()
                if (reclaimed > 0) {
                    logger.warn("Reclaimed {} stale outbox worker locks", reclaimed)
                }
                idempotencyMaintenance.run()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logger.error("Outbox stale-lock reclaim failed", ex)
            }

            delay(properties.staleReclaimInterval.toKotlinDuration())
        }
    }

    private suspend fun heartbeatLoop(destinations: List<OutboxDestination>) {
        while (currentCoroutineContext().isActive) {
            try {
                workerHeartbeat.alive(destinations)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logger.error("Outbox worker heartbeat failed", ex)
            }
            delay(properties.heartbeatInterval.toKotlinDuration())
        }
    }

    private fun shouldRun(): Boolean {
        return properties.enabled && runtimeMode.equals(WORKER_RUNTIME_MODE, ignoreCase = true)
    }

    private fun destinationsToPoll(): List<OutboxDestination> {
        return properties.destinations.ifEmpty {
            OutboxDestination.entries
        }.distinct()
    }

    private companion object {
        const val WORKER_RUNTIME_MODE = "worker"
        private val logger = LoggerFactory.getLogger(OutboxWorkerLifecycle::class.java)
    }
}
