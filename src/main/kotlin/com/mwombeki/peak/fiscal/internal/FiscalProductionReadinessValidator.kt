package com.mwombeki.peak.fiscal.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class FiscalProductionReadinessValidator(
    private val environment: Environment,
    private val jdbcTemplate: JdbcTemplate,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        if (!environment.activeProfiles.contains("prod")) {
            return
        }
        check(
            !environment.getProperty(
                "peak.fiscal.providers.contract-mock.enabled",
                Boolean::class.java,
                true,
            ),
        ) {
            "peak.fiscal.providers.contract-mock.enabled must be false in prod"
        }
        if (environment.runtimeMode() in setOf("api", "worker")) {
            val activeMocks = jdbcTemplate.queryForObject(
                "SELECT fiscal_config_count FROM active_contract_mock_provider_counts()",
                Long::class.java,
            ) ?: 0L
            check(activeMocks == 0L) {
                "Active contract_mock fiscal provider configurations are forbidden in prod"
            }
        }
    }

    private fun Environment.runtimeMode(): String {
        return getProperty("peak.runtime.mode", "api").trim().lowercase()
    }
}
