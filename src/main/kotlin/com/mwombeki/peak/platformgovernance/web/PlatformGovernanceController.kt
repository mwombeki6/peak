package com.mwombeki.peak.platformgovernance.web

import com.mwombeki.peak.platformgovernance.api.TenantGovernancePort
import com.mwombeki.peak.platformgovernance.api.GovernanceActionResponse
// Using Coder A's official shared request tracking system
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/platform/governance/tenants")
class PlatformGovernanceController(
    private val governancePort: TenantGovernancePort,
    private val requestContextHolder: RequestContextHolder // Injecting Coder A's thread context tool
) {

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ROLE_PLATFORM_SUPER_ADMIN', 'ROLE_PLATFORM_OPERATOR')")
    fun approveHotel(
        @PathVariable id: UUID,
        @RequestBody request: GovernanceActionRequest
    ): ResponseEntity<Any> {

        // Extract Coder A's active request identity
        val context = requestContextHolder.current()
        val identity = context.identity

        // Safely extract the platform admin's ID if it matches a Platform identity type
        val activeOperatorId = when (identity) {
            is RequestIdentity.Platform -> identity.platformUserId
            is RequestIdentity.Support -> identity.platformUserId
            else -> throw IllegalStateException("Security Violation: Only platform administrators can perform this governance action.")
        }

        val result = governancePort.approveTenant(id, activeOperatorId, request.reason)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAnyRole('ROLE_PLATFORM_SUPER_ADMIN', 'ROLE_PLATFORM_OPERATOR')")
    fun suspendHotel(
        @PathVariable id: UUID,
        @RequestBody request: GovernanceActionRequest
    ): ResponseEntity<Any> {

        val context = requestContextHolder.current()
        val identity = context.identity

        val activeOperatorId = when (identity) {
            is RequestIdentity.Platform -> identity.platformUserId
            is RequestIdentity.Support -> identity.platformUserId
            else -> throw IllegalStateException("Security Violation: Only platform administrators can perform this governance action.")
        }

        val result = governancePort.suspendTenant(id, activeOperatorId, request.reason)
        return ResponseEntity.ok(result)
    }
}

data class GovernanceActionRequest(
    val reason: String
)