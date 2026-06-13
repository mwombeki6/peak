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
class SecurityConfig(private val tenantContextFilter: TenantContextFilter) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // 1. Disable standard stateful protections since we are a stateless REST API using JWTs
            .csrf { csrf -> csrf.disable() }
            .sessionManagement { session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }

            // 2. Configure CORS policies to safely allow your React app to send requests
            .cors { cors -> cors.configurationSource(corsConfigurationSource()) }

            // 3. Define explicit access path rules
            .authorizeHttpRequests { auth ->
                auth
                    // Allow open public access to your API interactive documentation (Swagger)
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    // Allow open access to public booking engines
                    .requestMatchers("/api/public/**").permitAll()
                    // Every other single path requires a fully verified JWT authentication token
                    .anyRequest().authenticated()
            }

            // 4. Tell Spring Security to expect and validate OAuth2 Bearer JWT Tokens
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { }
            }

            // 5. CRITICAL STEP: Inject our multi-tenant extraction filter immediately after
            // Spring verifies the JWT signature, but before it reaches your business controllers.
            .addFilterAfter(tenantContextFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

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