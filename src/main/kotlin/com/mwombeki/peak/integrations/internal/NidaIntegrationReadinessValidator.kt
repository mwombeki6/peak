package com.mwombeki.peak.integrations.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class NidaIntegrationReadinessValidator(
    private val environment: Environment,
    private val properties: NidaIntegrationProperties,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        if (!environment.activeProfiles.contains("prod")) {
            return
        }
        require(properties.mode != NidaMode.SIMULATOR) {
            "NIDA simulator mode is prohibited in production"
        }
        if (properties.mode == NidaMode.CIG) {
            error("NIDA CIG mode cannot be enabled until the official private wire contract is implemented")
        }
    }
}
