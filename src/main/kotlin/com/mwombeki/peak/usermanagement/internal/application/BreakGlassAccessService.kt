package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.PlatformAuditEvent
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.AssuranceLevel
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.BreakGlassAccessPort
import com.mwombeki.peak.usermanagement.api.BreakGlassAccessSummary
import com.mwombeki.peak.usermanagement.api.BreakGlassConflictException
import com.mwombeki.peak.usermanagement.api.BreakGlassDecision
import com.mwombeki.peak.usermanagement.api.BreakGlassNotFoundException
import com.mwombeki.peak.usermanagement.api.DecideBreakGlassAccessCommand
import com.mwombeki.peak.usermanagement.api.PlatformAccessPort
import com.mwombeki.peak.usermanagement.api.PlatformAccessRequest
import com.mwombeki.peak.usermanagement.api.RequestBreakGlassAccessCommand
import com.mwombeki.peak.usermanagement.api.SupportPrivilegedAccessEventCommand
import com.mwombeki.peak.usermanagement.api.SupportPrivilegedAccessEvidencePort
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class BreakGlassAccessService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val platformAccess: PlatformAccessPort,
    private val supportEvidence: SupportPrivilegedAccessEvidencePort,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val objectMapper: ObjectMapper,
    private val stepUpPolicy: PrivilegedStepUpPolicy,
) : BreakGlassAccessPort {

    @Transactional(readOnly = true)
    override fun listAccess(
        tenantId: UUID?,
        status: String?,
        limit: Int,
    ): List<BreakGlassAccessSummary> {
        require(limit in 1..200) { "Access request limit must be between 1 and 200" }
        requirePlatform(tenantId, "platform.support.access.view", "platform.support.access.list")
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        tenantId?.let { clauses += "access.tenant_id = ?"; args += it }
        status?.let {
            val normalized = it.normalizedStatus()
            clauses += "access.status = ?"; args += normalized
        }
        val where = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE ").orEmpty()
        args += limit
        return jdbcTemplate.query(
            "$ACCESS_SELECT $where ORDER BY access.requested_at DESC, access.id DESC LIMIT ?",
            { rs, _ -> mapAccess(rs) }, *args.toTypedArray(),
        )
    }

    @Transactional
    override fun requestAccess(command: RequestBreakGlassAccessCommand): BreakGlassAccessSummary {
        requirePlatform(
            command.tenantId, "platform.support.access.request", "platform.support.access.request",
        )
        requirePlatform(command.tenantId, command.actionCode, "platform.support.target_permission")
        require(command.reason.isNotBlank() && command.reason.length <= 1000) {
            "Privileged access reason must be between 1 and 1000 characters"
        }
        require(command.durationMinutes in 1..120) {
            "Privileged access duration must be between 1 and 120 minutes"
        }
        require(command.maxUses in 1..1000) { "Privileged access max uses must be between 1 and 1000" }
        require(command.actionCode.matches(PERMISSION_CODE)) { "Invalid privileged access action code" }
        // The requested level is a ceiling request, never proof. What is stored
        // and enforced is the level the validated token actually achieved.
        val requested = AssuranceLevel.fromPolicy(command.assuranceLevel)
        val achieved = requireAssurance(requested)
        val assurance = achieved.databaseValue()
        val platformUserId = currentPlatformUser()
        return mutate(
            "platform.support.access.request", command, BreakGlassAccessSummary::class.java,
        ) { reservationId ->
            val ticketExists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM support_tickets
                    WHERE id = ? AND tenant_id = ? AND status NOT IN ('resolved', 'closed')
                )
                """.trimIndent(),
                Boolean::class.java, command.supportTicketId, command.tenantId,
            ) == true
            if (!ticketExists) throw BreakGlassConflictException(
                "An open support ticket for the target tenant is required",
            )
            val accessId = UUID.randomUUID()
            val startsAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val expiresAt = startsAt.plus(command.durationMinutes, ChronoUnit.MINUTES)
            jdbcTemplate.update(
                """
                INSERT INTO platform_break_glass_access (
                    id, platform_user_id, tenant_id, support_ticket_id, action_code,
                    reason, status, starts_at, expires_at, max_uses, assurance_level
                ) VALUES (?, ?, ?, ?, ?, ?, 'requested', ?, ?, ?, ?)
                """.trimIndent(),
                accessId, platformUserId, command.tenantId, command.supportTicketId,
                command.actionCode, command.reason.trim(), Timestamp.from(startsAt),
                Timestamp.from(expiresAt),
                command.maxUses, assurance,
            )
            supportEvidence.recordPrivilegedAccessEvent(SupportPrivilegedAccessEventCommand(
                command.tenantId, command.supportTicketId, "access_requested", accessId,
                platformUserId, command.actionCode, command.reason.trim(),
            ))
            access(accessId).also {
                record(it, "platform.support.access.requested", command.reason, reservationId)
            }
        }
    }

    @Transactional
    override fun decideAccess(command: DecideBreakGlassAccessCommand): BreakGlassAccessSummary {
        require(command.reason.isNotBlank()) { "Privileged access decision reason is required" }
        val candidate = access(command.accessId)
        requirePlatform(
            candidate.tenantId, "platform.support.access.approve", "platform.support.access.decide",
        )
        val approver = currentPlatformUser()
        // Approving is itself a privileged act and needs a proven fresh
        // ceremony at least as strong as the access being approved.
        requireAssurance(AssuranceLevel.fromPolicy(candidate.assuranceLevel))
        if (approver == candidate.platformUserId) {
            throw BreakGlassConflictException("Privileged access cannot be self-approved")
        }
        return mutate(
            "platform.support.access.decide", command, BreakGlassAccessSummary::class.java,
        ) { reservationId ->
            val locked = lockedAccess(command.accessId)
            if (locked.status != "requested") {
                throw BreakGlassConflictException("Only requested access can be decided")
            }
            when (command.decision) {
                BreakGlassDecision.APPROVE -> jdbcTemplate.update(
                    """
                    UPDATE platform_break_glass_access
                    SET status = 'approved', approved_by = ?, approved_at = now(),
                        starts_at = now(), expires_at = now() + (expires_at - starts_at),
                        decision_reason = ?
                    WHERE id = ?
                    """.trimIndent(),
                    approver, command.reason.trim(), command.accessId,
                )
                BreakGlassDecision.DENY -> jdbcTemplate.update(
                    """
                    UPDATE platform_break_glass_access
                    SET status = 'denied', denied_at = now(), decision_reason = ?
                    WHERE id = ?
                    """.trimIndent(),
                    command.reason.trim(), command.accessId,
                )
            }
            val event = if (command.decision == BreakGlassDecision.APPROVE) {
                "access_approved"
            } else {
                "access_revoked"
            }
            supportEvidence.recordPrivilegedAccessEvent(SupportPrivilegedAccessEventCommand(
                locked.tenantId, locked.supportTicketId, event, locked.accessId,
                approver, locked.actionCode, command.reason.trim(),
            ))
            access(command.accessId).also {
                record(it, "platform.support.access.${command.decision.name.lowercase()}",
                    command.reason, reservationId)
            }
        }
    }

    @Transactional
    override fun activateAccess(accessId: UUID): BreakGlassAccessSummary {
        val candidate = access(accessId)
        requirePlatform(
            candidate.tenantId, "platform.support.access.activate", "platform.support.access.activate",
        )
        val actor = currentPlatformUser()
        require(actor == candidate.platformUserId) {
            "Only the requesting operator can activate privileged access"
        }
        // Activation starts the clock on real access, so it requires a fresh
        // ceremony at the strength the grant was approved for.
        requireAssurance(AssuranceLevel.fromPolicy(candidate.assuranceLevel))
        return mutate(
            "platform.support.access.activate", mapOf("accessId" to accessId),
            BreakGlassAccessSummary::class.java,
        ) { reservationId ->
            val locked = lockedAccess(accessId)
            if (locked.status != "approved") {
                throw BreakGlassConflictException("Only approved access can be activated")
            }
            if (!locked.expiresAt.isAfter(Instant.now())) {
                jdbcTemplate.update(
                    "UPDATE platform_break_glass_access SET status = 'expired' WHERE id = ?", accessId,
                )
                throw BreakGlassConflictException("Approved access has expired")
            }
            jdbcTemplate.update(
                """
                UPDATE platform_break_glass_access
                SET status = 'active', activated_at = now(), activated_by = ?
                WHERE id = ?
                """.trimIndent(), actor, accessId,
            )
            supportEvidence.recordPrivilegedAccessEvent(SupportPrivilegedAccessEventCommand(
                locked.tenantId, locked.supportTicketId, "access_activated", accessId,
                actor, locked.actionCode, "Approved access activated",
            ))
            access(accessId).also {
                record(it, "platform.support.access.activated", "Approved access activated", reservationId)
                // Activation is the moment access becomes usable, so it is the
                // moment the tenant is told. Enqueued in the same transaction
                // as the state change, so a notice cannot be lost by a later
                // failure, and the worker supplies retry and delivery evidence.
                notifyTenantOfPrivilegedAccess(it, reservationId)
            }
        }
    }

    @Transactional
    override fun revokeAccess(accessId: UUID, reason: String): BreakGlassAccessSummary {
        require(reason.isNotBlank()) { "Privileged access revocation reason is required" }
        val candidate = access(accessId)
        requirePlatform(
            candidate.tenantId, "platform.support.access.revoke", "platform.support.access.revoke",
        )
        return mutate(
            "platform.support.access.revoke", mapOf("accessId" to accessId, "reason" to reason),
            BreakGlassAccessSummary::class.java,
        ) { reservationId ->
            val locked = lockedAccess(accessId)
            if (locked.status !in setOf("requested", "approved", "active")) {
                throw BreakGlassConflictException("Access is already terminal")
            }
            jdbcTemplate.update(
                """
                UPDATE platform_break_glass_access
                SET status = 'revoked', revoked_at = now(), decision_reason = ?
                WHERE id = ?
                """.trimIndent(), reason.trim(), accessId,
            )
            val actor = currentPlatformUser()
            supportEvidence.recordPrivilegedAccessEvent(SupportPrivilegedAccessEventCommand(
                locked.tenantId, locked.supportTicketId, "access_revoked", accessId,
                actor, locked.actionCode, reason.trim(),
            ))
            access(accessId).also {
                record(it, "platform.support.access.revoked", reason, reservationId)
            }
        }
    }

    private fun access(id: UUID): BreakGlassAccessSummary = jdbcTemplate.query(
        "$ACCESS_SELECT WHERE access.id = ?", { rs, _ -> mapAccess(rs) }, id,
    ).singleOrNull() ?: throw BreakGlassNotFoundException("Privileged access request was not found")

    private fun lockedAccess(id: UUID): BreakGlassAccessSummary = jdbcTemplate.query(
        "$ACCESS_SELECT WHERE access.id = ? FOR UPDATE", { rs, _ -> mapAccess(rs) }, id,
    ).singleOrNull() ?: throw BreakGlassNotFoundException("Privileged access request was not found")

    private fun mapAccess(rs: ResultSet) = BreakGlassAccessSummary(
        rs.getObject("id", UUID::class.java), rs.getObject("platform_user_id", UUID::class.java),
        rs.getObject("tenant_id", UUID::class.java), rs.getObject("support_ticket_id", UUID::class.java),
        rs.getString("action_code"), rs.getString("reason"), rs.getString("status"),
        rs.getTimestamp("requested_at").toInstant(), rs.getObject("approved_by", UUID::class.java),
        rs.getTimestamp("approved_at")?.toInstant(), rs.getTimestamp("activated_at")?.toInstant(),
        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
        rs.getTimestamp("revoked_at")?.toInstant(), rs.getInt("max_uses"), rs.getInt("use_count"),
        rs.getTimestamp("last_used_at")?.toInstant(), rs.getString("assurance_level"),
        rs.getString("decision_reason"),
    )

    /**
     * Tells the tenant that Peak staff access to their data has become active.
     *
     * Routed through the ordinary notification outbox so the notice inherits
     * durability, retry and per-attempt delivery evidence rather than being a
     * best-effort side call. The purpose is `security_notifications`, whose
     * delivery basis is legitimate interest, so a recipient cannot silence it
     * by withholding consent; the worker still re-checks eligibility, so a
     * deactivated or unverified channel is dropped.
     *
     * The content is deliberately factual: which ticket, which operation, when
     * it expires. Internal decision notes and the operator's reasoning are not
     * included.
     */
    private fun notifyTenantOfPrivilegedAccess(
        access: BreakGlassAccessSummary,
        reservationId: UUID,
    ) {
        val channelIds = jdbcTemplate.query(
            """
            SELECT channel.id
            FROM contact_channels channel
            JOIN tenant_contacts contact
              ON contact.tenant_id = channel.tenant_id
             AND contact.id = channel.contact_id
             AND contact.status = 'active'
             AND contact.deleted_at IS NULL
            WHERE channel.tenant_id = ?
              AND channel.is_active = true
              AND channel.verification_status = 'verified'
              AND channel.deleted_at IS NULL
              AND contact_channel_can_receive(
                    channel.tenant_id, channel.contact_id, channel.id,
                    'security_notifications'
                  )
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            access.tenantId,
        )

        channelIds.forEach { channelId ->
            outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = "platform_break_glass_access",
                    aggregateId = access.accessId,
                    tenantId = access.tenantId,
                    eventType = "platform.support.access.tenant_notified",
                    destination = OutboxDestination.NOTIFICATION,
                    payload = mapOf(
                        "contactChannelId" to channelId,
                        "purpose" to "security_notifications",
                        "subject" to "Peak support access to your account is active",
                        "content" to buildString {
                            append("Peak support has activated approved access to your account ")
                            append("under support ticket ")
                            append(access.supportTicketId)
                            append(". Permitted operation: ")
                            append(access.actionCode)
                            append(". Access expires at ")
                            append(access.expiresAt)
                            append(". You can review the full access record in your ")
                            append("privileged access evidence timeline.")
                        },
                    ),
                    idempotencyKeyId = reservationId,
                    priority = 2,
                ),
            )
        }
    }

    private fun record(
        access: BreakGlassAccessSummary, action: String, reason: String, reservationId: UUID,
    ) {
        val payload = mapOf(
            "tenantId" to access.tenantId, "supportTicketId" to access.supportTicketId,
            "actionCode" to access.actionCode, "status" to access.status,
            "expiresAt" to access.expiresAt, "reason" to reason.trim().take(1000),
        )
        auditPort.recordPlatformEvent(PlatformAuditEvent(
            action = action,
            resource = AuditResource("platform_break_glass_access", access.accessId),
            targetTenantId = access.tenantId,
            after = payload,
        ))
        outboxPort.enqueue(OutboxEventCommand(
            aggregateType = "platform_break_glass_access", aggregateId = access.accessId,
            tenantId = null, eventType = action,
            destination = OutboxDestination.PLATFORM, payload = payload,
            idempotencyKeyId = reservationId, priority = 4,
        ))
    }

    private fun <T : Any> mutate(
        operation: String, payload: Any, responseType: Class<T>, block: (UUID) -> T,
    ): T = when (val reservation = idempotencyPort.reserve(
        IdempotencyCommand(operation, payload, "platform_break_glass_access"),
    )) {
        is IdempotencyReservation.Started -> block(reservation.recordId).also {
            idempotencyPort.markSucceeded(reservation.recordId, 200, it,
                (it as? BreakGlassAccessSummary)?.accessId)
        }
        is IdempotencyReservation.Replay -> objectMapper.readValue(
            requireNotNull(reservation.responseBody) { "Stored privileged access response is missing" },
            responseType,
        )
        is IdempotencyReservation.InProgress -> throw BreakGlassConflictException(
            "Privileged access command is already in progress",
        )
        is IdempotencyReservation.Conflict -> throw BreakGlassConflictException(
            "Idempotency key was used for another privileged access command",
        )
    }

    private fun requirePlatform(tenantId: UUID?, permission: String, operation: String) {
        platformAccess.requireAuthorized(PlatformAccessRequest(tenantId, permission, operation))
    }

    /**
     * Verifies the authentication ceremony behind this request against a policy
     * requirement and returns the level actually achieved.
     *
     * The previous implementation read `platform_users.mfa_enabled`, which
     * records that an operator once enrolled a second factor. It proves nothing
     * about the current request: a token minted through a password-only flow
     * satisfied it. `mfa_enabled` is now informational only and must never
     * authorize privileged access.
     */
    /**
     * Verifies the ceremony behind this request through the shared policy.
     *
     * The previous implementation read `platform_users.mfa_enabled`, which
     * records that an operator once enrolled a second factor and proves nothing
     * about the current request: a token minted through a password-only flow
     * satisfied it. `mfa_enabled` is now informational only and must never
     * authorize privileged access.
     */
    private fun requireAssurance(required: AssuranceLevel): AssuranceLevel =
        stepUpPolicy.require(required, STEP_UP_MAX_AGE) { message ->
            BreakGlassConflictException(message)
        }

    private fun AssuranceLevel.databaseValue(): String = when (this) {
        AssuranceLevel.PHISHING_RESISTANT -> "phishing_resistant"
        AssuranceLevel.MFA -> "mfa"
        AssuranceLevel.NONE -> throw BreakGlassConflictException(
            "Privileged access requires proven multi-factor authentication",
        )
    }

    private fun currentPlatformUser(): UUID = when (val identity = requestContextHolder.current().identity) {
        is RequestIdentity.Platform -> identity.platformUserId
        else -> throw IllegalStateException("A direct platform identity is required")
    }

    private fun String.normalizedStatus() = trim().lowercase().also {
        require(it in ACCESS_STATUSES) { "Invalid privileged access status" }
    }

    private companion object {
        val ACCESS_STATUSES = setOf(
            "requested", "approved", "active", "denied", "revoked", "expired", "exhausted",
        )

        /**
         * How recently the authentication ceremony must have happened for a
         * privileged action. Short enough that a walked-away session cannot
         * request, approve or activate access.
         */
        val STEP_UP_MAX_AGE: Duration = Duration.ofMinutes(5)
        val PERMISSION_CODE = Regex("[a-z][a-z0-9_.-]{2,99}")
        val ACCESS_SELECT = """
            SELECT access.id, access.platform_user_id, access.tenant_id,
                   access.support_ticket_id, access.action_code, access.reason,
                   access.status, access.requested_at, access.approved_by,
                   access.approved_at, access.activated_at, access.starts_at,
                   access.expires_at, access.revoked_at, access.max_uses,
                   access.use_count, access.last_used_at, access.assurance_level,
                   access.decision_reason
            FROM platform_break_glass_access access
        """.trimIndent()
    }
}
