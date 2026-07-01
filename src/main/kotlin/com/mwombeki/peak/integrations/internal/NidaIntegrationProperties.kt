package com.mwombeki.peak.integrations.internal

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.integrations.nida")
data class NidaIntegrationProperties(
    val mode: NidaMode = NidaMode.DISABLED,
    val baseUrl: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val readTimeout: Duration = Duration.ofSeconds(8),
)

enum class NidaMode {
    DISABLED,
    SIMULATOR,
    CIG,
}
