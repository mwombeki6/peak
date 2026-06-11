package com.mwombeki.peak.shared.context.web

import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class RequestContextWebConfiguration(
    private val requestContextInterceptor: RequestContextInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(requestContextInterceptor)
            .addPathPatterns("/**")
            .order(Ordered.HIGHEST_PRECEDENCE)
    }
}
