package com.mwombeki.peak.shared.config

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import java.util.concurrent.atomic.AtomicLong
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "peak.observability.operational-metrics",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class OperationalMetrics(
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {
    private val values = METRICS.associateWith { AtomicLong() }

    @PostConstruct
    fun register() {
        values.forEach { (name, value) ->
            meterRegistry.gauge(name, value)
        }
    }

    @Scheduled(
        fixedDelayString = "\${peak.observability.operational-metrics.refresh-interval-ms:30000}",
    )
    fun refresh() {
        jdbcTemplate.query(
            "SELECT * FROM phase3_operational_metrics()",
        ) { rs ->
            values.getValue("peak.clickpesa.accounts")
                .set(rs.getLong("clickpesa_accounts"))
            values.getValue("peak.payment.poll.backlog")
                .set(rs.getLong("payment_poll_backlog"))
            values.getValue("peak.payment.webhook.failures")
                .set(rs.getLong("webhook_failures"))
            values.getValue("peak.payment.webhook.replays")
                .set(rs.getLong("webhook_replays"))
            values.getValue("peak.payment.refunds")
                .set(rs.getLong("refund_count"))
            values.getValue("peak.payment.reconciliation.backlog")
                .set(rs.getLong("reconciliation_backlog"))
            values.getValue("peak.fiscal.backlog")
                .set(rs.getLong("fiscal_backlog"))
            values.getValue("peak.pos.variance.backlog")
                .set(rs.getLong("pos_variance_backlog"))
            values.getValue("peak.night_audit.blockers")
                .set(rs.getLong("night_audit_blockers"))
        }
    }

    private companion object {
        val METRICS = setOf(
            "peak.clickpesa.accounts",
            "peak.payment.poll.backlog",
            "peak.payment.webhook.failures",
            "peak.payment.webhook.replays",
            "peak.payment.refunds",
            "peak.payment.reconciliation.backlog",
            "peak.fiscal.backlog",
            "peak.pos.variance.backlog",
            "peak.night_audit.blockers",
        )
    }
}
