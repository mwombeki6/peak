package com.mwombeki.peak.reliability.internal

import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.reliability.api.OutboxStatus
import com.mwombeki.peak.reliability.api.OutboxWorkerPort
import com.mwombeki.peak.shared.context.RequestContextHolder
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper

@Component
class JdbcOutboxPort(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val objectMapper: ObjectMapper,
) : OutboxPort, OutboxWorkerPort {
    override fun enqueue(command: OutboxEventCommand): UUID {
        require(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Outbox events must be enqueued inside an active transaction"
        }

        val context = requestContextHolder.current()
        val id = UUID.randomUUID()
        val headers = command.headers + mapOf(
            "correlation_id" to context.correlationId,
            "request_method" to context.httpMethod,
            "request_path" to context.requestPath,
        ) + context.idempotencyKey?.let { mapOf("idempotency_key" to it) }.orEmpty()

        jdbcTemplate.update(
            """
            INSERT INTO outbox_events (
                id,
                tenant_id,
                property_id,
                aggregate_type,
                aggregate_id,
                event_type,
                destination,
                payload,
                headers,
                correlation_id,
                idempotency_key_id,
                priority,
                max_attempts,
                next_attempt_at
            )
            VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?,
                COALESCE(?, now())
            )
            """.trimIndent(),
            id,
            command.tenantId,
            command.propertyId,
            command.aggregateType,
            command.aggregateId,
            command.eventType,
            command.destination.databaseValue,
            objectMapper.writeValueAsString(command.payload),
            objectMapper.writeValueAsString(headers),
            context.correlationId,
            command.idempotencyKeyId,
            command.priority,
            command.maxAttempts,
            command.availableAt?.let(Timestamp::from),
        )

        return id
    }

    override fun claim(
        workerId: String,
        destination: OutboxDestination?,
        limit: Int,
    ): List<ClaimedOutboxEvent> {
        require(workerId.isNotBlank()) {
            "Outbox worker id is required"
        }
        require(limit in 1..500) {
            "Outbox claim limit must be between 1 and 500"
        }

        return jdbcTemplate.query(
            "SELECT * FROM claim_outbox_events(?, ?, ?)",
            ::mapClaimed,
            workerId,
            destination?.databaseValue,
            limit,
        )
    }

    override fun complete(
        eventId: UUID,
        workerId: String,
    ) {
        jdbcTemplate.queryForList(
            "SELECT complete_outbox_event(?, ?)",
            eventId,
            workerId,
        )
    }

    override fun fail(
        eventId: UUID,
        workerId: String,
        errorMessage: String,
        retryDelay: Duration,
    ) {
        jdbcTemplate.queryForList(
            "SELECT fail_outbox_event(?, ?, ?, ?::interval)",
            eventId,
            workerId,
            errorMessage,
            "${retryDelay.seconds} seconds",
        )
    }

    override fun deadLetter(
        eventId: UUID,
        workerId: String,
        errorMessage: String,
    ) {
        jdbcTemplate.queryForList(
            "SELECT dead_letter_outbox_event(?, ?, ?)",
            eventId,
            workerId,
            errorMessage,
        )
    }

    override fun reclaimStale(
        lockedBefore: Instant,
        limit: Int,
    ): Int {
        require(limit in 1..5000) {
            "Outbox stale-lock reclaim limit must be between 1 and 5000"
        }

        return requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT reclaim_stale_outbox_events(?, ?)",
                Int::class.java,
                Timestamp.from(lockedBefore),
                limit,
            ),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapClaimed(rs: ResultSet, rowNumber: Int): ClaimedOutboxEvent {
        return ClaimedOutboxEvent(
            id = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            aggregateType = rs.getString("aggregate_type"),
            aggregateId = rs.getObject("aggregate_id", UUID::class.java),
            eventType = rs.getString("event_type"),
            destination = OutboxDestination.entries.first {
                it.databaseValue == rs.getString("destination")
            },
            payload = rs.getString("payload"),
            headers = rs.getString("headers"),
            correlationId = rs.getString("correlation_id"),
            idempotencyKeyId = rs.getObject("idempotency_key_id", UUID::class.java),
            status = OutboxStatus.entries.first {
                it.databaseValue == rs.getString("status")
            },
            priority = rs.getInt("priority"),
            attemptCount = rs.getInt("attempt_count"),
            maxAttempts = rs.getInt("max_attempts"),
            nextAttemptAt = rs.getTimestamp("next_attempt_at").toInstant(),
            lockedBy = rs.getString("locked_by"),
            lockedAt = rs.getTimestamp("locked_at")?.toInstant(),
            deliveredAt = rs.getTimestamp("delivered_at")?.toInstant(),
            failedAt = rs.getTimestamp("failed_at")?.toInstant(),
            errorMessage = rs.getString("error_message"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }
}
