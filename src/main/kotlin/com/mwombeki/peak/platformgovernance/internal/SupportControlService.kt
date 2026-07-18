package com.mwombeki.peak.platformgovernance.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.PlatformAuditEvent
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.platformgovernance.api.AddSupportNoteCommand
import com.mwombeki.peak.platformgovernance.api.OpenSupportTicketCommand
import com.mwombeki.peak.platformgovernance.api.SupportControlConflictException
import com.mwombeki.peak.platformgovernance.api.SupportControlNotFoundException
import com.mwombeki.peak.platformgovernance.api.SupportControlPort
import com.mwombeki.peak.platformgovernance.api.SupportEventSummary
import com.mwombeki.peak.platformgovernance.api.SupportNoteSummary
import com.mwombeki.peak.platformgovernance.api.SupportTicketDetail
import com.mwombeki.peak.platformgovernance.api.SupportTicketQuery
import com.mwombeki.peak.platformgovernance.api.SupportTicketSummary
import com.mwombeki.peak.platformgovernance.api.UpdateSupportTicketCommand
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.PlatformAccessPort
import com.mwombeki.peak.usermanagement.api.PlatformAccessRequest
import com.mwombeki.peak.usermanagement.api.SupportPrivilegedAccessEventCommand
import com.mwombeki.peak.usermanagement.api.SupportPrivilegedAccessEvidencePort
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessPort
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessRequest
import java.sql.ResultSet
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class SupportControlService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val tenantAccess: TenantPermissionAccessPort,
    private val platformAccess: PlatformAccessPort,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val objectMapper: ObjectMapper,
) : SupportControlPort, SupportPrivilegedAccessEvidencePort {

    @Transactional
    override fun recordPrivilegedAccessEvent(command: SupportPrivilegedAccessEventCommand) {
        require(command.eventType in PRIVILEGED_ACCESS_EVENTS) {
            "Invalid privileged access support event"
        }
        val ticket = lockedTicket(command.tenantId, command.ticketId)
        if (ticket.status == "closed") {
            throw SupportControlConflictException("Closed ticket cannot authorize privileged access")
        }
        addEvent(
            command.ticketId, command.eventType, command.actorPlatformUserId, null, null,
            mapOf(
                "accessId" to command.accessId,
                "actionCode" to command.actionCode,
                "reason" to command.reason.take(1000),
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun listTickets(query: SupportTicketQuery): List<SupportTicketSummary> {
        require(query.limit in 1..200) { "Support ticket limit must be between 1 and 200" }
        if (query.platformView) {
            requirePlatform(query.tenantId, "platform.support.view", "platform.support.list")
        } else {
            val tenantId = requireNotNull(query.tenantId) { "Tenant ID is required" }
            requireTenant(tenantId, "tenant.support.manage", "tenant.support.list")
        }
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        query.tenantId?.let { conditions += "ticket.tenant_id = ?"; args += it }
        query.status?.let { conditions += "ticket.status = ?"; args += it.normalizedStatus() }
        query.priority?.let { conditions += "ticket.priority = ?"; args += it.normalizedPriority() }
        query.assignedPlatformUserId?.let {
            conditions += "ticket.assigned_platform_user_id = ?"; args += it
        }
        val where = conditions.takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE ").orEmpty()
        args += query.limit
        return jdbcTemplate.query(
            "$TICKET_SELECT $where ORDER BY ticket.updated_at DESC, ticket.id DESC LIMIT ?",
            { rs, _ -> mapTicket(rs) },
            *args.toTypedArray(),
        )
    }

    @Transactional(readOnly = true)
    override fun getTicket(
        tenantId: UUID,
        ticketId: UUID,
        platformView: Boolean,
    ): SupportTicketDetail {
        if (platformView) {
            requirePlatform(tenantId, "platform.support.view", "platform.support.view")
        } else {
            requireTenant(tenantId, "tenant.support.manage", "tenant.support.view")
        }
        return detail(tenantId, ticketId, platformView)
    }

    @Transactional
    override fun openTicket(command: OpenSupportTicketCommand): SupportTicketDetail {
        requireTenant(command.tenantId, "tenant.support.manage", "tenant.support.open")
        require(command.subject.isNotBlank() && command.subject.length <= 200) {
            "Support subject must be between 1 and 200 characters"
        }
        require(command.description.isNotBlank() && command.description.length <= 10_000) {
            "Support description must be between 1 and 10000 characters"
        }
        val priority = command.priority.normalizedPriority()
        val category = command.category.trim().lowercase().also {
            require(it.matches(SAFE_CATEGORY)) { "Invalid support category" }
        }
        return mutate(
            "tenant.support.open", command, "support_tickets", SupportTicketDetail::class.java,
        ) { reservationId ->
            command.propertyId?.let { propertyId ->
                val exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM properties WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL)",
                    Boolean::class.java, command.tenantId, propertyId,
                ) == true
                if (!exists) throw SupportControlNotFoundException("Property was not found")
            }
            val ticketId = UUID.randomUUID()
            val tenantUserId = currentTenantUser(command.tenantId)
            val ticketNumber = "SUP-${LocalDate.now(ZoneOffset.UTC).format(DAY_FORMAT)}-${ticketId.toString().take(8).uppercase()}"
            jdbcTemplate.update(
                """
                INSERT INTO support_tickets (
                    id, tenant_id, property_id, opened_by_user_id, ticket_number,
                    subject, description, priority, status, category, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, ?::jsonb)
                """.trimIndent(),
                ticketId, command.tenantId, command.propertyId, tenantUserId, ticketNumber,
                command.subject.trim(), command.description.trim(), priority, category,
                objectMapper.writeValueAsString(command.metadata),
            )
            addEvent(ticketId, "opened", null, tenantUserId, null, mapOf(
                "status" to "open", "priority" to priority, "category" to category,
            ))
            val result = detail(command.tenantId, ticketId, platformView = false)
            recordTenant(command.tenantId, "tenant.support.opened", ticketId, result.ticket, reservationId)
            result
        }
    }

    @Transactional
    override fun addNote(command: AddSupportNoteCommand): SupportTicketDetail {
        if (command.platformView) {
            requirePlatform(command.tenantId, "platform.support.manage", "platform.support.note")
        } else {
            requireTenant(command.tenantId, "tenant.support.manage", "tenant.support.note")
        }
        require(command.note.isNotBlank() && command.note.length <= 10_000) {
            "Support note must be between 1 and 10000 characters"
        }
        val visibility = command.visibility.trim().lowercase().also {
            require(it in NOTE_VISIBILITIES) { "Invalid support note visibility" }
            if (!command.platformView) require(it == "customer") {
                "Tenant users can only create customer-visible notes"
            }
        }
        return mutate(
            if (command.platformView) "platform.support.note" else "tenant.support.note",
            command, "support_ticket_notes", SupportTicketDetail::class.java,
        ) { reservationId ->
            val ticket = lockedTicket(command.tenantId, command.ticketId)
            if (ticket.status == "closed") {
                throw SupportControlConflictException("Closed support ticket cannot receive notes")
            }
            val noteId = UUID.randomUUID()
            val platformUserId = if (command.platformView) currentPlatformUser() else null
            val tenantUserId = if (command.platformView) null else currentTenantUser(command.tenantId)
            jdbcTemplate.update(
                """
                INSERT INTO support_ticket_notes (
                    id, ticket_id, platform_user_id, tenant_user_id, note, visibility
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                noteId, command.ticketId, platformUserId, tenantUserId,
                command.note.trim(), visibility,
            )
            addEvent(
                command.ticketId, "note_added", platformUserId, tenantUserId,
                null, mapOf("noteId" to noteId, "visibility" to visibility),
            )
            val result = detail(command.tenantId, command.ticketId, command.platformView)
            if (command.platformView) {
                recordPlatform(command.tenantId, "platform.support.note_added", command.ticketId,
                    mapOf("noteId" to noteId, "visibility" to visibility), reservationId)
            } else {
                recordTenant(command.tenantId, "tenant.support.note_added", command.ticketId,
                    mapOf("noteId" to noteId), reservationId)
            }
            result
        }
    }

    @Transactional
    override fun updateTicket(command: UpdateSupportTicketCommand): SupportTicketDetail {
        requirePlatform(command.tenantId, "platform.support.manage", "platform.support.update")
        require(command.reason.isNotBlank()) { "Support changes require a reason" }
        require(command.status != null || command.priority != null || command.assignedPlatformUserId != null) {
            "At least one support ticket field must be updated"
        }
        return mutate(
            "platform.support.update", command, "support_tickets", SupportTicketDetail::class.java,
        ) { reservationId ->
            val before = lockedTicket(command.tenantId, command.ticketId)
            val status = command.status?.normalizedStatus() ?: before.status
            val priority = command.priority?.normalizedPriority() ?: before.priority
            validateTransition(before.status, status)
            command.assignedPlatformUserId?.let { assignee ->
                val exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM platform_users WHERE id = ? AND status = 'active' AND deleted_at IS NULL)",
                    Boolean::class.java, assignee,
                ) == true
                if (!exists) throw SupportControlNotFoundException("Support assignee was not found")
            }
            jdbcTemplate.update(
                """
                UPDATE support_tickets
                SET status = ?, priority = ?,
                    assigned_platform_user_id = COALESCE(?, assigned_platform_user_id),
                    resolved_at = CASE WHEN ? IN ('resolved', 'closed')
                        THEN COALESCE(resolved_at, now()) ELSE NULL END
                WHERE tenant_id = ? AND id = ?
                """.trimIndent(),
                status, priority, command.assignedPlatformUserId, status,
                command.tenantId, command.ticketId,
            )
            val actor = currentPlatformUser()
            if (status != before.status) addEvent(
                command.ticketId,
                when {
                    before.status in setOf("resolved", "closed") -> "reopened"
                    status == "resolved" -> "resolved"
                    else -> "status_changed"
                },
                actor, null,
                mapOf("status" to before.status),
                mapOf("status" to status, "reason" to command.reason.trim()),
            )
            if (priority != before.priority) addEvent(
                command.ticketId, "priority_changed", actor, null,
                mapOf("priority" to before.priority),
                mapOf("priority" to priority, "reason" to command.reason.trim()),
            )
            if (command.assignedPlatformUserId != null &&
                command.assignedPlatformUserId != before.assignedPlatformUserId
            ) addEvent(
                command.ticketId, "assigned", actor, null,
                mapOf("assignedPlatformUserId" to before.assignedPlatformUserId),
                mapOf("assignedPlatformUserId" to command.assignedPlatformUserId,
                    "reason" to command.reason.trim()),
            )
            val result = detail(command.tenantId, command.ticketId, platformView = true)
            recordPlatform(command.tenantId, "platform.support.updated", command.ticketId,
                mapOf("status" to status, "priority" to priority,
                    "assignedPlatformUserId" to command.assignedPlatformUserId,
                    "reason" to command.reason.trim()), reservationId)
            result
        }
    }

    private fun detail(tenantId: UUID, ticketId: UUID, platformView: Boolean): SupportTicketDetail {
        val ticket = ticket(tenantId, ticketId)
        val description = jdbcTemplate.queryForObject(
            "SELECT description FROM support_tickets WHERE tenant_id = ? AND id = ?",
            String::class.java, tenantId, ticketId,
        )
        val noteVisibility = if (platformView) "" else "AND visibility = 'customer'"
        val notes = jdbcTemplate.query(
            """
            SELECT id, note, visibility, platform_user_id, tenant_user_id, created_at
            FROM support_ticket_notes WHERE ticket_id = ? $noteVisibility
            ORDER BY created_at, id
            """.trimIndent(),
            { rs, _ -> SupportNoteSummary(
                rs.getObject("id", UUID::class.java), rs.getString("note"),
                rs.getString("visibility"), rs.getObject("platform_user_id", UUID::class.java),
                rs.getObject("tenant_user_id", UUID::class.java), rs.getTimestamp("created_at").toInstant(),
            ) },
            ticketId,
        )
        val timeline = jdbcTemplate.query(
            """
            SELECT id, event_type, actor_platform_user_id, actor_tenant_user_id,
                   before_state, after_state, occurred_at
            FROM support_ticket_events WHERE ticket_id = ? ORDER BY occurred_at, id
            """.trimIndent(),
            { rs, _ -> SupportEventSummary(
                rs.getObject("id", UUID::class.java), rs.getString("event_type"),
                rs.getObject("actor_platform_user_id", UUID::class.java),
                rs.getObject("actor_tenant_user_id", UUID::class.java),
                rs.getString("before_state")?.let(::jsonMap),
                rs.getString("after_state")?.let(::jsonMap),
                rs.getTimestamp("occurred_at").toInstant(),
            ) },
            ticketId,
        )
        return SupportTicketDetail(ticket, description, notes, timeline)
    }

    private fun ticket(tenantId: UUID, ticketId: UUID): SupportTicketSummary = jdbcTemplate.query(
        "$TICKET_SELECT WHERE ticket.tenant_id = ? AND ticket.id = ?",
        { rs, _ -> mapTicket(rs) }, tenantId, ticketId,
    ).singleOrNull() ?: throw SupportControlNotFoundException("Support ticket was not found")

    private fun lockedTicket(tenantId: UUID, ticketId: UUID): SupportTicketSummary = jdbcTemplate.query(
        "$TICKET_SELECT WHERE ticket.tenant_id = ? AND ticket.id = ? FOR UPDATE",
        { rs, _ -> mapTicket(rs) }, tenantId, ticketId,
    ).singleOrNull() ?: throw SupportControlNotFoundException("Support ticket was not found")

    private fun mapTicket(rs: ResultSet) = SupportTicketSummary(
        rs.getObject("id", UUID::class.java), rs.getObject("tenant_id", UUID::class.java),
        rs.getObject("property_id", UUID::class.java), rs.getString("ticket_number"),
        rs.getString("subject"), rs.getString("priority"), rs.getString("status"),
        rs.getString("category"), rs.getObject("assigned_platform_user_id", UUID::class.java),
        rs.getTimestamp("opened_at").toInstant(), rs.getTimestamp("resolved_at")?.toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
    )

    private fun addEvent(
        ticketId: UUID,
        eventType: String,
        platformUserId: UUID?,
        tenantUserId: UUID?,
        before: Map<String, Any?>?,
        after: Map<String, Any?>?,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO support_ticket_events (
                ticket_id, event_type, actor_platform_user_id, actor_tenant_user_id,
                before_state, after_state
            ) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """.trimIndent(),
            ticketId, eventType, platformUserId, tenantUserId,
            before?.let(objectMapper::writeValueAsString),
            after?.let(objectMapper::writeValueAsString),
        )
    }

    private fun recordTenant(
        tenantId: UUID, action: String, ticketId: UUID, payload: Any, reservationId: UUID,
    ) {
        auditPort.recordTenantEvent(TenantAuditEvent(
            tenantId, action, AuditResource("support_tickets", ticketId),
            after = mapOf("payload" to payload),
        ))
        enqueue(tenantId, action, ticketId, payload, reservationId)
    }

    private fun recordPlatform(
        tenantId: UUID, action: String, ticketId: UUID, payload: Any, reservationId: UUID,
    ) {
        auditPort.recordPlatformEvent(PlatformAuditEvent(
            action = action, resource = AuditResource("support_tickets", ticketId),
            targetTenantId = tenantId, after = mapOf("payload" to payload),
        ))
        enqueue(tenantId, action, ticketId, payload, reservationId)
    }

    private fun enqueue(tenantId: UUID, action: String, ticketId: UUID, payload: Any, key: UUID) {
        outboxPort.enqueue(OutboxEventCommand(
            aggregateType = "support_tickets", aggregateId = ticketId,
            tenantId = when (requestContextHolder.current().identity) {
                is RequestIdentity.Tenant -> tenantId
                else -> null
            },
            eventType = action, destination = OutboxDestination.PLATFORM,
            payload = payload, idempotencyKeyId = key, priority = 3,
        ))
    }

    private fun <T : Any> mutate(
        operation: String,
        payload: Any,
        resourceType: String,
        responseType: Class<T>,
        block: (UUID) -> T,
    ): T = when (val reservation = idempotencyPort.reserve(
        IdempotencyCommand(operation, payload, resourceType),
    )) {
        is IdempotencyReservation.Started -> block(reservation.recordId).also {
            idempotencyPort.markSucceeded(reservation.recordId, 200, it, resourceId(it))
        }
        is IdempotencyReservation.Replay -> objectMapper.readValue(
            requireNotNull(reservation.responseBody) { "Stored support response is missing" }, responseType,
        )
        is IdempotencyReservation.InProgress -> throw SupportControlConflictException(
            "Support command is already in progress",
        )
        is IdempotencyReservation.Conflict -> throw SupportControlConflictException(
            "Idempotency key was used for another support command",
        )
    }

    private fun resourceId(value: Any): UUID? = when (value) {
        is SupportTicketDetail -> value.ticket.ticketId
        else -> null
    }

    private fun requireTenant(tenantId: UUID, permission: String, operation: String) {
        tenantAccess.requireAuthorized(TenantPermissionAccessRequest(tenantId, permission, operation))
    }

    private fun requirePlatform(tenantId: UUID?, permission: String, operation: String) {
        platformAccess.requireAuthorized(PlatformAccessRequest(tenantId, permission, operation))
    }

    private fun currentTenantUser(tenantId: UUID): UUID = when (
        val identity = requestContextHolder.current().identity
    ) {
        is RequestIdentity.Tenant -> identity.tenantUserId.also {
            require(identity.tenantId == tenantId) { "Tenant identity does not match support ticket" }
        }
        else -> throw IllegalStateException("Tenant identity is required")
    }

    private fun currentPlatformUser(): UUID = when (val identity = requestContextHolder.current().identity) {
        is RequestIdentity.Platform -> identity.platformUserId
        is RequestIdentity.Support -> identity.platformUserId
        else -> throw IllegalStateException("Platform identity is required")
    }

    private fun validateTransition(before: String, after: String) {
        if (before == after) return
        val allowed = STATUS_TRANSITIONS[before].orEmpty()
        if (after !in allowed) throw SupportControlConflictException(
            "Support ticket cannot transition from $before to $after",
        )
    }

    private fun String.normalizedStatus() = trim().lowercase().also {
        require(it in SUPPORT_STATUSES) { "Invalid support ticket status" }
    }

    private fun String.normalizedPriority() = trim().lowercase().also {
        require(it in SUPPORT_PRIORITIES) { "Invalid support ticket priority" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonMap(value: String): Map<String, Any?> =
        objectMapper.readValue(value, Map::class.java) as Map<String, Any?>

    private companion object {
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        val SAFE_CATEGORY = Regex("[a-z][a-z0-9_-]{1,39}")
        val NOTE_VISIBILITIES = setOf("internal", "customer")
        val PRIVILEGED_ACCESS_EVENTS = setOf(
            "access_requested", "access_approved", "access_activated", "access_revoked",
        )
        val SUPPORT_PRIORITIES = setOf("low", "normal", "high", "urgent")
        val SUPPORT_STATUSES = setOf(
            "open", "triaged", "in_progress", "waiting_customer", "resolved", "closed",
        )
        val STATUS_TRANSITIONS = mapOf(
            "open" to setOf("triaged", "in_progress", "resolved", "closed"),
            "triaged" to setOf("in_progress", "waiting_customer", "resolved", "closed"),
            "in_progress" to setOf("waiting_customer", "resolved", "closed"),
            "waiting_customer" to setOf("in_progress", "resolved", "closed"),
            "resolved" to setOf("open", "in_progress", "closed"),
            "closed" to setOf("open"),
        )
        val TICKET_SELECT = """
            SELECT ticket.id, ticket.tenant_id, ticket.property_id, ticket.ticket_number,
                   ticket.subject, ticket.priority, ticket.status, ticket.category,
                   ticket.assigned_platform_user_id, ticket.opened_at,
                   ticket.resolved_at, ticket.updated_at
            FROM support_tickets ticket
        """.trimIndent()
    }
}
