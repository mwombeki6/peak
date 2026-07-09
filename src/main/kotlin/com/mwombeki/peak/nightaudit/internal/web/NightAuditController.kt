package com.mwombeki.peak.nightaudit.internal.web

import com.mwombeki.peak.nightaudit.api.NightAuditNotFoundException
import com.mwombeki.peak.nightaudit.api.NightAuditPort
import com.mwombeki.peak.nightaudit.api.NightAuditRunResponse
import com.mwombeki.peak.nightaudit.api.NightAuditCloseSnapshotResponse
import com.mwombeki.peak.nightaudit.api.OverrideNightAuditIssueRequest
import com.mwombeki.peak.nightaudit.api.RunNightAuditRequest
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/night-audit")
class NightAuditController(
    private val nightAuditPort: NightAuditPort,
) {
    @PostMapping
    fun runNightAudit(
        @PathVariable propertyId: UUID,
        @RequestBody request: RunNightAuditRequest,
    ): NightAuditRunResponse {
        return nightAuditPort.runNightAudit(propertyId, request)
    }

    @GetMapping
    fun listRuns(@PathVariable propertyId: UUID): List<NightAuditRunResponse> {
        return nightAuditPort.listRuns(propertyId)
    }

    @GetMapping("/{runId}")
    fun getRun(
        @PathVariable propertyId: UUID,
        @PathVariable runId: UUID,
    ): NightAuditRunResponse {
        return nightAuditPort.getRun(propertyId, runId)
            ?: throw NightAuditNotFoundException("Night audit run was not found")
    }

    @PostMapping("/{runId}/issues/{issueId}/override")
    fun overrideIssue(
        @PathVariable propertyId: UUID,
        @PathVariable runId: UUID,
        @PathVariable issueId: UUID,
        @RequestBody request: OverrideNightAuditIssueRequest,
    ): NightAuditRunResponse {
        return nightAuditPort.overrideIssue(propertyId, runId, issueId, request)
    }

    @PostMapping("/{runId}/complete")
    fun complete(
        @PathVariable propertyId: UUID,
        @PathVariable runId: UUID,
    ): NightAuditRunResponse {
        return nightAuditPort.complete(propertyId, runId)
    }

    @GetMapping("/{runId}/close-snapshot")
    fun getCloseSnapshot(
        @PathVariable propertyId: UUID,
        @PathVariable runId: UUID,
    ): NightAuditCloseSnapshotResponse {
        return nightAuditPort.getCloseSnapshot(propertyId, runId)
            ?: throw NightAuditNotFoundException(
                "Night audit close snapshot was not found",
            )
    }
}
