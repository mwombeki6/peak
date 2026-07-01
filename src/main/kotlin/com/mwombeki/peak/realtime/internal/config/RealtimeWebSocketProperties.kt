package com.mwombeki.peak.realtime.internal.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.realtime.websocket")
data class RealtimeWebSocketProperties(
    val allowedOrigins: List<String> = emptyList(),
    val maxConnections: Int = 500,
) {
    val cleanedAllowedOrigins: List<String> = allowedOrigins
        .map { it.trim() }
        .filter { it.isNotBlank() }

    init {
        require(maxConnections in 1..100_000) {
            "peak.realtime.websocket.max-connections must be between 1 and 100000"
        }
        require(cleanedAllowedOrigins.none { it == "*" }) {
            "peak.realtime.websocket.allowed-origins must not contain wildcard origins"
        }
    }
}
