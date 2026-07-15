package com.mwombeki.peak.platformgovernance.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.PlatformAuditEvent
import com.mwombeki.peak.platformgovernance.api.GovernanceActionResponse
import com.mwombeki.peak.platformgovernance.api.TenantGovernancePort
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.tenantmanagement.api.TenantLifecycleMutationPort
import com.mwombeki.peak.tenantmanagement.api.TenantLifecycleTransitionCommand
import com.mwombeki.peak.usermanagement.api.PlatformAccessPort
import com.mwombeki.peak.usermanagement.api.PlatformAccessRequest
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class TenantGovernanceService(
    private val requestContextHolder: RequestContextHolder,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val platformAccessPort: PlatformAccessPort,
    private val tenantLifecycleMutationPort: TenantLifecycleMutationPort,
    private val objectMapper: ObjectMapper,
) : TenantGovernancePort {

    @Transactional
    override fun approveTenant(
        tenantId: UUID,
        operatorId: UUID,
        reason: String,
    ): GovernanceActionResponse {
        requirePlatformAccess(
            tenantId = tenantId,
            operatorId = operatorId,
            permissionCode = "platform.tenants.manage",
            operation = "platform.tenants.approve",
        )
        return idempotentTransition(
            tenantId = tenantId,
            operationType = "platform.tenant.approve",
            requestedStatus = "active",
            reason = reason,
        ) { idempotencyKeyId ->
            transitionTenant(
                tenantId = tenantId,
                operatorId = operatorId,
                allowedCurrentStatuses = setOf("trial", "suspended", "frozen"),
                newStatus = "active",
                lifecycleEventType = "activated",
                reason = reason,
                message = "Tenant account has been activated.",
                idempotencyKeyId = idempotencyKeyId,
            )
        }
    }

    @Transactional
    override fun suspendTenant(
        tenantId: UUID,
        operatorId: UUID,
        reason: String,
    ): GovernanceActionResponse {
        requirePlatformAccess(
            tenantId = tenantId,
            operatorId = operatorId,
            permissionCode = "platform.tenants.manage",
            operation = "platform.tenants.suspend",
        )
        return idempotentTransition(
            tenantId = tenantId,
            operationType = "platform.tenant.suspend",
            requestedStatus = "suspended",
            reason = reason,
        ) { idempotencyKeyId ->
            transitionTenant(
                tenantId = tenantId,
                operatorId = operatorId,
                allowedCurrentStatuses = setOf("trial", "active"),
                newStatus = "suspended",
                lifecycleEventType = "suspended",
                reason = reason,
                message = "Tenant account has been suspended.",
                idempotencyKeyId = idempotencyKeyId,
            )
        }
    }

    private fun idempotentTransition(
        tenantId: UUID,
        operationType: String,
        requestedStatus: String,
        reason: String,
        transition: (UUID) -> GovernanceActionResponse,
    ): GovernanceActionResponse {
        require(reason.isNotBlank()) {
            "Governance reason is required"
        }
        return when (
            val reservation = idempotencyPort.reserve(
                IdempotencyCommand(
                    operationType = operationType,
                    requestPayload = mapOf(
                        "tenantId" to tenantId,
                        "requestedStatus" to requestedStatus,
                        "reason" to reason.trim(),
                    ),
                    resourceType = "tenants",
                ),
            )
        ) {
            is IdempotencyReservation.Started -> {
                val response = transition(reservation.recordId)
                idempotencyPort.markSucceeded(
                    recordId = reservation.recordId,
                    responseCode = 200,
                    responseBody = response,
                    resourceId = tenantId,
                )
                response
            }

            is IdempotencyReservation.Replay -> {
                require(!reservation.responseBody.isNullOrBlank()) {
                    "Governance replay does not contain a stored response body"
                }
                objectMapper.readValue(
                    reservation.responseBody,
                    GovernanceActionResponse::class.java,
                ).copy(replayed = true)
            }

            is IdempotencyReservation.InProgress -> {
                error("Tenant governance command is already being processed")
            }

            is IdempotencyReservation.Conflict -> {
                throw IllegalArgumentException(
                    "Idempotency key was already used for a different governance command",
                )
            }
        }
    }

    private fun transitionTenant(
        tenantId: UUID,
        operatorId: UUID,
        allowedCurrentStatuses: Set<String>,
        newStatus: String,
        lifecycleEventType: String,
        reason: String,
        message: String,
        idempotencyKeyId: UUID,
    ): GovernanceActionResponse {
        val transition = tenantLifecycleMutationPort.transition(
            TenantLifecycleTransitionCommand(
                tenantId = tenantId,
                operatorId = operatorId,
                allowedCurrentStatuses = allowedCurrentStatuses,
                newStatus = newStatus,
                eventType = lifecycleEventType,
                reason = reason,
            ),
        )

        val response = GovernanceActionResponse(
            tenantId = tenantId,
            previousStatus = transition.previousStatus,
            newStatus = transition.newStatus,
            message = message,
        )
        val payload = mapOf(
            "tenantId" to tenantId,
            "previousStatus" to transition.previousStatus,
            "newStatus" to transition.newStatus,
            "reason" to reason.trim(),
        )
        auditPort.recordPlatformEvent(
            PlatformAuditEvent(
                action = "platform.tenants.$lifecycleEventType",
                targetTenantId = tenantId,
                resource = AuditResource("tenants", tenantId),
                after = payload,
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "tenants",
                aggregateId = tenantId,
                eventType = "platform.tenant.$lifecycleEventType",
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                idempotencyKeyId = idempotencyKeyId,
                priority = 3,
            ),
        )
        return response
    }

    private fun requirePlatformAccess(
        tenantId: UUID,
        operatorId: UUID,
        permissionCode: String,
        operation: String,
    ) {
        val identity = requestContextHolder.current().identity
        val platformUserId = when (identity) {
            is RequestIdentity.Platform -> identity.platformUserId
            is RequestIdentity.Support -> identity.platformUserId
            else -> throw IllegalStateException("Platform identity is required")
        }
        require(platformUserId == operatorId) {
            "Governance operator must match the active request identity"
        }
        platformAccessPort.requireAuthorized(
            PlatformAccessRequest(
                tenantId = tenantId,
                permissionCode = permissionCode,
                operation = operation,
            ),
        )
    }
}
