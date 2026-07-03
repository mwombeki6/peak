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
        check(
            !environment.getProperty(
                "peak.fiscal.providers.signed-simulator.enabled",
                Boolean::class.java,
                true,
            ),
        ) {
            "peak.fiscal.providers.signed-simulator.enabled must be false in prod"
        }
        if (environment.runtimeMode() in setOf("api", "worker")) {
            val activeMocks = jdbcTemplate.queryForObject(
                "SELECT fiscal_config_count FROM active_contract_mock_provider_counts()",
                Long::class.java,
            ) ?: 0L
            check(activeMocks == 0L) {
                "Active simulator fiscal provider configurations are forbidden in prod"
            }
            val approvedCodes = environment.getProperty(
                "peak.fiscal.production-approved-provider-codes",
                "",
            )
            val unsafe = jdbcTemplate.queryForObject(
                """
                SELECT unsafe_fiscal_config_count
                FROM production_provider_readiness_counts(
                    ARRAY[]::text[],
                    string_to_array(?, ',')
                )
                """.trimIndent(),
                Long::class.java,
                approvedCodes,
            ) ?: 0L
            check(unsafe == 0L) {
                "Production fiscal configurations require an explicitly approved adapter, " +
                        "secret reference, and sandbox certification"
            }
        }
    }

    private fun Environment.runtimeMode(): String {
        return getProperty("peak.runtime.mode", "api").trim().lowercase()
    }
}
