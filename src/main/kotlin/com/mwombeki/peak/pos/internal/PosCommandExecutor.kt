package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.pos.api.PosConflictException
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.TenantActor
import com.mwombeki.peak.shared.context.TenantRequestContext
import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class PosCommandExecutor(
    private val tenantRequestContext: TenantRequestContext,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    fun <T : Any> mutate(
        propertyId: UUID,
        operationType: String,
        requestPayload: Any,
        resourceType: String,
        replayType: Class<T>,
        resourceId: (T) -> UUID?,
        markReplayed: (T) -> T,
        block: (TenantActor, UUID) -> T,
    ): T {
        return requireNotNull(
            transactionTemplate.execute {
                val actor = bind(propertyId, lockProperty = true)
                when (
                    val reservation = idempotencyPort.reserve(
                        IdempotencyCommand(
                            operationType = operationType,
                            requestPayload = requestPayload,
                            resourceType = resourceType,
                        ),
                    )
                ) {
                    is IdempotencyReservation.Started -> {
                        val response = try {
                            block(actor, reservation.recordId)
                        } catch (ex: DataIntegrityViolationException) {
                            throw PosConflictException("POS command conflicts with current data")
                        } catch (ex: ConcurrencyFailureException) {
                            throw PosConflictException("POS command conflicts with current data")
                        }
                        idempotencyPort.markSucceeded(
                            recordId = reservation.recordId,
                            responseCode = 200,
                            responseBody = response,
                            resourceId = resourceId(response),
                        )
                        metric(operationType, "succeeded")
                        response
                    }

                    is IdempotencyReservation.Replay -> {
                        if (reservation.responseBody.isNullOrBlank()) {
                            throw PosConflictException(
                                "POS command replay does not contain a stored response",
                            )
                        }
                        markReplayed(objectMapper.readValue(reservation.responseBody, replayType))
                            .also { metric(operationType, "replayed") }
                    }

                    is IdempotencyReservation.InProgress -> {
                        metric(operationType, "in_progress")
                        throw PosConflictException("POS command is already being processed")
                    }

                    is IdempotencyReservation.Conflict -> {
                        metric(operationType, "conflict")
                        throw PosConflictException(
                            "Idempotency key was used for a different POS command",
                        )
                    }
                }
            },
        )
    }

    fun <T> read(propertyId: UUID, block: (TenantActor) -> T): T {
        return requireNotNull(
            transactionTemplate.execute {
                block(bind(propertyId, lockProperty = false))
            },
        )
    }

    fun recordSideEffects(
        actor: TenantActor,
        propertyId: UUID,
        action: String,
        aggregateType: String,
        aggregateId: UUID,
        payload: Map<String, Any?>,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = actor.tenantId,
                action = action,
                resource = AuditResource(aggregateType, aggregateId),
                after = payload,
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = aggregateType,
                aggregateId = aggregateId,
                tenantId = actor.tenantId,
                propertyId = propertyId,
                eventType = action,
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                idempotencyKeyId = idempotencyKeyId,
                priority = 3,
            ),
        )
    }

    private fun bind(propertyId: UUID, lockProperty: Boolean): TenantActor {
        val actor = tenantRequestContext.bind()
        tenantRequestContext.requirePropertyUsable(actor.tenantId, propertyId, lockProperty)
        return actor
    }

    private fun metric(operationType: String, result: String) {
        meterRegistry.counter(
            "peak.pos.command",
            "operation",
            operationType,
            "result",
            result,
        ).increment()
    }
}
