package com.mwombeki.peak.procurement.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.procurement.api.ProcurementConflictException
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
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class ProcurementCommandExecutor(
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
        val actor = context.bind().also {
            context.requirePropertyUsable(it.tenantId, propertyId, true)
        }
        when (val reserved = idempotency.reserve(IdempotencyCommand(operation, payload, resource))) {
            is IdempotencyReservation.Started -> try {
                block(actor, reserved.recordId).also {
                    idempotency.markSucceeded(reserved.recordId, 200, it, id(it))
                }
            } catch (ex: DataIntegrityViolationException) {
                throw ProcurementConflictException("Procurement command conflicts with current data")
            } catch (ex: ConcurrencyFailureException) {
                throw ProcurementConflictException("Procurement command conflicts with current data")
            }
            is IdempotencyReservation.Replay -> replay(
                mapper.readValue(
                    reserved.responseBody
                        ?: throw ProcurementConflictException("Stored replay is missing"),
                    type,
                ),
            )
            is IdempotencyReservation.InProgress ->
                throw ProcurementConflictException("Procurement command is in progress")
            is IdempotencyReservation.Conflict ->
                throw ProcurementConflictException("Idempotency key conflicts with another command")
        }
    })

    fun <T> read(propertyId: UUID, block: (TenantActor) -> T): T =
        requireNotNull(transaction.execute {
            val actor = context.bind()
            context.requirePropertyUsable(actor.tenantId, propertyId)
            block(actor)
        })

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
}
