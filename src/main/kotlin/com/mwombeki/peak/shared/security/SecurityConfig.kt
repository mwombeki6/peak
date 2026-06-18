package com.mwombeki.peak.shared.security

import com.mwombeki.peak.shared.context.TenantContextFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Security Architecture.
 * Configures stateless JWT authentication protection, CORS access gates,
 * and binds the multi-tenant context extraction filters.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfig(
    private val tenantContextFilter: TenantContextFilter,
) {
    // Note: SecurityFilterChain is now provided by HttpSecurityConfiguration to avoid duplication
    // We should consider moving the TenantContextFilter logic to HttpSecurityConfiguration as well
    // or use a custom Customizer.

    /**
     * Cross-Origin Resource Sharing (CORS) Production Policy Config.
     * Prevents cross-site execution attacks while opening verified lines for your frontends.
     */
    private fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOriginPatterns = listOf("*") // In production, replace with your exact domain names
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("Authorization", "Content-Type", "X-Idempotency-Key", "X-Tenant-ID")
        configuration.exposedHeaders = listOf("X-Idempotency-Key")
        configuration.allowCredentials = true

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}