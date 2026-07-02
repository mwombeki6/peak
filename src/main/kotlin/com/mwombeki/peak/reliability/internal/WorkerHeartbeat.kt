package com.mwombeki.peak.reliability.internal

import com.mwombeki.peak.reliability.api.OutboxDestination
import java.sql.Timestamp
import java.time.Instant
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class WorkerHeartbeat(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val workerIdProvider: OutboxWorkerIdProvider,
) {
    fun started(destinations: List<OutboxDestination>) {
        upsert("running", destinations)
    }

    fun alive(destinations: List<OutboxDestination>) {
        upsert("running", destinations)
        jdbcTemplate.update(
            "DELETE FROM worker_runtime_heartbeats WHERE last_seen_at < now() - interval '7 days'",
        )
    }

    fun stopped() {
        runCatching {
            jdbcTemplate.update(
                """
                UPDATE worker_runtime_heartbeats
                SET status = 'stopped', last_seen_at = now()
                WHERE worker_id = ?
                """.trimIndent(),
                workerIdProvider.workerId(),
            )
        }
    }

    private fun upsert(
        status: String,
        destinations: List<OutboxDestination>,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO worker_runtime_heartbeats (
                worker_id, status, metadata, started_at, last_seen_at
            )
            VALUES (?, ?, ?::jsonb, now(), now())
            ON CONFLICT (worker_id)
            DO UPDATE SET
                status = EXCLUDED.status,
                metadata = EXCLUDED.metadata,
                last_seen_at = now()
            """.trimIndent(),
            workerIdProvider.workerId(),
            status,
            objectMapper.writeValueAsString(
                mapOf("destinations" to destinations.map { it.databaseValue }),
            ),
        )
    }
}

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
    prefix = "peak.reliability.outbox.worker",
    name = ["health-required"],
    havingValue = "true",
)
class WorkerRuntimeHealthIndicator(
    private val jdbcTemplate: JdbcTemplate,
    private val properties: OutboxWorkerProperties,
) : HealthIndicator {
    override fun health(): Health {
        val cutoff = Timestamp.from(Instant.now().minus(properties.heartbeatStaleAfter))
        val active = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM worker_runtime_heartbeats
            WHERE status = 'running' AND last_seen_at >= ?
            """.trimIndent(),
            Int::class.java,
            cutoff,
        ) ?: 0
        return if (active > 0) {
            Health.up().withDetail("activeWorkers", active).build()
        } else {
            Health.down().withDetail("reason", "no_recent_worker_heartbeat").build()
        }
    }
}
