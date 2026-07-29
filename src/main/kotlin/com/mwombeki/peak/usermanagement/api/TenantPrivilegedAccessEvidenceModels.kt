package com.mwombeki.peak.usermanagement.api

import java.time.Instant
import java.util.UUID
import org.springframework.modulith.NamedInterface

/**
 * Tenant-facing record of privileged Peak staff access to this tenant.
 *
 * The promise this serves is that a hotel can see, without asking, every time
 * Peak entered their operational world: what was requested, whether it was
 * approved, when it became active, every use it was put to, and when it ended.
 */
@NamedInterface("api")
interface TenantPrivilegedAccessEvidencePort {
    fun listEvidence(limit: Int = 200): List<TenantPrivilegedAccessEvent>
}

@NamedInterface("api")
data class TenantPrivilegedAccessEvent(
    val accessId: UUID,
    val supportTicketId: UUID?,
    val operatorName: String,
    val actionCode: String,
    val operationCode: String?,
    val reason: String,
    val eventType: String,
    val occurredAt: Instant,
    val startsAt: Instant,
    val expiresAt: Instant,
    val maxUses: Int,
    val useCount: Int,
    val denialReason: String?,
)
