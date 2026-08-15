package com.mwombeki.peak.tenantmanagement.api

import java.util.UUID

/**
 * The boundary contract defining how external clients interact with Tenant Onboarding.
 */

interface TenantOnboardingPort {
    fun registerNewTenant(request: TenantRegisterRequest): TenantResponse
    fun getTenantById(id: UUID): TenantResponse?
    fun getOnboarding(tenantId: UUID): TenantOnboardingResponse
}

data class TenantOnboardingResponse(
    val tenantId: UUID,
    val workflowStatus: String,
    val currentStep: String?,
    val canCreateProperties: Boolean,
    val nextAction: TenantOnboardingNextAction?,
    val steps: List<TenantOnboardingStepView>,
)

data class TenantOnboardingStepView(
    val key: String,
    val sequence: Int,
    val status: String,
    val required: Boolean,
    val detail: String,
)

/**
 * The one thing to do now. Same shape as property onboarding nextAction so a
 * wizard can drive both machines without a second contract.
 */
data class TenantOnboardingNextAction(
    val step: String,
    val title: String,
    val why: String,
    val method: String,
    val path: String,
    val bodyHint: Map<String, Any?>? = null,
)

