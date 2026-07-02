package com.mwombeki.peak.payment.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class PaymentProductionReadinessValidator(
    private val environment: Environment,
    private val jdbcTemplate: JdbcTemplate,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        if (!environment.activeProfiles.contains("prod")) {
            return
        }
        check(
            !environment.getProperty(
                "peak.payment.providers.contract-mock.enabled",
                Boolean::class.java,
                true,
            ),
        ) {
            "peak.payment.providers.contract-mock.enabled must be false in prod"
        }
        if (environment.runtimeMode() in setOf("api", "worker")) {
            val activeMocks = jdbcTemplate.queryForObject(
                "SELECT payment_account_count FROM active_contract_mock_provider_counts()",
                Long::class.java,
            ) ?: 0L
            check(activeMocks == 0L) {
                "Active contract_mock payment provider accounts are forbidden in prod"
            }
            val approvedCodes = environment.getProperty(
                "peak.payment.production-approved-provider-codes",
                "",
            )
            val unsafe = jdbcTemplate.queryForObject(
                """
                SELECT unsafe_payment_account_count
                FROM production_provider_readiness_counts(
                    string_to_array(?, ','),
                    ARRAY[]::text[]
                )
                """.trimIndent(),
                Long::class.java,
                approvedCodes,
            ) ?: 0L
            check(unsafe == 0L) {
                "Production payment provider accounts require explicit ClickPesa approval, " +
                        "secret references, and sandbox certification"
            }
        }
    }

    private fun Environment.runtimeMode(): String {
        return getProperty("peak.runtime.mode", "api").trim().lowercase()
    }
}
