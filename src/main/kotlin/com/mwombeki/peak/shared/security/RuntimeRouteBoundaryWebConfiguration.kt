package com.mwombeki.peak.shared.security

import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class RuntimeRouteBoundaryWebConfiguration(
    private val runtimeRouteBoundaryInterceptor: RuntimeRouteBoundaryInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(runtimeRouteBoundaryInterceptor)
            .addPathPatterns("/api", "/api/**")
            .order(Ordered.HIGHEST_PRECEDENCE + 50)
    }
}
