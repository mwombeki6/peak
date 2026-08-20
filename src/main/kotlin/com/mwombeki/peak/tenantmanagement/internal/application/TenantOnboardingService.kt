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
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.util.HumanIdentifierGenerator
import com.mwombeki.peak.shared.util.violatesConstraint
import com.mwombeki.peak.tenantmanagement.api.Tenant
import com.mwombeki.peak.tenantmanagement.api.TenantOnboardingNextAction
import com.mwombeki.peak.tenantmanagement.api.TenantOnboardingPort
import com.mwombeki.peak.tenantmanagement.api.TenantOnboardingResponse
import com.mwombeki.peak.tenantmanagement.api.TenantProfile
import com.mwombeki.peak.tenantmanagement.api.TenantRegisterRequest
import com.mwombeki.peak.tenantmanagement.api.TenantResponse
import com.mwombeki.peak.tenantmanagement.api.TenantStatus
import com.mwombeki.peak.tenantmanagement.internal.TenantProfileRepository
import com.mwombeki.peak.tenantmanagement.internal.TenantRepository
import com.mwombeki.peak.usermanagement.api.PlatformAccessPort
import com.mwombeki.peak.usermanagement.api.PlatformAccessRequest
import java.time.Instant
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class TenantOnboardingService(
    private val tenantRepository: TenantRepository,
    private val tenantProfileRepository: TenantProfileRepository,
    private val requestContextHolder: RequestContextHolder,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val platformAccessPort: PlatformAccessPort,
    private val objectMapper: ObjectMapper,
    private val jdbcTemplate: JdbcTemplate,
    private val launchEvaluator: TenantLaunchEvaluator,
) : TenantOnboardingPort {

    private val identifierGenerator = HumanIdentifierGenerator()

    @Transactional
    override fun registerNewTenant(request: TenantRegisterRequest): TenantResponse {
        requirePlatformAccess(
            tenantId = null,
            permissionCode = "platform.tenants.manage",
            operation = "platform.tenants.register",
        )
        val operatorId = currentPlatformActorId()
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
            var tenant = Tenant(
                id = tenantId,
                name = request.name.trim(),
                slug = slug,
                status = TenantStatus.TRIAL,
                schemaName = schemaNameFor(tenantId),
                planId = request.planId,
                countryCode = request.countryCode,
                currencyCode = request.currencyCode,
                tenantNumber = identifierGenerator.generate("TN"),
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

            var tenantNumberAttempts = 0
            while (true) {
                try {
                    tenantRepository.save(tenant)
                    break
                } catch (ex: DuplicateKeyException) {
                    if (!ex.violatesConstraint("uq_tenants_tenant_number") || ++tenantNumberAttempts >= 5) {
                        throw ex
                    }
                    tenant = tenant.copy(tenantNumber = identifierGenerator.generate("TN"))
                }
            }
            tenantProfileRepository.save(profile)
            initializeControlPlane(tenant, operatorId)
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
            response(tenant, profile, launchEvaluator.evaluate(tenantId).nextAction)
        }
    }

    @Transactional(readOnly = true)
    override fun getOnboarding(tenantId: UUID): TenantOnboardingResponse {
        requirePlatformAccess(
            tenantId = tenantId,
            permissionCode = "platform.tenants.view",
            operation = "platform.tenants.onboarding",
        )
        return launchEvaluator.evaluate(tenantId)
    }

    @Transactional(readOnly = true)
    override fun getTenantById(id: UUID): TenantResponse? {
        requirePlatformAccess(
            tenantId = id,
            permissionCode = "platform.tenants.view",
            operation = "platform.tenants.view",
        )
        val tenant = tenantRepository.findById(id) ?: return null
        val profile = tenantProfileRepository.findByTenantId(id)
            ?: throw IllegalStateException("Tenant profile is missing for tenant $id")

        return response(tenant, profile, launchEvaluator.evaluate(id).nextAction)
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

    private fun currentPlatformActorId(): UUID {
        return when (val identity = requestContextHolder.current().identity) {
            is RequestIdentity.Platform -> identity.platformUserId
            is RequestIdentity.Support -> identity.platformUserId
            else -> throw IllegalStateException("Platform identity is required")
        }
    }

    private fun requirePlatformAccess(
        tenantId: UUID?,
        permissionCode: String,
        operation: String,
    ) {
        platformAccessPort.requireAuthorized(
            PlatformAccessRequest(
                tenantId = tenantId,
                permissionCode = permissionCode,
                operation = operation,
            ),
        )
    }

    private fun response(
        tenant: Tenant,
        profile: TenantProfile,
        nextAction: TenantOnboardingNextAction? = null,
    ): TenantResponse {
        return TenantResponse(
            id = tenant.id,
            name = tenant.name,
            slug = tenant.slug,
            status = tenant.status,
            planId = tenant.planId,
            businessEmail = profile.businessEmail,
            tenantNumber = tenant.tenantNumber,
            nextAction = nextAction,
        )
    }

    private fun schemaNameFor(tenantId: UUID): String {
        return "tenant_${tenantId.toString().replace("-", "")}"
    }

    private fun initializeControlPlane(tenant: Tenant, operatorId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO tenant_control_states (
                tenant_id, lifecycle_status, verification_status,
                provisioning_status, subscription_status, service_status,
                offboarding_status, updated_by_platform_user_id
            ) VALUES (?, 'trial', 'unverified', 'provisioning', 'trialing',
                      'operational', 'none', ?)
            """.trimIndent(),
            tenant.id,
            operatorId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_subscriptions (
                tenant_id, plan_id, status, billing_cycle, billing_currency,
                provider, current_period_starts_at, trial_ends_at,
                created_by_platform_user_id
            ) VALUES (?, ?, 'trialing', 'monthly', ?, 'manual', now(),
                      now() + interval '30 days', ?)
            """.trimIndent(),
            tenant.id,
            tenant.planId,
            tenant.currencyCode ?: "TZS",
            operatorId,
        )
        val workflowId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO tenant_workflows (
                id, tenant_id, workflow_type, status,
                requested_by_platform_user_id, reason, current_step,
                total_steps, completed_steps, started_at
            ) VALUES (?, ?, 'onboarding', 'running', ?,
                      'Tenant registered; verification and provisioning required',
                      'register_tenant', 6, 1, now())
            """.trimIndent(),
            workflowId,
            tenant.id,
            operatorId,
        )
        listOf(
            "register_tenant" to "succeeded",
            "verify_business" to "pending",
            "provision_administrator" to "pending",
            "configure_entitlements" to "pending",
            "verify_readiness" to "pending",
            "activate" to "pending",
        ).forEachIndexed { index, (step, status) ->
            jdbcTemplate.update(
                """
                INSERT INTO tenant_workflow_steps (
                    tenant_id, workflow_id, step_key, sequence, status,
                    attempt_count, started_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?,
                          CASE WHEN ? = 'succeeded' THEN now() ELSE NULL END,
                          CASE WHEN ? = 'succeeded' THEN now() ELSE NULL END)
                """.trimIndent(),
                tenant.id,
                workflowId,
                step,
                index + 1,
                status,
                if (status == "succeeded") 1 else 0,
                status,
                status,
            )
        }
    }

}
