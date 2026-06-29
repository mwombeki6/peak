package com.mwombeki.peak.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.runtime")
data class PeakRuntimeProperties(
    val mode: PeakRuntimeMode = PeakRuntimeMode.API,
)

enum class PeakRuntimeMode {
    API,
    WORKER,
    MIGRATION,
    BOOTSTRAP,
}
