package com.mwombeki.peak.shared.context

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.security.request-context")
data class RequestContextProperties(
    val allowHeaderIdentity: Boolean = false,
)
