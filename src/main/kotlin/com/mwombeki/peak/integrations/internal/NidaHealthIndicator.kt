package com.mwombeki.peak.integrations.internal

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

@Component("nida")
class NidaHealthIndicator(
    private val properties: NidaIntegrationProperties,
) : HealthIndicator {

    override fun health(): Health {
        return when (properties.mode) {
            NidaMode.SIMULATOR -> Health.up()
                .withDetail("mode", "simulator")
                .build()

            NidaMode.DISABLED -> Health.unknown()
                .withDetail("mode", "disabled")
                .withDetail("manualFallbackAvailable", true)
                .build()

            NidaMode.CIG -> Health.unknown()
                .withDetail("mode", "cig")
                .withDetail("status", "contract_pending")
                .build()
        }
    }
}
