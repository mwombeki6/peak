package com.mwombeki.peak.integrations.internal

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component("clickPesa")
class ClickPesaHealthIndicator(
    private val jdbcTemplate: JdbcTemplate,
) : HealthIndicator {
    override fun health(): Health {
        return try {
            val state = jdbcTemplate.query(
                """
                SELECT clickpesa_accounts, payment_poll_backlog,
                       webhook_failures
                FROM phase3_operational_metrics()
                """.trimIndent(),
                { rs, _ ->
                    Triple(
                        rs.getLong("clickpesa_accounts"),
                        rs.getLong("payment_poll_backlog"),
                        rs.getLong("webhook_failures"),
                    )
                },
            ).single()
            Health.up()
                .withDetail("configuredAccounts", state.first)
                .withDetail("pollBacklog", state.second)
                .withDetail("webhookFailures", state.third)
                .build()
        } catch (_: Exception) {
            Health.unknown()
                .withDetail("state", "unavailable")
                .build()
        }
    }
}
