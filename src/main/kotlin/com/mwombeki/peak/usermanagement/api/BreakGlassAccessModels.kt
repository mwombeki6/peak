package com.mwombeki.peak.usermanagement.api

import java.time.Instant
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface BreakGlassAccessPort {
    fun listAccess(tenantId: UUID?, status: String?, limit: Int): List<BreakGlassAccessSummary>
    fun requestAccess(command: RequestBreakGlassAccessCommand): BreakGlassAccessSummary
    fun decideAccess(command: DecideBreakGlassAccessCommand): BreakGlassAccessSummary
    fun activateAccess(accessId: UUID): BreakGlassAccessSummary
    fun revokeAccess(accessId: UUID, reason: String): BreakGlassAccessSummary
}

/**
 * Consumer-owned port for attaching privileged-access evidence to the support
 * case that authorized it.
 */
@NamedInterface("api")
interface SupportPrivilegedAccessEvidencePort {
    fun recordPrivilegedAccessEvent(command: SupportPrivilegedAccessEventCommand)
}

data class SupportPrivilegedAccessEventCommand(
    val tenantId: UUID,
    val ticketId: UUID,
    val eventType: String,
    val accessId: UUID,
    val actorPlatformUserId: UUID,
    val actionCode: String,
    val reason: String,
)

data class BreakGlassAccessSummary(
    val accessId: UUID,
    val platformUserId: UUID,
    val tenantId: UUID,
    val supportTicketId: UUID,
    val actionCode: String,
    val reason: String,
    val status: String,
    val requestedAt: Instant,
    val approvedBy: UUID?,
    val approvedAt: Instant?,
    val activatedAt: Instant?,
    val startsAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val maxUses: Int,
    val useCount: Int,
    val lastUsedAt: Instant?,
    val assuranceLevel: String,
    val decisionReason: String?,
)

data class RequestBreakGlassAccessCommand(
    val tenantId: UUID,
    val supportTicketId: UUID,
    val actionCode: String,
    val reason: String,
    val durationMinutes: Long,
    val maxUses: Int,
    val assuranceLevel: String,
)

enum class BreakGlassDecision { APPROVE, DENY }

data class DecideBreakGlassAccessCommand(
    val accessId: UUID,
    val decision: BreakGlassDecision,
    val reason: String,
)

class BreakGlassNotFoundException(message: String) : RuntimeException(message)
class BreakGlassConflictException(message: String) : RuntimeException(message)
