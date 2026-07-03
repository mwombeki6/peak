package com.mwombeki.peak.housekeeping.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.housekeeping.api.HousekeepingConflictException
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
class HousekeepingCommandExecutor(
    private val tenantRequestContext: TenantRequestContext,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun <T : Any> mutate(
        propertyId: UUID,
        operation: String,
        payload: Any,
        resourceType: String,
        responseType: Class<T>,
        resourceId: (T) -> UUID?,
        replay: (T) -> T,
        block: (TenantActor, UUID) -> T,
    ): T = requireNotNull(transactionTemplate.execute {
        val actor = bind(propertyId, true)
        when (val reservation = idempotencyPort.reserve(
            IdempotencyCommand(operation, payload, resourceType),
        )) {
            is IdempotencyReservation.Started -> try {
                block(actor, reservation.recordId).also {
                    idempotencyPort.markSucceeded(reservation.recordId, 200, it, resourceId(it))
                }
            } catch (ex: DataAccessException) {
                throw HousekeepingConflictException("Housekeeping command conflicts with current data")
            }
            is IdempotencyReservation.Replay -> {
                val body = reservation.responseBody
                    ?: throw HousekeepingConflictException("Stored replay response is missing")
                replay(objectMapper.readValue(body, responseType))
            }
            is IdempotencyReservation.InProgress ->
                throw HousekeepingConflictException("Housekeeping command is already in progress")
            is IdempotencyReservation.Conflict ->
                throw HousekeepingConflictException("Idempotency key was used for a different command")
        }
    })

    fun <T> read(propertyId: UUID, block: (TenantActor) -> T): T =
        requireNotNull(transactionTemplate.execute { block(bind(propertyId, false)) })

    fun sideEffects(
        actor: TenantActor,
        propertyId: UUID,
        action: String,
        resourceType: String,
        resourceId: UUID,
        payload: Map<String, Any?>,
        idempotencyId: UUID,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = actor.tenantId,
                action = action,
                resource = AuditResource(resourceType, resourceId),
                after = payload,
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = resourceType,
                aggregateId = resourceId,
                tenantId = actor.tenantId,
                propertyId = propertyId,
                eventType = action,
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                idempotencyKeyId = idempotencyId,
            ),
        )
    }

    private fun bind(propertyId: UUID, lock: Boolean): TenantActor =
        tenantRequestContext.bind().also {
            tenantRequestContext.requirePropertyUsable(it.tenantId, propertyId, lock)
        }
}
