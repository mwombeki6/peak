package com.mwombeki.peak.realtime.internal

import io.micrometer.core.instrument.MeterRegistry
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@ConfigurationProperties(prefix = "peak.realtime.journal")
data class RealtimeJournalProperties(
    val pollBatchSize: Int = 500,
    val replayLimit: Int = 500,
) {
    init {
        require(pollBatchSize in 1..1000)
        require(replayLimit in 1..1000)
    }
}

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RealtimeEventJournal(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: RealtimeJournalProperties,
    private val meterRegistry: MeterRegistry,
) {
    fun append(
        tenantId: UUID,
        propertyId: UUID,
        eventType: String,
        payload: Map<String, Any?>,
    ): StoredRealtimeEvent = append(
        tenantId = tenantId,
        propertyId = propertyId,
        outletId = null,
        eventType = eventType,
        schemaVersion = 1,
        aggregateType = null,
        aggregateId = null,
        aggregateVersion = null,
        payload = payload,
    )

    fun append(
        tenantId: UUID,
        propertyId: UUID,
        outletId: UUID?,
        eventType: String,
        schemaVersion: Int,
        aggregateType: String?,
        aggregateId: UUID?,
        aggregateVersion: Long?,
        payload: Map<String, Any?>,
    ): StoredRealtimeEvent {
        require(EVENT_TYPE.matches(eventType)) {
            "Realtime event type is invalid"
        }
        require(schemaVersion >= 1) {
            "Realtime event schema version must be at least 1"
        }
        require((aggregateType == null) == (aggregateId == null)) {
            "Realtime aggregate type and id must be provided together"
        }
        require(aggregateVersion == null || aggregateVersion >= 0) {
            "Realtime aggregate version must be non-negative"
        }
        val serialized = objectMapper.writeValueAsString(payload)
        require(serialized.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Realtime event payload exceeds 64 KiB"
        }
        return jdbcTemplate.query(
            """
            SELECT sequence_id, event_id, tenant_id, property_id,
                   event_type, payload::text AS payload, created_at,
                   schema_version, aggregate_type, aggregate_id,
                   aggregate_version, outlet_id
            FROM append_realtime_event(?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """.trimIndent(),
            ::mapEvent,
            tenantId,
            propertyId,
            eventType,
            serialized,
            outletId,
            schemaVersion,
            aggregateType,
            aggregateId,
            aggregateVersion,
        ).single().also {
            meterRegistry.counter(
                "peak.realtime.journal.events.appended",
                "eventType",
                eventType,
            ).increment()
        }
    }

    fun latestSequence(): Long {
        return jdbcTemplate.queryForObject(
            "SELECT latest_realtime_event_sequence()",
            Long::class.java,
        ) ?: 0L
    }

    fun pollAfter(sequenceId: Long): List<StoredRealtimeEvent> {
        return jdbcTemplate.query(
            """
            SELECT sequence_id, event_id, tenant_id, property_id,
                   event_type, payload::text AS payload, created_at,
                   schema_version, aggregate_type, aggregate_id,
                   aggregate_version, outlet_id
            FROM poll_realtime_events(?, ?)
            """.trimIndent(),
            ::mapEvent,
            sequenceId,
            properties.pollBatchSize,
        ).also { events ->
            meterRegistry.counter("peak.realtime.journal.events.polled")
                .increment(events.size.toDouble())
        }
    }

    fun replayAfter(
        tenantId: UUID,
        propertyId: UUID,
        lastEventId: String?,
    ): List<StoredRealtimeEvent> {
        if (lastEventId.isNullOrBlank()) {
            return emptyList()
        }
        val sequenceId = lastEventId.toLongOrNull()
            ?: throw IllegalArgumentException("Last-Event-ID must be a numeric realtime sequence.")
        return jdbcTemplate.query(
            """
            SELECT sequence_id, event_id, tenant_id, property_id,
                   event_type, payload::text AS payload, created_at,
                   schema_version, aggregate_type, aggregate_id,
                   aggregate_version, outlet_id
            FROM replay_realtime_events(?, ?, ?, ?)
            """.trimIndent(),
            ::mapEvent,
            tenantId,
            propertyId,
            sequenceId,
            properties.replayLimit,
        ).also { events ->
            meterRegistry.counter("peak.realtime.journal.events.replayed")
                .increment(events.size.toDouble())
        }
    }

    fun deleteExpired(): Int {
        return (jdbcTemplate.queryForObject(
            "SELECT delete_expired_realtime_events(?)",
            Int::class.java,
            CLEANUP_BATCH_SIZE,
        ) ?: 0).also { deleted ->
            meterRegistry.counter("peak.realtime.journal.events.expired")
                .increment(deleted.toDouble())
        }
    }

    fun isFullPollBatch(events: List<StoredRealtimeEvent>): Boolean {
        return events.size == properties.pollBatchSize
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapEvent(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): StoredRealtimeEvent {
        return StoredRealtimeEvent(
            sequenceId = rs.getLong("sequence_id"),
            eventId = rs.getObject("event_id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            eventType = rs.getString("event_type"),
            payload = objectMapper.readValue(rs.getString("payload"), Map::class.java)
                    as Map<String, Any?>,
            createdAt = rs.getTimestamp("created_at").toInstant(),
            schemaVersion = rs.getInt("schema_version"),
            aggregateType = rs.getString("aggregate_type"),
            aggregateId = rs.getObject("aggregate_id", UUID::class.java),
            aggregateVersion = rs.getObject("aggregate_version") as Long?,
            outletId = rs.getObject("outlet_id", UUID::class.java),
        )
    }

    private companion object {
        val EVENT_TYPE = Regex("[A-Za-z][A-Za-z0-9._:-]{1,99}")
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        const val CLEANUP_BATCH_SIZE = 5000
    }
}

data class StoredRealtimeEvent(
    val sequenceId: Long,
    val eventId: UUID,
    val tenantId: UUID,
    val propertyId: UUID,
    val eventType: String,
    val payload: Map<String, Any?>,
    val createdAt: Instant,
    val schemaVersion: Int = 1,
    val aggregateType: String? = null,
    val aggregateId: UUID? = null,
    val aggregateVersion: Long? = null,
    val outletId: UUID? = null,
)
