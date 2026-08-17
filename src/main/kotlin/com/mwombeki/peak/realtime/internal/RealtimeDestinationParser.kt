package com.mwombeki.peak.realtime.internal

import java.util.UUID

/**
 * Parsed form of a STOMP subscription destination. The tenant is deliberately absent from
 * the scoped destinations — it is derived from the authenticated context, never trusted from
 * the wire (see docs/architecture/realtime.md, subscription model).
 */
sealed interface RealtimeSubscriptionTarget {
    /** Legacy property-wide stream: /topic/tenants/{t}/properties/{p}/stream */
    data class PropertyStream(val tenantId: UUID, val propertyId: UUID) : RealtimeSubscriptionTarget

    /** Property-wide operations stream: /topic/properties/{p}/operations */
    data class PropertyOperations(val propertyId: UUID) : RealtimeSubscriptionTarget

    /** Outlet-scoped stream: /topic/outlets/{o}/orders | /topic/outlets/{o}/kitchen */
    data class Outlet(val outletId: UUID, val orders: Boolean) : RealtimeSubscriptionTarget

    /** Single-order stream: /topic/orders/{orderId} */
    data class Order(val orderId: UUID) : RealtimeSubscriptionTarget

    /** Single-payment stream: /topic/payments/{paymentTransactionId} */
    data class Payment(val paymentTransactionId: UUID) : RealtimeSubscriptionTarget
}

object RealtimeDestinationParser {

    fun parse(destination: String): RealtimeSubscriptionTarget? {
        LEGACY_STREAM.matchEntire(destination)?.let {
            return RealtimeSubscriptionTarget.PropertyStream(
                tenantId = uuid(it.groupValues[1]) ?: return null,
                propertyId = uuid(it.groupValues[2]) ?: return null,
            )
        }
        PROPERTY_OPERATIONS.matchEntire(destination)?.let {
            return RealtimeSubscriptionTarget.PropertyOperations(
                propertyId = uuid(it.groupValues[1]) ?: return null,
            )
        }
        OUTLET_ORDERS.matchEntire(destination)?.let {
            return RealtimeSubscriptionTarget.Outlet(
                outletId = uuid(it.groupValues[1]) ?: return null,
                orders = true,
            )
        }
        OUTLET_KITCHEN.matchEntire(destination)?.let {
            return RealtimeSubscriptionTarget.Outlet(
                outletId = uuid(it.groupValues[1]) ?: return null,
                orders = false,
            )
        }
        ORDER.matchEntire(destination)?.let {
            return RealtimeSubscriptionTarget.Order(
                orderId = uuid(it.groupValues[1]) ?: return null,
            )
        }
        PAYMENT.matchEntire(destination)?.let {
            return RealtimeSubscriptionTarget.Payment(
                paymentTransactionId = uuid(it.groupValues[1]) ?: return null,
            )
        }
        return null
    }

    private fun uuid(raw: String): UUID? = runCatching { UUID.fromString(raw) }.getOrNull()

    private val LEGACY_STREAM = Regex(RealtimeDestinationRouter.LEGACY_STREAM_PATTERN)
    private val PROPERTY_OPERATIONS = Regex(RealtimeDestinationRouter.PROPERTY_OPERATIONS_PATTERN)
    private val OUTLET_ORDERS = Regex(RealtimeDestinationRouter.OUTLET_ORDERS_PATTERN)
    private val OUTLET_KITCHEN = Regex(RealtimeDestinationRouter.OUTLET_KITCHEN_PATTERN)
    private val ORDER = Regex(RealtimeDestinationRouter.ORDER_PATTERN)
    private val PAYMENT = Regex(RealtimeDestinationRouter.PAYMENT_PATTERN)
}