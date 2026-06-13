package com.mwombeki.peak.reliability.internal

import java.net.InetAddress
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class OutboxWorkerIdProvider(
    private val properties: OutboxWorkerProperties,
) {
    private val generatedWorkerId = "peak-${hostname()}-${UUID.randomUUID()}"

    fun workerId(): String {
        return properties.workerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: generatedWorkerId
    }

    private fun hostname(): String {
        return try {
            InetAddress.getLocalHost().hostName
        } catch (ex: Exception) {
            "unknown-host"
        }
    }
}
