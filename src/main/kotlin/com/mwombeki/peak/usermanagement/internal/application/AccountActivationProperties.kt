package com.mwombeki.peak.usermanagement.internal.application

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.usermanagement.activation")
data class AccountActivationProperties(
    val hospitalityIssuer: String = "http://localhost:8081/realms/peak-hospitality",
    val platformIssuer: String = "http://localhost:8081/realms/peak-platform",
    /** Returns the six-digit code in the HTTP body. Forbidden in production. */
    val exposeCodeInResponse: Boolean = false,
)
