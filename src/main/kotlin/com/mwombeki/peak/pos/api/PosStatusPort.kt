package com.mwombeki.peak.pos.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface PosStatusPort {
    fun nightAuditSummary(tenantId: UUID, propertyId: UUID): PosNightAuditSummary
}

@NamedInterface("api")
data class PosNightAuditSummary(
    val openOrUnapprovedSessions: Int,
)
