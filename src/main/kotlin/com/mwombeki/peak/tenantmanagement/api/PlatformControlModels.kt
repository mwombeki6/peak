package com.mwombeki.peak.tenantmanagement.api

import com.mwombeki.peak.audit.api.PlatformAuditTimelineEntry
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface PlatformTenantControlPort {
    fun listTenants(query: TenantCatalogQuery): TenantCatalogPage
    fun tenantOverview(tenantId: UUID): TenantControlOverview
    fun listWorkflows(tenantId: UUID, limit: Int): List<TenantWorkflowSummary>
    fun auditTimeline(tenantId: UUID, limit: Int): List<PlatformAuditTimelineEntry>
    fun transitionLifecycle(command: TenantControlTransitionCommand): TenantControlMutationReceipt
    fun reconcile(tenantId: UUID, expectedVersion: Long): TenantControlMutationReceipt
}

data class TenantCatalogQuery(
    val search: String? = null,
    val lifecycleStatus: String? = null,
    val subscriptionStatus: String? = null,
    val serviceStatus: String? = null,
    val cursor: UUID? = null,
    val limit: Int = 50,
)

data class TenantCatalogPage(
    val items: List<TenantCatalogItem>,
    val nextCursor: UUID?,
    val limit: Int,
)

data class TenantCatalogItem(
    val tenantId: UUID,
    val name: String,
    val slug: String,
    val countryCode: String?,
    val currencyCode: String?,
    val planCode: String,
    val lifecycleStatus: String,
    val verificationStatus: String,
    val provisioningStatus: String,
    val subscriptionStatus: String,
    val serviceStatus: String,
    val offboardingStatus: String,
    val releaseChannel: String,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TenantControlOverview(
    val tenant: TenantCatalogItem,
    val legalName: String,
    val businessEmail: String,
    val businessPhone: String,
    val subscription: TenantSubscriptionSummary?,
    val latestUsage: TenantUsageSummary?,
    val enabledModules: Int,
    val openWorkflows: Int,
    val openSupportTickets: Int,
    val unresolvedAlerts: Int,
    val configurationDrift: Boolean,
    val activation: TenantActivationReadiness,
    val onboardingWorkflow: TenantWorkflowSummary?,
)

enum class TenantControlAction {
    ACTIVATE,
    RESTRICT,
    FREEZE,
    SUSPEND,
    REACTIVATE,
    ARCHIVE,
    START_OFFBOARDING,
    COMPLETE_OFFBOARDING,
    CANCEL_OFFBOARDING,
    RESTORE,
}

data class TenantControlTransitionCommand(
    val tenantId: UUID,
    val action: TenantControlAction,
    val reason: String,
    val expectedVersion: Long,
)

data class TenantControlMutationReceipt(
    val tenantId: UUID,
    val lifecycleStatus: String,
    val provisioningStatus: String,
    val subscriptionStatus: String,
    val serviceStatus: String,
    val offboardingStatus: String,
    val version: Long,
    val workflowId: UUID?,
    val changed: Boolean,
    val replayed: Boolean,
)

data class TenantWorkflowSummary(
    val workflowId: UUID,
    val workflowType: String,
    val status: String,
    val currentStep: String?,
    val completedSteps: Int,
    val totalSteps: Int,
    val reason: String?,
    val errorCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val steps: List<TenantWorkflowStepSummary>,
)

data class TenantWorkflowStepSummary(
    val stepKey: String,
    val sequence: Int,
    val status: String,
    val attemptCount: Int,
    val errorCode: String?,
    val completedAt: Instant?,
)

@NamedInterface("api")
interface PlatformCommercialControlPort {
    fun listPlans(): List<PlanSummary>
    fun createPlan(command: CreatePlanCommand): PlanSummary
    fun updatePlan(command: UpdatePlanCommand): PlanSummary
    fun replacePlanEntitlements(command: ReplacePlanEntitlementsCommand): List<EntitlementSummary>
    fun commercialOverview(tenantId: UUID, tenantView: Boolean = false): TenantCommercialOverview
    fun changeSubscription(command: ChangeTenantSubscriptionCommand): TenantSubscriptionSummary
    fun createEntitlementOverride(command: CreateEntitlementOverrideCommand): EntitlementSummary
    fun revokeEntitlementOverride(tenantId: UUID, overrideId: UUID, reason: String): EntitlementSummary
    fun captureUsageSnapshot(tenantId: UUID, snapshotDate: LocalDate = LocalDate.now()): TenantUsageSummary
}

@NamedInterface("api")
interface EntitlementAccessPort {
    fun effectiveEntitlement(tenantId: UUID, entitlementCode: String): EffectiveEntitlement
    fun requireEnabled(tenantId: UUID, entitlementCode: String)
    fun requireWithinLimit(tenantId: UUID, entitlementCode: String, currentUsage: Long, increment: Long = 1)
}

data class PlanSummary(
    val planId: UUID,
    val code: String,
    val name: String,
    val maxProperties: Int,
    val maxRooms: Int,
    val maxUsers: Int,
    val maxOutlets: Int,
    val monthlyUsd: BigDecimal,
    val annualUsd: BigDecimal,
    val isActive: Boolean,
    val entitlements: List<EntitlementSummary>,
)

data class CreatePlanCommand(
    val code: String,
    val name: String,
    val maxProperties: Int,
    val maxRooms: Int,
    val maxUsers: Int,
    val maxOutlets: Int,
    val monthlyUsd: BigDecimal,
    val annualUsd: BigDecimal,
)

data class UpdatePlanCommand(
    val planId: UUID,
    val name: String?,
    val maxProperties: Int?,
    val maxRooms: Int?,
    val maxUsers: Int?,
    val maxOutlets: Int?,
    val monthlyUsd: BigDecimal?,
    val annualUsd: BigDecimal?,
    val isActive: Boolean?,
)

data class ReplacePlanEntitlementsCommand(
    val planId: UUID,
    val entitlements: List<EntitlementDefinition>,
)

data class EntitlementDefinition(
    val code: String,
    val enabled: Boolean,
    val value: Map<String, Any?> = emptyMap(),
)

data class EntitlementSummary(
    val code: String,
    val enabled: Boolean,
    val value: Map<String, Any?>,
    val source: String,
    val startsAt: Instant?,
    val expiresAt: Instant?,
    val overrideId: UUID? = null,
)

data class EffectiveEntitlement(
    val tenantId: UUID,
    val code: String,
    val enabled: Boolean,
    val value: Map<String, Any?>,
    val source: String,
    val resolvedAt: Instant,
)

data class TenantCommercialOverview(
    val tenantId: UUID,
    val subscription: TenantSubscriptionSummary?,
    val entitlements: List<EntitlementSummary>,
    val latestUsage: TenantUsageSummary?,
)

data class TenantSubscriptionSummary(
    val subscriptionId: UUID,
    val tenantId: UUID,
    val planId: UUID,
    val planCode: String,
    val status: String,
    val billingCycle: String,
    val billingCurrency: String,
    val provider: String,
    val providerCustomerId: String?,
    val providerSubscriptionId: String?,
    val currentPeriodStartsAt: Instant,
    val currentPeriodEndsAt: Instant?,
    val trialEndsAt: Instant?,
    val gracePeriodEndsAt: Instant?,
    val cancelAtPeriodEnd: Boolean,
    val version: Long,
)

data class ChangeTenantSubscriptionCommand(
    val tenantId: UUID,
    val planId: UUID,
    val status: String,
    val billingCycle: String,
    val billingCurrency: String,
    val provider: String,
    val providerCustomerId: String?,
    val providerSubscriptionId: String?,
    val currentPeriodEndsAt: Instant?,
    val trialEndsAt: Instant?,
    val gracePeriodEndsAt: Instant?,
    val cancelAtPeriodEnd: Boolean,
    val expectedVersion: Long?,
    val reason: String,
)

data class CreateEntitlementOverrideCommand(
    val tenantId: UUID,
    val code: String,
    val enabled: Boolean,
    val value: Map<String, Any?>,
    val reason: String,
    val expiresAt: Instant?,
)

data class TenantUsageSummary(
    val tenantId: UUID,
    val snapshotDate: LocalDate,
    val propertyCount: Int,
    val roomCount: Int,
    val userCount: Int,
    val outletCount: Int,
    val storageBytes: Long,
    val apiCalls: Long,
    val metrics: Map<String, Any?>,
)

sealed class PlatformControlException(message: String) : RuntimeException(message)
class PlatformControlNotFoundException(message: String) : PlatformControlException(message)
class PlatformControlConflictException(message: String) : PlatformControlException(message)
class PlatformControlInProgressException(message: String) : PlatformControlException(message)
