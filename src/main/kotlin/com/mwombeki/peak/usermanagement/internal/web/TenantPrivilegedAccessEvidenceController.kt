package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.usermanagement.api.TenantPrivilegedAccessEvent
import com.mwombeki.peak.usermanagement.api.TenantPrivilegedAccessEvidencePort
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Lets a tenant read every privileged Peak staff access to their own account.
 *
 * The `tenantId` in the path is matched against the caller's identity by the
 * route guard, and the underlying view filters on the bound database session,
 * so this endpoint cannot return another tenant's history even if the path were
 * wrong.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/privileged-access")
class TenantPrivilegedAccessEvidenceController(
    private val evidencePort: TenantPrivilegedAccessEvidencePort,
) {
    @GetMapping
    fun listPrivilegedAccess(
        @PathVariable tenantId: UUID,
        @RequestParam(defaultValue = "200") @Min(1) @Max(500) limit: Int,
    ): List<TenantPrivilegedAccessEvent> {
        return evidencePort.listEvidence(limit)
    }
}
