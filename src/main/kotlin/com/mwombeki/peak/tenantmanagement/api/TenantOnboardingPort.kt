package com.mwombeki.peak.tenantmanagement.api

import java.util.UUID

/**
 * The boundary contract defining how external clients interact with Tenant Onboarding.
 */

interface TenantOnboardingPort{
    fun registerNewTenant(request: TenantRegisterRequest): TenantResponse
    fun getTenantById(id: UUID): TenantResponse?
}
