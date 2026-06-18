package com.mwombeki.peak.tenantmanagement.internal.application

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

@Service
class TenantOnboardingService(
    private val tenantRepository: TenantRepository,
    private val tenantProfileRepository: TenantProfileRepository,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
) : TenantOnboardingPort {

    @Transactional
    override fun registerNewTenant(request: TenantRegisterRequest): TenantResponse {
        val operatorId = bindPlatformContext()
        val slug = request.slug.trim().lowercase()

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

        return response(tenant, profile)
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

        return response(tenant, profile)
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
