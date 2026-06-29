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
            requireTrue(!requestContextProperties.allowTrustedJwtIdentityClaims) {
                "peak.security.request-context.allow-trusted-jwt-identity-claims must be false in prod"
            }
            requireTrue(!springDocEnabled("springdoc.api-docs.enabled")) {
                "springdoc.api-docs.enabled must be false in prod"
            }
            requireTrue(!springDocEnabled("springdoc.swagger-ui.enabled")) {
                "springdoc.swagger-ui.enabled must be false in prod"
            }
            validateDatasource()
            validateFlyway()
            validateRuntimeTopology()
            validateCommunicationProviders()
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

        if (runtimeProperties.mode !in setOf(
                PeakRuntimeMode.MIGRATION,
                PeakRuntimeMode.BOOTSTRAP,
            )
        ) {
            requireTrue(username != LOCAL_MIGRATOR_USER) {
                "API/worker runtime must not use the migrator database role in prod"
            }
        }
    }

    private fun MutableList<String>.validateFlyway() {
        val flywayEnabled = environment.getProperty("spring.flyway.enabled", Boolean::class.java, true)
        when (runtimeProperties.mode) {
            PeakRuntimeMode.MIGRATION -> requireTrue(flywayEnabled) {
                "spring.flyway.enabled must be true for migration runtime in prod"
            }
            else -> requireTrue(!flywayEnabled) {
                "spring.flyway.enabled must be false for API/worker runtime in prod"
            }
        }
    }

    private fun MutableList<String>.validateRuntimeTopology() {
        val webApplicationType = environment.getProperty("spring.main.web-application-type")
            ?.trim()
            ?.lowercase()
        val outboxWorkerEnabled = environment.getProperty(
            "peak.reliability.outbox.worker.enabled",
            Boolean::class.java,
            false,
        )

        when (runtimeProperties.mode) {
            PeakRuntimeMode.API -> {
                requireTrue(!outboxWorkerEnabled) {
                    "peak.reliability.outbox.worker.enabled must be false for API runtime in prod"
                }
                requireTrue(webApplicationType != WEB_APPLICATION_TYPE_NONE) {
                    "spring.main.web-application-type must not be none for API runtime in prod"
                }
                validateRealtimeWebSocketOrigins()
            }

            PeakRuntimeMode.WORKER -> {
                requireTrue(outboxWorkerEnabled) {
                    "peak.reliability.outbox.worker.enabled must be true for worker runtime in prod"
                }
                requireTrue(webApplicationType == WEB_APPLICATION_TYPE_NONE) {
                    "spring.main.web-application-type must be none for worker runtime in prod"
                }
            }

            PeakRuntimeMode.MIGRATION -> {
                requireTrue(!outboxWorkerEnabled) {
                    "peak.reliability.outbox.worker.enabled must be false for migration runtime in prod"
                }
                requireTrue(webApplicationType == WEB_APPLICATION_TYPE_NONE) {
                    "spring.main.web-application-type must be none for migration runtime in prod"
                }
            }

            PeakRuntimeMode.BOOTSTRAP -> {
                requireTrue(!outboxWorkerEnabled) {
                    "peak.reliability.outbox.worker.enabled must be false for bootstrap runtime in prod"
                }
                requireTrue(webApplicationType == WEB_APPLICATION_TYPE_NONE) {
                    "spring.main.web-application-type must be none for bootstrap runtime in prod"
                }
                requireTrue(
                    environment.getProperty(
                        "peak.bootstrap.platform.enabled",
                        Boolean::class.java,
                        false,
                    ),
                ) {
                    "peak.bootstrap.platform.enabled must be true for bootstrap runtime"
                }
                listOf(
                    "peak.bootstrap.platform.full-name",
                    "peak.bootstrap.platform.email",
                    "peak.bootstrap.platform.issuer",
                    "peak.bootstrap.platform.subject",
                ).forEach { property ->
                    requirePresent(environment.getProperty(property)) {
                        "$property is required for bootstrap runtime"
                    }
                }
            }
        }
    }

    private fun MutableList<String>.validateRealtimeWebSocketOrigins() {
        val origins = configuredList("peak.realtime.websocket.allowed-origins")
        requireTrue(origins.isNotEmpty()) {
            "peak.realtime.websocket.allowed-origins must be explicit in prod"
        }
        requireTrue(origins.none { it == "*" }) {
            "peak.realtime.websocket.allowed-origins must not include wildcard origins in prod"
        }
    }

    private fun MutableList<String>.validateCommunicationProviders() {
        val localProviderEnabled = environment.getProperty(
            "peak.communication.delivery.local-provider.enabled",
            Boolean::class.java,
            true,
        )
        requireTrue(!localProviderEnabled) {
            "peak.communication.delivery.local-provider.enabled must be false in prod"
        }

        if (runtimeProperties.mode != PeakRuntimeMode.WORKER) {
            return
        }

        val httpProviderEnabled = environment.getProperty(
            "peak.communication.delivery.http-provider.enabled",
            Boolean::class.java,
            false,
        )
        val baseUrl = environment.getProperty("peak.communication.delivery.http-provider.base-url")
        val apiKey = environment.getProperty("peak.communication.delivery.http-provider.api-key")
        val acceptanceProfile = environment.activeProfiles.contains(ACCEPTANCE_PROFILE)

        requireTrue(httpProviderEnabled) {
            "peak.communication.delivery.http-provider.enabled must be true for prod worker runtime"
        }
        requirePresent(baseUrl) {
            "peak.communication.delivery.http-provider.base-url is required for prod worker runtime"
        }
        requireTrue(baseUrl?.startsWith("https://") == true || acceptanceProfile) {
            "peak.communication.delivery.http-provider.base-url must use https in prod"
        }
        requirePresent(apiKey) {
            "peak.communication.delivery.http-provider.api-key is required for prod worker runtime"
        }
        requireTrue(apiKey != "change-me" && apiKey?.contains("CHANGE_ME") != true) {
            "peak.communication.delivery.http-provider.api-key must not use a placeholder"
        }
    }

    private fun String?.isDefaultLocalSecret(): Boolean {
        return this == LOCAL_MIGRATOR_USER || this == "peak_app" || this == "peak_worker"
    }

    private fun springDocEnabled(property: String): Boolean {
        return environment.getProperty(property, Boolean::class.java, true)
    }

    private fun configuredList(property: String): List<String> {
        val direct = environment.getProperty(property)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val indexed = (0..50).mapNotNull { index ->
            environment.getProperty("$property[$index]")?.trim()?.takeIf { it.isNotBlank() }
        }
        return (direct + indexed).distinct()
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
        const val ACCEPTANCE_PROFILE = "acceptance"
        const val LOCAL_MIGRATOR_USER = "peak_migrator"
        const val WEB_APPLICATION_TYPE_NONE = "none"
    }
}
