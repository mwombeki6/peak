package com.mwombeki.peak.housekeeping.internal

import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class HousekeepingCheckoutOutboxHandler(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) : OutboxEventHandler {
    override val destination = OutboxDestination.HOUSEKEEPING

    override fun supports(event: ClaimedOutboxEvent) =
        event.destination == destination && event.eventType == EVENT

    override suspend fun handle(event: ClaimedOutboxEvent) {
        val tenantId = requireNotNull(event.tenantId)
        val propertyId = requireNotNull(event.propertyId)
        val stayId = requireNotNull(event.aggregateId)
        val roomId = UUID.fromString(
            objectMapper.readTree(event.payload).get("roomId").asText(),
        )
        transactionTemplate.executeWithoutResult {
            val identity = RequestIdentity.Public(
                tenantId = tenantId,
                propertyId = propertyId,
                correlationId = event.correlationId ?: event.id.toString(),
            )
            databaseSessionContext.bind(identity)
            jdbcTemplate.update(
                """
                INSERT INTO housekeeping_tasks (
                    tenant_id, property_id, room_id, source_stay_id, type,
                    status, priority, scheduled_date, notes
                )
                SELECT ?, ?, ?, ?, 'departure_clean', 'pending', 3,
                       business_date, 'Automatically created at checkout'
                FROM properties
                WHERE tenant_id = ? AND id = ?
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                tenantId, propertyId, roomId, stayId, tenantId, propertyId,
            )
        }
    }

    private companion object {
        const val EVENT = "frontdesk.departure_clean_requested"
    }
}
