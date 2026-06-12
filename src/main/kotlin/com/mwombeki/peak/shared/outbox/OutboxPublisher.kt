package com.mwombeki.peak.shared.outbox

import com.mwombeki.peak.shared.context.TenantContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Enterprise Outbox Writer Service:
 * Guarantees that business events are written to the database using the same
 * active database transaction block as the primary state change.
 */
@Service
class OutboxPublisher(private val jdbcTemplate: JdbcTemplate) {

    /**
     * Publishes an event to the outbox table.
     * MANDATORY propagation ensures it participates in the existing transaction block.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun publish(eventType: String, jsonPayload: String) {
        // Enforce secure context validation before writing data rows
        val tenantId = TenantContext.getTenantId()
            ?: throw IllegalStateException("Security Violation: Cannot publish outbox event without an active tenant context!")

        val sql = """
            INSERT INTO outbox_events (id, tenant_id, event_type, payload, status, retry_count, max_retries, created_at)
            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, NOW())
        """.trimIndent()

        jdbcTemplate.update(
            sql,
            UUID.randomUUID(),
            tenantId,
            eventType,
            jsonPayload,
            OutboxStatus.PENDING.name,
            0,
            5
        )
    }
}