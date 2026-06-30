package com.mwombeki.peak.nightaudit.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface NightAuditPort {
    fun runNightAudit(propertyId: UUID, request: RunNightAuditRequest): NightAuditRunResponse
    fun listRuns(propertyId: UUID): List<NightAuditRunResponse>
    fun getRun(propertyId: UUID, runId: UUID): NightAuditRunResponse?
}
