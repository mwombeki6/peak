package com.mwombeki.peak.usermanagement.internal.web

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.security.route-guard")
data class RouteGuardProperties(
    val enabled: Boolean = true,
    val denyUnregisteredApiRoutes: Boolean = true,
    val ruleCacheTtl: Duration = Duration.ofSeconds(30),
    val validateRouteMatrixOnStartup: Boolean = false,
    val startupValidationExclusions: List<String> = listOf(
        "/api-docs/**",
        "/swagger-ui/**",
        "/v3/api-docs/**",
        "/error",
    ),
) {
    init {
        require(!ruleCacheTtl.isNegative && !ruleCacheTtl.isZero) {
            "Route guard rule cache TTL must be positive"
        }
    }
}
