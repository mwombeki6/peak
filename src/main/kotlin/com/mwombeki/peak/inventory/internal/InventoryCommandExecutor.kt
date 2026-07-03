package com.mwombeki.peak.inventory.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.inventory.api.InventoryConflictException
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.TenantActor
import com.mwombeki.peak.shared.context.TenantRequestContext
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.dao.DataAccessException
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class InventoryCommandExecutor(
    private val context: TenantRequestContext,
    private val idempotency: IdempotencyPort,
    private val audit: AuditPort,
    private val outbox: OutboxPort,
    private val transaction: TransactionTemplate,
    private val mapper: ObjectMapper,
) {
    fun <T : Any> mutate(
        propertyId: UUID, operation: String, payload: Any, resource: String,
        type: Class<T>, id: (T) -> UUID, replay: (T) -> T,
        block: (TenantActor, UUID) -> T,
    ): T = requireNotNull(transaction.execute {
        val actor = bind(propertyId, true)
        when (val reserved = idempotency.reserve(IdempotencyCommand(operation, payload, resource))) {
            is IdempotencyReservation.Started -> try {
                block(actor, reserved.recordId).also {
                    idempotency.markSucceeded(reserved.recordId, 200, it, id(it))
                }
            } catch (ex: DataAccessException) {
                throw InventoryConflictException("Inventory command conflicts with current stock")
            }
            is IdempotencyReservation.Replay -> replay(
                mapper.readValue(
                    reserved.responseBody
                        ?: throw InventoryConflictException("Stored replay is missing"),
                    type,
                ),
            )
            is IdempotencyReservation.InProgress ->
                throw InventoryConflictException("Inventory command is in progress")
            is IdempotencyReservation.Conflict ->
                throw InventoryConflictException("Idempotency key conflicts with another command")
        }
    })

    fun <T> read(propertyId: UUID, block: (TenantActor) -> T): T =
        requireNotNull(transaction.execute { block(bind(propertyId, false)) })

    fun effects(
        actor: TenantActor, propertyId: UUID, action: String,
        resource: String, id: UUID, payload: Map<String, Any?>, key: UUID,
    ) {
        audit.recordTenantEvent(
            TenantAuditEvent(actor.tenantId, action, AuditResource(resource, id), after = payload),
        )
        outbox.enqueue(
            OutboxEventCommand(
                resource, action, OutboxDestination.PLATFORM, payload,
                id, actor.tenantId, propertyId, idempotencyKeyId = key,
            ),
        )
    }

    private fun bind(propertyId: UUID, lock: Boolean) = context.bind().also {
        context.requirePropertyUsable(it.tenantId, propertyId, lock)
    }
}
