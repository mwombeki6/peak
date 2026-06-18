package com.mwombeki.peak.shared.config

import com.mwombeki.peak.shared.context.RequestContextProperties
import com.mwombeki.peak.shared.security.HttpSecurityProperties
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class ProductionReadinessValidator(
    private val environment: Environment,
    private val runtimeProperties: PeakRuntimeProperties,
    private val httpSecurityProperties: HttpSecurityProperties,
    private val requestContextProperties: RequestContextProperties,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        if (!environment.activeProfiles.contains(PROD_PROFILE)) {
            return
        }

        val violations = buildList {
            requireTrue(httpSecurityProperties.jwt.enabled) {
                "peak.security.http.jwt.enabled must be true in prod"
            }
            requirePresent(httpSecurityProperties.jwt.issuerUri) {
                "peak.security.http.jwt.issuer-uri is required in prod"
            }
            requirePresent(httpSecurityProperties.jwt.audience) {
                "peak.security.http.jwt.audience is required in prod"
            }
            requireTrue(httpSecurityProperties.cors.allowedOrigins.any { it.isNotBlank() }) {
                "peak.security.http.cors.allowed-origins must be explicit in prod"
            }
            requireTrue(!requestContextProperties.allowHeaderIdentity) {
                "peak.security.request-context.allow-header-identity must be false in prod"
            }
            requireTrue(!springDocEnabled("springdoc.api-docs.enabled")) {
                "springdoc.api-docs.enabled must be false in prod"
            }
            requireTrue(!springDocEnabled("springdoc.swagger-ui.enabled")) {
                "springdoc.swagger-ui.enabled must be false in prod"
            }
            validateDatasource()
        }

        if (violations.isNotEmpty()) {
            error(
                "Production readiness validation failed: " +
                        violations.joinToString("; "),
            )
        }
    }

    private fun MutableList<String>.validateDatasource() {
        val username = environment.getProperty("spring.datasource.username")
        val password = environment.getProperty("spring.datasource.password")

        requirePresent(username) {
            "spring.datasource.username is required in prod"
        }
        requirePresent(password) {
            "spring.datasource.password is required in prod"
        }
        requireTrue(!password.isDefaultLocalSecret()) {
            "spring.datasource.password must not use the local development default in prod"
        }

        if (runtimeProperties.mode != PeakRuntimeMode.MIGRATION) {
            requireTrue(username != LOCAL_MIGRATOR_USER) {
                "API/worker runtime must not use the migrator database role in prod"
            }
        }
    }

    private fun String?.isDefaultLocalSecret(): Boolean {
        return this == LOCAL_MIGRATOR_USER || this == "peak_app" || this == "peak_worker"
    }

    private fun springDocEnabled(property: String): Boolean {
        return environment.getProperty(property, Boolean::class.java, true)
    }

    private fun MutableList<String>.requirePresent(
        value: String?,
        message: () -> String,
    ) {
        requireTrue(!value.isNullOrBlank(), message)
    }

    private fun MutableList<String>.requireTrue(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) {
            add(message())
        }
    }

    private companion object {
        const val PROD_PROFILE = "prod"
        const val LOCAL_MIGRATOR_USER = "peak_migrator"
    }
}
