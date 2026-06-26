package com.mwombeki.peak.realtime.internal.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.realtime.websocket")
data class RealtimeWebSocketProperties(
    val allowedOrigins: List<String> = emptyList(),
) {
    val cleanedAllowedOrigins: List<String> = allowedOrigins
        .map { it.trim() }
        .filter { it.isNotBlank() }

    init {
        require(cleanedAllowedOrigins.none { it == "*" }) {
            "peak.realtime.websocket.allowed-origins must not contain wildcard origins"
        }
    }
}
