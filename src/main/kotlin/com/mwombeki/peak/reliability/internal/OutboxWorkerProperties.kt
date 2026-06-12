package com.mwombeki.peak.reliability.internal

import com.mwombeki.peak.reliability.api.OutboxDestination
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.reliability.outbox.worker")
data class OutboxWorkerProperties(
    val enabled: Boolean = false,
    val workerId: String? = null,
    val destinations: List<OutboxDestination> = emptyList(),
    val batchSize: Int = 50,
    val maxParallelism: Int = 4,
    val pollInterval: Duration = Duration.ofSeconds(2),
    val idlePollInterval: Duration = Duration.ofSeconds(10),
    val deliveryTimeout: Duration = Duration.ofSeconds(30),
    val retryInitialDelay: Duration = Duration.ofSeconds(30),
    val retryMaxDelay: Duration = Duration.ofMinutes(15),
    val reclaimStaleLocks: Boolean = true,
    val staleLockTimeout: Duration = Duration.ofMinutes(15),
    val staleReclaimInterval: Duration = Duration.ofMinutes(1),
    val staleReclaimLimit: Int = 500,
) {
    init {
        require(batchSize in 1..500) {
            "Outbox worker batch size must be between 1 and 500"
        }
        require(maxParallelism in 1..128) {
            "Outbox worker max parallelism must be between 1 and 128"
        }
        require(pollInterval.hasPositiveLength()) {
            "Outbox worker poll interval must be positive"
        }
        require(idlePollInterval.hasPositiveLength()) {
            "Outbox worker idle poll interval must be positive"
        }
        require(deliveryTimeout.hasPositiveLength()) {
            "Outbox worker delivery timeout must be positive"
        }
        require(retryInitialDelay.hasPositiveLength()) {
            "Outbox worker retry initial delay must be positive"
        }
        require(retryMaxDelay >= retryInitialDelay) {
            "Outbox worker retry max delay must be greater than or equal to initial delay"
        }
        require(staleLockTimeout.hasPositiveLength()) {
            "Outbox worker stale lock timeout must be positive"
        }
        require(staleReclaimInterval.hasPositiveLength()) {
            "Outbox worker stale reclaim interval must be positive"
        }
        require(staleReclaimLimit in 1..5000) {
            "Outbox worker stale reclaim limit must be between 1 and 5000"
        }
    }

    private fun Duration.hasPositiveLength(): Boolean = !isZero && !isNegative
}
