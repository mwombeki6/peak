package com.mwombeki.peak.audit.api

import java.time.Instant
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface PlatformAuditQueryPort {
    fun tenantTimeline(tenantId: UUID, limit: Int = 100): List<PlatformAuditTimelineEntry>
}

@NamedInterface("api")
data class PlatformAuditTimelineEntry(
    val id: UUID,
    val platformUserId: UUID?,
    val action: String,
    val entityType: String,
    val entityId: UUID?,
    val outcome: String,
    val correlationId: String,
    val createdAt: Instant,
)
