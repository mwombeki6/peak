package com.mwombeki.peak.reliability.internal

import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxWorkerPort
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class OutboxWorkerProcessor(
    private val outboxWorkerPort: OutboxWorkerPort,
    private val dispatcher: OutboxEventDispatcher,
    private val properties: OutboxWorkerProperties,
    private val workerIdProvider: OutboxWorkerIdProvider,
    private val clock: Clock,
) {
    suspend fun processBatch(
        destination: OutboxDestination? = null,
    ): OutboxWorkerBatchResult {
        val workerId = workerIdProvider.workerId()
        val claimed = outboxWorkerPort.claim(
            workerId = workerId,
            destination = destination,
            limit = properties.batchSize,
        )

        if (claimed.isEmpty()) {
            return OutboxWorkerBatchResult.empty(destination)
        }

        val semaphore = Semaphore(properties.maxParallelism)
        val eventResults = coroutineScope {
            claimed.map { event ->
                async {
                    semaphore.withPermit {
                        processClaimedEvent(event, workerId)
                    }
                }
            }.awaitAll()
        }

        return OutboxWorkerBatchResult.from(
            destination = destination,
            claimed = claimed.size,
            eventResults = eventResults,
        )
    }

    fun processBatchBlocking(
        destination: OutboxDestination? = null,
    ): OutboxWorkerBatchResult {
        return runBlocking {
            processBatch(destination)
        }
    }

    fun reclaimStaleLocks(): Int {
        if (!properties.reclaimStaleLocks) {
            return 0
        }

        return outboxWorkerPort.reclaimStale(
            lockedBefore = Instant.now(clock).minus(properties.staleLockTimeout),
            limit = properties.staleReclaimLimit,
        )
    }

    private suspend fun processClaimedEvent(
        event: ClaimedOutboxEvent,
        workerId: String,
    ): OutboxWorkerEventResult {
        return try {
            dispatcher.dispatch(event, properties.deliveryTimeout)
            outboxWorkerPort.complete(event.id, workerId)
            logger.info(
                "Delivered outbox event {} destination={} eventType={}",
                event.id,
                event.destination.databaseValue,
                event.eventType,
            )
            OutboxWorkerEventResult(event.id, OutboxWorkerEventOutcome.DELIVERED)
        } catch (ex: NoOutboxEventHandlerException) {
            deadLetter(event, workerId, ex)
        } catch (ex: AmbiguousOutboxEventHandlerException) {
            deadLetter(event, workerId, ex)
        } catch (ex: TimeoutCancellationException) {
            failWithRetry(event, workerId, ex)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            failWithRetry(event, workerId, ex)
        }
    }

    private fun deadLetter(
        event: ClaimedOutboxEvent,
        workerId: String,
        ex: Exception,
    ): OutboxWorkerEventResult {
        val message = failureMessage(event, ex)
        outboxWorkerPort.deadLetter(event.id, workerId, message)
        logger.error(
            "Dead-lettered outbox event {} destination={} eventType={} reason={}",
            event.id,
            event.destination.databaseValue,
            event.eventType,
            message,
        )
        return OutboxWorkerEventResult(event.id, OutboxWorkerEventOutcome.DEAD_LETTERED)
    }

    private fun failWithRetry(
        event: ClaimedOutboxEvent,
        workerId: String,
        ex: Exception,
    ): OutboxWorkerEventResult {
        val retryDelay = retryDelay(event)
        val message = failureMessage(event, ex)
        outboxWorkerPort.fail(
            eventId = event.id,
            workerId = workerId,
            errorMessage = message,
            retryDelay = retryDelay,
        )

        val outcome = if (event.attemptCount >= event.maxAttempts) {
            OutboxWorkerEventOutcome.DEAD_LETTERED
        } else {
            OutboxWorkerEventOutcome.FAILED
        }

        logger.warn(
            "Failed outbox event {} destination={} eventType={} attempt={}/{} " +
                    "retryDelay={} outcome={} reason={}",
            event.id,
            event.destination.databaseValue,
            event.eventType,
            event.attemptCount,
            event.maxAttempts,
            retryDelay,
            outcome,
            message,
        )

        return OutboxWorkerEventResult(event.id, outcome)
    }

    private fun retryDelay(event: ClaimedOutboxEvent): Duration {
        var delay = properties.retryInitialDelay
        repeat((event.attemptCount - 1).coerceAtLeast(0)) {
            delay = try {
                delay.multipliedBy(2)
            } catch (ex: ArithmeticException) {
                return properties.retryMaxDelay
            }
            if (delay >= properties.retryMaxDelay) {
                return properties.retryMaxDelay
            }
        }
        return if (delay > properties.retryMaxDelay) {
            properties.retryMaxDelay
        } else {
            delay
        }
    }

    private fun failureMessage(
        event: ClaimedOutboxEvent,
        ex: Exception,
    ): String {
        val reason = ex.message?.takeIf { it.isNotBlank() }
            ?: ex::class.simpleName
            ?: "Unknown outbox delivery failure"
        return "Outbox event ${event.id} delivery failed for ${event.eventType}: $reason"
            .take(MAX_ERROR_MESSAGE_LENGTH)
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(OutboxWorkerProcessor::class.java)
        private const val MAX_ERROR_MESSAGE_LENGTH = 1000
    }
}

data class OutboxWorkerBatchResult(
    val destination: OutboxDestination?,
    val claimed: Int,
    val delivered: Int,
    val failed: Int,
    val deadLettered: Int,
) {
    companion object {
        fun empty(destination: OutboxDestination?): OutboxWorkerBatchResult {
            return OutboxWorkerBatchResult(
                destination = destination,
                claimed = 0,
                delivered = 0,
                failed = 0,
                deadLettered = 0,
            )
        }

        fun from(
            destination: OutboxDestination?,
            claimed: Int,
            eventResults: List<OutboxWorkerEventResult>,
        ): OutboxWorkerBatchResult {
            return OutboxWorkerBatchResult(
                destination = destination,
                claimed = claimed,
                delivered = eventResults.count {
                    it.outcome == OutboxWorkerEventOutcome.DELIVERED
                },
                failed = eventResults.count {
                    it.outcome == OutboxWorkerEventOutcome.FAILED
                },
                deadLettered = eventResults.count {
                    it.outcome == OutboxWorkerEventOutcome.DEAD_LETTERED
                },
            )
        }
    }
}

data class OutboxWorkerEventResult(
    val eventId: UUID,
    val outcome: OutboxWorkerEventOutcome,
)

enum class OutboxWorkerEventOutcome {
    DELIVERED,
    FAILED,
    DEAD_LETTERED,
}
