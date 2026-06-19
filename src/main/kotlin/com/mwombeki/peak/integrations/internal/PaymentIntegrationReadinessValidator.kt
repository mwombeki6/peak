package com.mwombeki.peak.integrations.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class PaymentIntegrationReadinessValidator(
    private val environment: Environment,
    private val properties: PaymentIntegrationProperties,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        if (!environment.activeProfiles.contains(PROD_PROFILE)) {
            return
        }
        if (runtimeMode() != API_RUNTIME_MODE) {
            return
        }

        val violations = buildList {
            requireTrue(properties.providers.isNotEmpty()) {
                "At least one payment provider must be configured in prod API runtime"
            }
            properties.providers.forEach { (key, provider) ->
                validateProvider(key, provider)
            }
        }

        if (violations.isNotEmpty()) {
            error(
                "Payment integration readiness validation failed: " +
                        violations.joinToString("; "),
            )
        }
    }

    private fun MutableList<String>.validateProvider(
        key: String,
        provider: ProviderConfig,
    ) {
        requireTrue(provider.baseUrl.startsWith("https://")) {
            "Payment provider $key base-url must use https"
        }
        requireTrue(!provider.apiKey.isNullOrBlank()) {
            "Payment provider $key api-key is required in prod"
        }
        requireTrue(!provider.apiSecret.isNullOrBlank()) {
            "Payment provider $key api-secret is required in prod"
        }
        requireTrue(!provider.apiKey.isUnsafePlaceholder()) {
            "Payment provider $key api-key must not use a placeholder"
        }
        requireTrue(!provider.apiSecret.isUnsafePlaceholder()) {
            "Payment provider $key api-secret must not use a placeholder"
        }
    }

    private fun String?.isUnsafePlaceholder(): Boolean {
        if (this.isNullOrBlank()) {
            return false
        }
        val normalized = lowercase()
        return normalized.contains("change-me") ||
                normalized.contains("changeme") ||
                normalized.contains("placeholder") ||
                normalized.contains("secret") && length < 16
    }

    private fun MutableList<String>.requireTrue(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) {
            add(message())
        }
    }

    private fun runtimeMode(): String {
        return environment.getProperty("peak.runtime.mode")
            ?.trim()
            ?.lowercase()
            ?: API_RUNTIME_MODE
    }

    private companion object {
        const val PROD_PROFILE = "prod"
        const val API_RUNTIME_MODE = "api"
    }
}
