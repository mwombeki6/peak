package com.mwombeki.peak.usermanagement.internal.web

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.security.route-guard")
data class RouteGuardProperties(
    val enabled: Boolean = true,
    val denyUnregisteredApiRoutes: Boolean = true,
)
