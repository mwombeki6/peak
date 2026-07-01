package com.mwombeki.peak.reservations.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class GuestIdentityReadinessValidator(
    private val environment: Environment,
    private val properties: GuestIdentityProperties,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        if (!environment.activeProfiles.contains("prod")) {
            return
        }
        if (environment.getProperty("peak.runtime.mode", "api").lowercase() != "api") {
            return
        }
        require(properties.hashKey.length >= 32 && !properties.hashKey.isPlaceholder()) {
            "PEAK_GUEST_IDENTITY_HASH_KEY must contain a non-placeholder secret of at least 32 characters"
        }
        require(properties.hashKeyVersion.isNotBlank()) {
            "Guest identity hash key version is required"
        }
        val previousKeyConfigured = properties.previousHashKey.isNotBlank()
        val previousVersionConfigured = properties.previousHashKeyVersion.isNotBlank()
        require(previousKeyConfigured == previousVersionConfigured) {
            "Previous guest identity hash key and version must be configured together"
        }
        if (previousKeyConfigured) {
            require(properties.previousHashKey.length >= 32 && !properties.previousHashKey.isPlaceholder()) {
                "Previous guest identity hash key must be a non-placeholder secret of at least 32 characters"
            }
            require(properties.previousHashKeyVersion != properties.hashKeyVersion) {
                "Current and previous guest identity hash key versions must differ"
            }
        }
    }

    private fun String.isPlaceholder(): Boolean {
        val normalized = lowercase()
        return normalized.contains("change-me") ||
                normalized.contains("development") ||
                normalized.contains("placeholder")
    }
}
