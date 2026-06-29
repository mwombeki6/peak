package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.PlatformAuditEvent
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.tenantmanagement.api.Tenant
import com.mwombeki.peak.tenantmanagement.api.TenantOnboardingPort
import com.mwombeki.peak.tenantmanagement.api.TenantProfile
import com.mwombeki.peak.tenantmanagement.api.TenantRegisterRequest
import com.mwombeki.peak.tenantmanagement.api.TenantResponse
import com.mwombeki.peak.tenantmanagement.api.TenantStatus
import com.mwombeki.peak.tenantmanagement.internal.TenantProfileRepository
import com.mwombeki.peak.tenantmanagement.internal.TenantRepository
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class TenantOnboardingService(
    private val tenantRepository: TenantRepository,
    private val tenantProfileRepository: TenantProfileRepository,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val objectMapper: ObjectMapper,
) : TenantOnboardingPort {

    @Transactional
    override fun registerNewTenant(request: TenantRegisterRequest): TenantResponse {
        val operatorId = bindPlatformContext()
        val slug = request.slug.trim().lowercase()

        return idempotentMutation(
            operationType = "platform.tenant.register",
            requestPayload = request,
        ) { idempotencyKeyId ->
            require(!tenantRepository.existsBySlug(slug)) {
                "Tenant slug '$slug' is already in use"
            }
            require(tenantRepository.planExists(request.planId)) {
                "Active subscription plan was not found"
            }

            val now = Instant.now()
            val tenantId = UUID.randomUUID()
            val tenant = Tenant(
                id = tenantId,
                name = request.name.trim(),
                slug = slug,
                status = TenantStatus.TRIAL,
                schemaName = schemaNameFor(tenantId),
                planId = request.planId,
                countryCode = request.countryCode,
                currencyCode = request.currencyCode,
                createdAt = now,
                updatedAt = now,
            )
            val profile = TenantProfile(
                tenantId = tenantId,
                legalName = request.legalName.trim(),
                tradingName = request.tradingName?.trim()?.takeIf { it.isNotEmpty() },
                entityType = request.entityType.trim(),
                businessRegistrationNumber = request.businessRegistrationNumber
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
                businessEmail = request.businessEmail.trim().lowercase(),
                businessPhone = request.businessPhone.trim(),
                registeredAddress = request.registeredAddress,
                registrationCountryCode = request.countryCode,
                updatedAt = now,
            )

            tenantRepository.save(tenant)
            tenantProfileRepository.save(profile)
            tenantRepository.recordLifecycleEvent(
                tenantId = tenantId,
                eventType = "created",
                reason = "Tenant registered by platform operator",
                platformUserId = operatorId,
                metadata = mapOf(
                    "slug" to slug,
                    "planId" to request.planId,
                ),
            )
            recordSideEffects(
                tenantId = tenantId,
                action = "platform.tenants.registered",
                payload = mapOf(
                    "tenantId" to tenantId,
                    "slug" to slug,
                    "planId" to request.planId,
                    "status" to TenantStatus.TRIAL.databaseValue,
                ),
                idempotencyKeyId = idempotencyKeyId,
            )
            response(tenant, profile)
        }
    }

    @Transactional(readOnly = true)
    override fun getTenantById(id: UUID): TenantResponse? {
        bindPlatformContext()
        val tenant = tenantRepository.findById(id) ?: return null
        val profile = tenantProfileRepository.findByTenantId(id)
            ?: throw IllegalStateException("Tenant profile is missing for tenant $id")

        return response(tenant, profile)
    }

    @Transactional
    override fun updateTenantStatus(id: UUID, status: TenantStatus): TenantResponse {
        val operatorId = bindPlatformContext()
        return idempotentMutation(
            operationType = "platform.tenant.status.${status.databaseValue}",
            requestPayload = mapOf("tenantId" to id, "status" to status.databaseValue),
        ) { idempotencyKeyId ->
            val before = tenantRepository.findById(id)
                ?: throw IllegalArgumentException("Tenant was not found")

            tenantRepository.updateStatus(id, status)
            tenantRepository.recordLifecycleEvent(
                tenantId = id,
                eventType = status.lifecycleEventType(),
                reason = "Tenant status changed from ${before.status.databaseValue} to ${status.databaseValue}",
                platformUserId = operatorId,
                metadata = mapOf(
                    "previousStatus" to before.status.databaseValue,
                    "newStatus" to status.databaseValue,
                ),
            )

            val tenant = tenantRepository.findById(id)
                ?: throw IllegalStateException("Tenant disappeared after status update")
            val profile = tenantProfileRepository.findByTenantId(id)
                ?: throw IllegalStateException("Tenant profile is missing for tenant $id")
            recordSideEffects(
                tenantId = id,
                action = "platform.tenants.status.changed",
                payload = mapOf(
                    "tenantId" to id,
                    "previousStatus" to before.status.databaseValue,
                    "newStatus" to status.databaseValue,
                ),
                idempotencyKeyId = idempotencyKeyId,
            )
            response(tenant, profile)
        }
    }

    private fun idempotentMutation(
        operationType: String,
        requestPayload: Any,
        block: (UUID) -> TenantResponse,
    ): TenantResponse {
        return when (
            val reservation = idempotencyPort.reserve(
                IdempotencyCommand(
                    operationType = operationType,
                    requestPayload = requestPayload,
                    resourceType = "tenants",
                ),
            )
        ) {
            is IdempotencyReservation.Started -> {
                val response = block(reservation.recordId)
                idempotencyPort.markSucceeded(
                    recordId = reservation.recordId,
                    responseCode = 200,
                    responseBody = response,
                    resourceId = response.id,
                )
                response
            }

            is IdempotencyReservation.Replay -> {
                check(!reservation.responseBody.isNullOrBlank()) {
                    "Tenant onboarding replay does not contain a stored response body"
                }
                objectMapper.readValue(reservation.responseBody, TenantResponse::class.java)
            }

            is IdempotencyReservation.InProgress -> {
                error("Tenant onboarding command is already being processed")
            }

            is IdempotencyReservation.Conflict -> {
                throw IllegalArgumentException(
                    "Idempotency key was already used for a different tenant onboarding request",
                )
            }
        }
    }

    private fun recordSideEffects(
        tenantId: UUID,
        action: String,
        payload: Map<String, Any?>,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordPlatformEvent(
            PlatformAuditEvent(
                action = action,
                targetTenantId = tenantId,
                resource = AuditResource("tenants", tenantId),
                after = payload,
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "tenants",
                aggregateId = tenantId,
                eventType = action,
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                idempotencyKeyId = idempotencyKeyId,
                priority = 3,
            ),
        )
    }

    private fun bindPlatformContext(): UUID {
        val identity = requestContextHolder.current().identity
        val platformUserId = when (identity) {
            is RequestIdentity.Platform -> identity.platformUserId
            is RequestIdentity.Support -> identity.platformUserId
            else -> throw IllegalStateException("Platform identity is required")
        }
        databaseSessionContext.bind(identity)
        return platformUserId
    }

    private fun response(
        tenant: Tenant,
        profile: TenantProfile,
    ): TenantResponse {
        return TenantResponse(
            id = tenant.id,
            name = tenant.name,
            slug = tenant.slug,
            status = tenant.status,
            planId = tenant.planId,
            businessEmail = profile.businessEmail,
        )
    }

    private fun schemaNameFor(tenantId: UUID): String {
        return "tenant_${tenantId.toString().replace("-", "")}"
    }

    private fun TenantStatus.lifecycleEventType(): String {
        return when (this) {
            TenantStatus.TRIAL -> "created"
            TenantStatus.ACTIVE -> "activated"
            TenantStatus.SUSPENDED -> "suspended"
            TenantStatus.FROZEN -> "frozen"
            TenantStatus.ARCHIVED -> "archived"
            TenantStatus.TERMINATED -> "terminated"
            TenantStatus.CANCELLED -> "cancelled"
        }
    }
}
