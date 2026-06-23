package com.mwombeki.peak.communication.internal.wokers

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OutboxDeliveryWorker(
    private val jdbcTemplate: JdbcTemplate
) {

    // Runs automatically every 5 seconds
    @Scheduled(fixedDelay = 5000)
    fun processOutboxMail() {
        // 1. Claim pending items using a professional row lock pattern
        val pendingEvents = jdbcTemplate.query(
            """
            SELECT id, event_type, payload FROM outbox_events 
            WHERE status = 'pending' AND attempt_count < max_attempts AND next_attempt_at <= NOW()
            LIMIT 10
            FOR UPDATE SKIP LOCKED
            """.trimIndent()
        ) { rs, _ ->
            Triple(
                rs.getObject("id") as UUID,
                rs.getString("event_type"),
                rs.getString("payload")
            )
        }

        if (pendingEvents.isEmpty()) return

        println(" [Worker] Found ${pendingEvents.size} pending notifications to deliver...")

        for ((id, eventType, payload) in pendingEvents) {
            try {
                // 2. Route payload to the dummy network gateway provider
                println(" [Network Send] Processing $eventType with payload: $payload")

                // Simulating network delay
                Thread.sleep(100)

                // 3. Mark event as successfully delivered
                jdbcTemplate.update(
                    "UPDATE outbox_events SET status = 'delivered', delivered_at = NOW(), attempt_count = attempt_count + 1 WHERE id = ?",
                    id
                )
            } catch (e: Exception) {
                println(" [Delivery Error] Failed to send event $id: ${e.message}")

                // Increment retry counter or mark as failed
                jdbcTemplate.update(
                    """
                    UPDATE outbox_events 
                    SET attempt_count = attempt_count + 1, 
                        status = CASE WHEN attempt_count + 1 >= max_attempts THEN 'failed' ELSE 'pending' END,
                        next_attempt_at = NOW() + (interval '1 minute' * (attempt_count + 1)),
                        error_message = ?
                    WHERE id = ?
                    """.trimIndent(),
                    e.message, id
                )
            }
        }
    }
}