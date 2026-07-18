package com.mwombeki.peak.platformgovernance.api

import java.time.Instant
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface SupportControlPort {
    fun listTickets(query: SupportTicketQuery): List<SupportTicketSummary>
    fun getTicket(tenantId: UUID, ticketId: UUID, platformView: Boolean): SupportTicketDetail
    fun openTicket(command: OpenSupportTicketCommand): SupportTicketDetail
    fun addNote(command: AddSupportNoteCommand): SupportTicketDetail
    fun updateTicket(command: UpdateSupportTicketCommand): SupportTicketDetail
}

data class SupportTicketQuery(
    val tenantId: UUID?,
    val status: String?,
    val priority: String?,
    val assignedPlatformUserId: UUID?,
    val platformView: Boolean,
    val limit: Int = 100,
)

data class SupportTicketSummary(
    val ticketId: UUID,
    val tenantId: UUID?,
    val propertyId: UUID?,
    val ticketNumber: String,
    val subject: String,
    val priority: String,
    val status: String,
    val category: String,
    val assignedPlatformUserId: UUID?,
    val openedAt: Instant,
    val resolvedAt: Instant?,
    val updatedAt: Instant,
)

data class SupportTicketDetail(
    val ticket: SupportTicketSummary,
    val description: String?,
    val notes: List<SupportNoteSummary>,
    val timeline: List<SupportEventSummary>,
)

data class SupportNoteSummary(
    val noteId: UUID,
    val note: String,
    val visibility: String,
    val platformUserId: UUID?,
    val tenantUserId: UUID?,
    val createdAt: Instant,
)

data class SupportEventSummary(
    val eventId: UUID,
    val eventType: String,
    val actorPlatformUserId: UUID?,
    val actorTenantUserId: UUID?,
    val beforeState: Map<String, Any?>?,
    val afterState: Map<String, Any?>?,
    val occurredAt: Instant,
)

data class OpenSupportTicketCommand(
    val tenantId: UUID,
    val propertyId: UUID?,
    val subject: String,
    val description: String,
    val priority: String,
    val category: String,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class AddSupportNoteCommand(
    val tenantId: UUID,
    val ticketId: UUID,
    val note: String,
    val visibility: String,
    val platformView: Boolean,
)

data class UpdateSupportTicketCommand(
    val tenantId: UUID,
    val ticketId: UUID,
    val status: String?,
    val priority: String?,
    val assignedPlatformUserId: UUID?,
    val reason: String,
)

class SupportControlNotFoundException(message: String) : RuntimeException(message)
class SupportControlConflictException(message: String) : RuntimeException(message)
