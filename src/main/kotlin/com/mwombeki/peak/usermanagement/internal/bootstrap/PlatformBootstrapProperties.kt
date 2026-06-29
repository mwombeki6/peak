package com.mwombeki.peak.usermanagement.internal.bootstrap

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.bootstrap.platform")
data class PlatformBootstrapProperties(
    val enabled: Boolean = false,
    val fullName: String? = null,
    val email: String? = null,
    val issuer: String? = null,
    val subject: String? = null,
)
