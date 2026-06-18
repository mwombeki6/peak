package com.mwombeki.peak.shared.security

import com.mwombeki.peak.shared.context.TenantContextFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
class HttpSecurityConfiguration(
    private val properties: HttpSecurityProperties,
    private val problemWriter: SecurityProblemWriter,
    private val tenantContextFilter: TenantContextFilter,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { csrf -> csrf.disable() }
            .cors { }
            .sessionManagement { session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .securityContext { securityContext ->
                securityContext.requireExplicitSave(false)
            }
            .formLogin { formLogin -> formLogin.disable() }
            .httpBasic { httpBasic -> httpBasic.disable() }
            .logout { logout -> logout.disable() }
            .rememberMe { rememberMe -> rememberMe.disable() }
            .headers { headers ->
                headers.contentSecurityPolicy { csp ->
                    csp.policyDirectives(
                        "default-src 'self'; " +
                                "object-src 'none'; " +
                                "frame-ancestors 'none'; " +
                                "base-uri 'self'; " +
                                "form-action 'self'",
                    )
                }
                headers.frameOptions { frameOptions -> frameOptions.deny() }
                headers.referrerPolicy { referrer ->
                    referrer.policy(
                        ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER,
                    )
                }
                headers.httpStrictTransportSecurity { hsts ->
                    hsts
                        .includeSubDomains(true)
                        .preload(true)
                        .maxAgeInSeconds(31536000)
                }
                headers.permissionsPolicyHeader { permissions ->
                    permissions.policy(
                        "camera=(), microphone=(), geolocation=(), payment=()",
                    )
                }
            }
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint { _, response, _ ->
                    problemWriter.write(
                        response = response,
                        status = HttpStatus.UNAUTHORIZED,
                        title = "Unauthorized",
                        detail = "Authentication is required",
                    )
                }
                exceptions.accessDeniedHandler { _, response, _ ->
                    problemWriter.write(
                        response = response,
                        status = HttpStatus.FORBIDDEN,
                        title = "Forbidden",
                        detail = "Request is not authorized",
                    )
                }
            }
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers(*properties.publicPaths.toTypedArray()).permitAll()
                    .requestMatchers("/api/**").permitAll()
                    .anyRequest().denyAll()
            }

        if (properties.jwt.enabled) {
            http.oauth2ResourceServer { resourceServer ->
                resourceServer.jwt { }
            }
        }

        http.addFilterAfter(tenantContextFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOrigins = properties.cors.allowedOrigins
        configuration.allowedMethods = properties.cors.allowedMethods
        configuration.allowedHeaders = properties.cors.allowedHeaders
        configuration.exposedHeaders = properties.cors.exposedHeaders
        configuration.maxAge = properties.cors.maxAgeSeconds
        configuration.allowCredentials = false

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "peak.security.http.jwt",
        name = ["enabled"],
        havingValue = "true",
    )
    fun jwtDecoder(): JwtDecoder {
        val issuerUri = properties.jwt.issuerUri?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("peak.security.http.jwt.issuer-uri is required when JWT is enabled")
        val audience = properties.jwt.audience?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("peak.security.http.jwt.audience is required when JWT is enabled")

        return NimbusJwtDecoder
            .withIssuerLocation(issuerUri)
            .build()
            .also { decoder ->
                decoder.setJwtValidator(
                    DelegatingOAuth2TokenValidator(
                        JwtValidators.createDefaultWithIssuer(issuerUri),
                        JwtAudienceValidator(audience),
                    ),
                )
            }
    }
}
