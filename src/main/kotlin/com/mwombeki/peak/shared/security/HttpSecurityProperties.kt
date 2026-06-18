package com.mwombeki.peak.shared.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.security.http")
data class HttpSecurityProperties(
    val jwt: Jwt = Jwt(),
    val cors: Cors = Cors(),
    val publicPaths: List<String> = listOf(
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        "/error",
    ),
) {
    data class Jwt(
        val enabled: Boolean = false,
        val issuerUri: String? = null,
        val audience: String? = null,
    )

    data class Cors(
        val allowedOrigins: List<String> = emptyList(),
        val allowedMethods: List<String> = listOf(
            "GET",
            "POST",
            "PUT",
            "PATCH",
            "DELETE",
            "OPTIONS",
        ),
        val allowedHeaders: List<String> = listOf(
            "Authorization",
            "Content-Type",
            "Idempotency-Key",
            "X-Correlation-Id",
            "X-Peak-Public-Tenant-Id",
            "X-Peak-Public-Property-Id",
        ),
        val exposedHeaders: List<String> = listOf("X-Correlation-Id"),
        val maxAgeSeconds: Long = 3600,
    )
}
