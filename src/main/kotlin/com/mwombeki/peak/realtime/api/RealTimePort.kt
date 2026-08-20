package com.mwombeki.peak.realtime.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

/**
 * Canonical realtime event envelope.
 *
 * Every meaningful committed domain transition that reaches subscribers travels in this shape:
 *
 * ```
 * {
 *   "eventId": "...", "type": "pos.order.settled", "schemaVersion": 1,
 *   "aggregateType": "POS_ORDER", "aggregateId": "...", "aggregateVersion": 7,
 *   "occurredAt": "...",
 *   "tenantId": "...", "propertyId": "...", "outletId": "...",
 *   "payload": { ... }
 * }
 * ```
 *
 * The envelope is a contract. Subscribers must never treat a WebSocket payload as financial
 * truth — events signal that authoritative state changed and should trigger a refetch of the
 * canonical REST state. aggregateVersion lets a subscriber drop stale/out-of-order events
 * instead of regressing state.
 */
@NamedInterface("api")
data class RealtimeEventRequest(
    val tenantId: UUID,
    val propertyId: UUID,
    /** Present when the transition happened inside an outlet (POS orders, kitchen tickets). */
    val outletId: UUID? = null,
    val eventType: String,
    val schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
    val aggregateType: String? = null,
    val aggregateId: UUID? = null,
    /** Version of the aggregate after the committed transition, when the domain has one. */
    val aggregateVersion: Long? = null,
    val payload: Map<String, Any?>,
) {
    init {
        require(eventType.matches(EVENT_TYPE_PATTERN)) {
            "Realtime event type is invalid: $eventType"
        }
        require((aggregateType == null) == (aggregateId == null)) {
            "Realtime aggregate type and id must be provided together"
        }
        require(aggregateVersion == null || aggregateVersion >= 0) {
            "Realtime aggregate version must be non-negative"
        }
    }

    companion object {
        const val DEFAULT_SCHEMA_VERSION = 1
        val EVENT_TYPE_PATTERN = Regex("[A-Za-z][A-Za-z0-9._:-]{1,99}")
    }
}

/** Legacy broadcast shape, kept for existing callers; maps onto [RealtimeEventRequest]. */
@NamedInterface("api")
data class BroadcastEventRequest(
    val tenantId: UUID,
    val propertyId: UUID,
    val eventType: String, // e.g., "ROOM_STATUS_CHANGED", "NEW_BOOKING"
    val payload: Map<String, Any?>
)

@NamedInterface("api")
interface RealtimePort {
    fun broadcastLiveEvent(request: BroadcastEventRequest)

    fun broadcastRealtimeEvent(request: RealtimeEventRequest)
}

/**
 * Canonical event type and aggregate identifiers, shared between the modules that publish
 * events and the realtime module that routes them. The event catalog in
 * docs/architecture/realtime.md is generated from these values.
 */
object RealtimeEventTypes {
    // POS orders
    const val POS_ORDER_CREATED = "pos.order.created"
    const val POS_ORDER_UPDATED = "pos.order.updated"
    const val POS_ORDER_SENT = "pos.order.sent"
    const val POS_ORDER_PAYMENT_INITIATED = "pos.order.payment_initiated"
    const val POS_ORDER_SETTLED = "pos.order.settled"
    const val POS_ORDER_ITEM_VOIDED = "pos.order_item.voided"

    // Kitchen tickets
    const val KITCHEN_TICKET_CREATED = "pos.kitchen_ticket.created"
    const val KITCHEN_TICKET_PREPARING = "pos.kitchen_ticket.preparing"
    const val KITCHEN_TICKET_READY = "pos.kitchen_ticket.ready"
    const val KITCHEN_TICKET_DELIVERED = "pos.kitchen_ticket.delivered"
    const val KITCHEN_TICKET_VOIDED = "pos.kitchen_ticket.voided"

    // Print jobs
    const val PRINT_JOB_CREATED = "pos.print_job.created"
    const val PRINT_JOB_CLAIMED = "pos.print_job.claimed"
    const val PRINT_JOB_PRINTED = "pos.print_job.printed"
    const val PRINT_JOB_FAILED = "pos.print_job.failed"
    const val PRINT_JOB_RECLAIMED = "pos.print_job.reclaimed"

    // Shifts / sessions
    const val SESSION_OPENED = "pos.session.opened"
    const val SESSION_CLOSING = "pos.session.closing"
    const val SESSION_VARIANCE_APPROVED = "pos.session.variance_approved"
    const val SESSION_CLOSED = "pos.session.closed"

    // Payments (backend-committed canonical transitions only)
    const val PAYMENT_CREATED = "payment.created"
    const val PAYMENT_PENDING = "payment.pending"
    const val PAYMENT_SUCCEEDED = "payment.succeeded"
    const val PAYMENT_FAILED = "payment.failed"
    const val PAYMENT_RECONCILIATION_REQUIRED = "payment.reconciliation_required"

    // Device lifecycle (reserved; wiring lands with committed device pairing)
    const val DEVICE_REVOKED = "device.revoked"
    const val DEVICE_CONFIGURATION_CHANGED = "device.configuration_changed"

    const val AGGREGATE_POS_ORDER = "POS_ORDER"
    const val AGGREGATE_KITCHEN_TICKET = "KITCHEN_TICKET"
    const val AGGREGATE_PRINT_JOB = "PRINT_JOB"
    const val AGGREGATE_POS_SESSION = "POS_SESSION"
    const val AGGREGATE_PAYMENT_TRANSACTION = "PAYMENT_TRANSACTION"
    const val AGGREGATE_DEVICE = "DEVICE"
}
