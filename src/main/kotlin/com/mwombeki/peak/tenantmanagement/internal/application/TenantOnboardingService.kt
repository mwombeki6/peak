package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.tenantmanagement.api.*
import com.mwombeki.peak.tenantmanagement.internal.TenantProfileRepository
import com.mwombeki.peak.tenantmanagement.internal.TenantRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class TenantOnboardingService(
    private val tenantRepository: TenantRepository,
    private val tenantProfileRepository: TenantProfileRepository
) : TenantOnboardingPort {

    @Transactional
    override fun registerNewTenant(request: TenantRegisterRequest): TenantResponse {
        // Guard Clause: Block duplicate sub-domains/slugs before touching the database
        if (tenantRepository.findBySlug(request.uniqueSlug) != null) {
            throw IllegalArgumentException("A hotel tenant with slug '${request.uniqueSlug}' already exists.")
        }

        val targetTenantId = UUID.randomUUID()

        // 1. Build the core tenant data
        val tenant = Tenant(
            id = targetTenantId,
            name = request.name,
            uniqueSlug = request.uniqueSlug,
            status = TenantStatus.PENDING_VERIFICATION,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        // 2. Build the detailed profile metadata
        val profile = TenantProfile(
            id = UUID.randomUUID(),
            tenantId = targetTenantId,
            businessRegistrationNumber = request.businessRegistrationNumber,
            primaryEmail = request.primaryEmail,
            primaryPhone = request.primaryPhone,
            physicalAddress = request.physicalAddress,
            country = request.country,
            city = request.city,
            updatedAt = Instant.now()
        )

        // 3. Save everything transactionally via JdbcTemplate methods
        tenantRepository.save(tenant)
        tenantProfileRepository.save(profile)

        return TenantResponse(
            id = tenant.id,
            name = tenant.name,
            uniqueSlug = tenant.uniqueSlug,
            status = tenant.status,
            primaryEmail = profile.primaryEmail
        )
    }

    @Transactional(readOnly = true)
    override fun getTenantById(id: UUID): TenantResponse? {
        val tenant = tenantRepository.findById(id) ?: return null
        val profile = tenantProfileRepository.findByTenantId(id)
            ?: throw IllegalStateException("Database integrity error: Profile missing for tenant ID: $id")

        return TenantResponse(
            id = tenant.id,
            name = tenant.name,
            uniqueSlug = tenant.uniqueSlug,
            status = tenant.status,
            primaryEmail = profile.primaryEmail
        )
    }

    @Transactional
    override fun updateTenantStatus(id: UUID, status: TenantStatus): TenantResponse {
        tenantRepository.findById(id) ?: throw IllegalArgumentException("Tenant not found with ID: $id")

        // Execute our raw SQL status update command
        tenantRepository.updateStatus(id, status)

        val tenant = tenantRepository.findById(id)!!
        val profile = tenantProfileRepository.findByTenantId(id)!!

        return TenantResponse(
            id = tenant.id,
            name = tenant.name,
            uniqueSlug = tenant.uniqueSlug,
            status = tenant.status,
            primaryEmail = profile.primaryEmail
        )
    }
}