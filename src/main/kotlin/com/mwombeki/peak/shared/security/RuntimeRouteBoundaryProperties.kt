package com.mwombeki.peak.shared.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.security.runtime-route-boundary")
data class RuntimeRouteBoundaryProperties(
    val enabled: Boolean = false,
)
