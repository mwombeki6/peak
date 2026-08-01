package com.mwombeki.peak.tenantmanagement.internal.web

import com.mwombeki.peak.shared.exception.ApiProblemFactory
import com.mwombeki.peak.tenantmanagement.api.ChangeTenantSubscriptionCommand
import com.mwombeki.peak.tenantmanagement.api.CreateEntitlementOverrideCommand
import com.mwombeki.peak.tenantmanagement.api.CreatePlanCommand
import com.mwombeki.peak.tenantmanagement.api.EntitlementDefinition
import com.mwombeki.peak.tenantmanagement.api.EntitlementSummary
import com.mwombeki.peak.tenantmanagement.api.PlanSummary
import com.mwombeki.peak.tenantmanagement.api.PlatformCommercialControlPort
import com.mwombeki.peak.tenantmanagement.api.PlatformControlConflictException
import com.mwombeki.peak.tenantmanagement.api.PlatformControlInProgressException
import com.mwombeki.peak.tenantmanagement.api.PlatformControlNotFoundException
import com.mwombeki.peak.tenantmanagement.api.PlatformTenantControlPort
import com.mwombeki.peak.tenantmanagement.api.PlatformTenantActivationPort
import com.mwombeki.peak.tenantmanagement.api.TenantActivationReadiness
import com.mwombeki.peak.tenantmanagement.api.TenantActivationNotReadyException
import com.mwombeki.peak.tenantmanagement.api.ReplacePlanEntitlementsCommand
import com.mwombeki.peak.tenantmanagement.api.TenantCatalogPage
import com.mwombeki.peak.tenantmanagement.api.TenantCatalogQuery
import com.mwombeki.peak.tenantmanagement.api.TenantCommercialOverview
import com.mwombeki.peak.tenantmanagement.api.TenantControlAction
import com.mwombeki.peak.tenantmanagement.api.TenantControlMutationReceipt
import com.mwombeki.peak.tenantmanagement.api.TenantControlOverview
import com.mwombeki.peak.tenantmanagement.api.TenantControlTransitionCommand
import com.mwombeki.peak.tenantmanagement.api.TenantSubscriptionSummary
import com.mwombeki.peak.tenantmanagement.api.TenantUsageSummary
import com.mwombeki.peak.tenantmanagement.api.TenantWorkflowSummary
import com.mwombeki.peak.tenantmanagement.api.UpdatePlanCommand
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestController
@RequestMapping("/api/v1/platform/tenants")
class PlatformTenantControlController(
    private val controlPort: PlatformTenantControlPort,
    private val activationPort: PlatformTenantActivationPort,
) {
    @GetMapping
    fun listTenants(
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) lifecycleStatus: String?,
        @RequestParam(required = false) subscriptionStatus: String?,
        @RequestParam(required = false) serviceStatus: String?,
        @RequestParam(required = false) cursor: UUID?,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) limit: Int,
    ): TenantCatalogPage {
        return controlPort.listTenants(
            TenantCatalogQuery(
                search, lifecycleStatus, subscriptionStatus, serviceStatus, cursor, limit,
            ),
        )
    }

    @GetMapping("/{tenantId}/control")
    fun overview(@PathVariable tenantId: UUID): TenantControlOverview =
        controlPort.tenantOverview(tenantId)

    @GetMapping("/{tenantId}/activation-readiness")
    fun activationReadiness(
        @PathVariable tenantId: UUID,
    ): TenantActivationReadiness = activationPort.readiness(tenantId)

    @GetMapping("/{tenantId}/control/workflows")
    fun workflows(
        @PathVariable tenantId: UUID,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) limit: Int,
    ): List<TenantWorkflowSummary> = controlPort.listWorkflows(tenantId, limit)

    @GetMapping("/{tenantId}/control/timeline")
    fun timeline(
        @PathVariable tenantId: UUID,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) limit: Int,
    ) = controlPort.auditTimeline(tenantId, limit)

    @PostMapping("/{tenantId}/control/lifecycle")
    fun transition(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: TenantLifecycleHttpRequest,
    ): TenantControlMutationReceipt {
        return controlPort.transitionLifecycle(
            TenantControlTransitionCommand(
                tenantId, request.action, request.reason, request.expectedVersion,
            ),
        )
    }

    @PostMapping("/{tenantId}/control/reconcile")
    fun reconcile(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: ReconcileTenantControlHttpRequest,
    ): TenantControlMutationReceipt = controlPort.reconcile(tenantId, request.expectedVersion)
}

@RestController
@RequestMapping("/api/v1/platform/plans")
class PlatformPlanController(
    private val commercialPort: PlatformCommercialControlPort,
) {
    @GetMapping
    fun listPlans(): List<PlanSummary> = commercialPort.listPlans()

    @PostMapping
    fun createPlan(
        @Valid @RequestBody request: CreatePlanHttpRequest,
    ): ResponseEntity<PlanSummary> {
        val plan = commercialPort.createPlan(request.toCommand())
        return ResponseEntity.status(HttpStatus.CREATED).body(plan)
    }

    @PutMapping("/{planId}")
    fun updatePlan(
        @PathVariable planId: UUID,
        @Valid @RequestBody request: UpdatePlanHttpRequest,
    ): PlanSummary = commercialPort.updatePlan(request.toCommand(planId))

    @PutMapping("/{planId}/entitlements")
    fun replaceEntitlements(
        @PathVariable planId: UUID,
        @Valid @RequestBody request: ReplaceEntitlementsHttpRequest,
    ): List<EntitlementSummary> = commercialPort.replacePlanEntitlements(
        ReplacePlanEntitlementsCommand(
            planId,
            request.entitlements.map {
                EntitlementDefinition(it.code, it.enabled, it.value)
            },
        ),
    )
}

@RestController
@RequestMapping("/api/v1/platform/tenants/{tenantId}/commercial")
class PlatformTenantCommercialController(
    private val commercialPort: PlatformCommercialControlPort,
) {
    @GetMapping
    fun overview(@PathVariable tenantId: UUID): TenantCommercialOverview =
        commercialPort.commercialOverview(tenantId)

    @PutMapping("/subscription")
    fun changeSubscription(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: ChangeSubscriptionHttpRequest,
    ): TenantSubscriptionSummary = commercialPort.changeSubscription(request.toCommand(tenantId))

    @PostMapping("/entitlement-overrides")
    fun createOverride(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: EntitlementOverrideHttpRequest,
    ): ResponseEntity<EntitlementSummary> {
        val result = commercialPort.createEntitlementOverride(
            CreateEntitlementOverrideCommand(
                tenantId, request.code, request.enabled, request.value,
                request.reason, request.expiresAt,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(result)
    }

    @PostMapping("/entitlement-overrides/{overrideId}/revoke")
    fun revokeOverride(
        @PathVariable tenantId: UUID,
        @PathVariable overrideId: UUID,
        @Valid @RequestBody request: ReasonHttpRequest,
    ): EntitlementSummary = commercialPort.revokeEntitlementOverride(
        tenantId, overrideId, request.reason,
    )

    @PostMapping("/usage-snapshots")
    fun captureUsage(
        @PathVariable tenantId: UUID,
        @RequestParam(required = false) snapshotDate: LocalDate?,
    ): TenantUsageSummary = commercialPort.captureUsageSnapshot(
        tenantId, snapshotDate ?: LocalDate.now(),
    )
}

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/commercial")
class TenantCommercialController(
    private val commercialPort: PlatformCommercialControlPort,
) {
    @GetMapping
    fun overview(@PathVariable tenantId: UUID): TenantCommercialOverview =
        commercialPort.commercialOverview(tenantId, tenantView = true)
}

@RestControllerAdvice(
    assignableTypes = [
        PlatformTenantControlController::class,
        PlatformPlanController::class,
        PlatformTenantCommercialController::class,
        TenantCommercialController::class,
        TenantTrustController::class,
        PlatformTenantTrustController::class,
    ],
)
class PlatformControlExceptionAdvice(
    private val problemFactory: ApiProblemFactory,
) {
    @ExceptionHandler(PlatformControlNotFoundException::class)
    fun notFound(ex: PlatformControlNotFoundException): ResponseEntity<ProblemDetail> =
        problemFactory.response(HttpStatus.NOT_FOUND, "Platform control target not found", ex.safeMessage())

    @ExceptionHandler(PlatformControlConflictException::class)
    fun conflict(ex: PlatformControlConflictException): ResponseEntity<ProblemDetail> =
        problemFactory.response(HttpStatus.CONFLICT, "Platform control conflict", ex.safeMessage())

    @ExceptionHandler(PlatformControlInProgressException::class)
    fun inProgress(ex: PlatformControlInProgressException): ResponseEntity<ProblemDetail> =
        problemFactory.response(HttpStatus.CONFLICT, "Platform control command in progress", ex.safeMessage())

    @ExceptionHandler(TenantActivationNotReadyException::class)
    fun activationNotReady(ex: TenantActivationNotReadyException): ResponseEntity<ProblemDetail> =
        problemFactory.response(HttpStatus.CONFLICT, "Tenant activation is not ready", ex.safeMessage())

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> =
        problemFactory.response(HttpStatus.BAD_REQUEST, "Invalid platform control request", ex.safeMessage())

    private fun RuntimeException.safeMessage(): String = message.orEmpty()
        .removePrefix("ERROR:")
        .lineSequence()
        .first()
        .take(500)
}

data class TenantLifecycleHttpRequest(
    @field:NotNull val action: TenantControlAction,
    @field:NotBlank val reason: String,
    @field:Positive val expectedVersion: Long,
)

data class ReconcileTenantControlHttpRequest(
    @field:Positive val expectedVersion: Long,
)

data class CreatePlanHttpRequest(
    @field:NotBlank @field:Pattern(regexp = "[a-z][a-z0-9_-]{1,49}") val code: String,
    @field:NotBlank val name: String,
    @field:Positive val maxProperties: Int,
    @field:Positive val maxRooms: Int,
    @field:Positive val maxUsers: Int,
    @field:PositiveOrZero val maxOutlets: Int,
    @field:PositiveOrZero val monthlyUsd: BigDecimal,
    @field:PositiveOrZero val annualUsd: BigDecimal,
) {
    fun toCommand() = CreatePlanCommand(
        code, name, maxProperties, maxRooms, maxUsers, maxOutlets, monthlyUsd, annualUsd,
    )
}

data class UpdatePlanHttpRequest(
    val name: String? = null,
    @field:Positive val maxProperties: Int? = null,
    @field:Positive val maxRooms: Int? = null,
    @field:Positive val maxUsers: Int? = null,
    @field:PositiveOrZero val maxOutlets: Int? = null,
    @field:PositiveOrZero val monthlyUsd: BigDecimal? = null,
    @field:PositiveOrZero val annualUsd: BigDecimal? = null,
    val isActive: Boolean? = null,
) {
    fun toCommand(planId: UUID) = UpdatePlanCommand(
        planId, name, maxProperties, maxRooms, maxUsers, maxOutlets,
        monthlyUsd, annualUsd, isActive,
    )
}

data class ReplaceEntitlementsHttpRequest(
    @field:NotEmpty val entitlements: List<EntitlementHttpRequest>,
)

data class EntitlementHttpRequest(
    @field:NotBlank @field:Pattern(regexp = "[a-z][a-z0-9_.-]{1,99}") val code: String,
    val enabled: Boolean,
    val value: Map<String, Any?> = emptyMap(),
)

data class ChangeSubscriptionHttpRequest(
    @field:NotNull val planId: UUID,
    @field:NotBlank val status: String,
    @field:NotBlank val billingCycle: String,
    @field:Pattern(regexp = "[A-Z]{3}") val billingCurrency: String,
    @field:NotBlank val provider: String,
    val providerCustomerId: String? = null,
    val providerSubscriptionId: String? = null,
    val currentPeriodEndsAt: Instant? = null,
    val trialEndsAt: Instant? = null,
    val gracePeriodEndsAt: Instant? = null,
    val cancelAtPeriodEnd: Boolean = false,
    @field:Positive val expectedVersion: Long? = null,
    @field:NotBlank val reason: String,
) {
    fun toCommand(tenantId: UUID) = ChangeTenantSubscriptionCommand(
        tenantId, planId, status, billingCycle, billingCurrency, provider,
        providerCustomerId, providerSubscriptionId, currentPeriodEndsAt,
        trialEndsAt, gracePeriodEndsAt, cancelAtPeriodEnd, expectedVersion, reason,
    )
}

data class EntitlementOverrideHttpRequest(
    @field:NotBlank @field:Pattern(regexp = "[a-z][a-z0-9_.-]{1,99}") val code: String,
    val enabled: Boolean,
    val value: Map<String, Any?> = emptyMap(),
    @field:NotBlank val reason: String,
    val expiresAt: Instant? = null,
)

data class ReasonHttpRequest(@field:NotBlank val reason: String)
