package com.mwombeki.peak.usermanagement.internal.web

import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class RouteGuardWebConfiguration(
    private val routeGuardInterceptor: RouteGuardInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(routeGuardInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns(
                "/api-docs/**",
                "/swagger-ui/**",
                "/v3/api-docs/**",
                "/error",
            )
            .order(Ordered.HIGHEST_PRECEDENCE + 100)
    }
}
