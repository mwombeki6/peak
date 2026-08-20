package com.mwombeki.peak.realtime.internal

import com.mwombeki.peak.realtime.api.RealtimeEventTypes
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * Maps a committed journal event to the set of STOMP destinations it must be delivered to.
 *
 * Events are routed by their own metadata (tenant/property/outlet/aggregate), never by
 * anything a client supplied. All events reach the property operations stream; aggregate
 * events additionally reach their scoped destination so a kitchen display can subscribe to
 * exactly its outlet's kitchen tickets and a terminal to a specific order or payment.
 */
@Component
class RealtimeDestinationRouter {

    fun destinations(event: StoredRealtimeEvent): Set<String> {
        val destinations = LinkedHashSet<String>()
        destinations += propertyOperations(event.propertyId)
        destinations += legacyPropertyStream(event.tenantId, event.propertyId)

        val outletId = event.outletId
        when (event.aggregateType) {
            RealtimeEventTypes.AGGREGATE_POS_ORDER -> {
                if (outletId != null) {
                    destinations += outletOrders(outletId)
                }
                event.aggregateId?.let { destinations += order(it) }
            }
            RealtimeEventTypes.AGGREGATE_KITCHEN_TICKET,
            RealtimeEventTypes.AGGREGATE_PRINT_JOB,
            -> {
                if (outletId != null) {
                    destinations += outletKitchen(outletId)
                }
            }
            RealtimeEventTypes.AGGREGATE_PAYMENT_TRANSACTION -> {
                event.aggregateId?.let { destinations += payment(it) }
            }
        }
        return destinations
    }

    companion object {
        const val LEGACY_STREAM_PATTERN =
            "^/topic/tenants/([^/]+)/properties/([^/]+)/stream$"
        const val PROPERTY_OPERATIONS_PATTERN =
            "^/topic/properties/([^/]+)/operations$"
        const val OUTLET_ORDERS_PATTERN =
            "^/topic/outlets/([^/]+)/orders$"
        const val OUTLET_KITCHEN_PATTERN =
            "^/topic/outlets/([^/]+)/kitchen$"
        const val ORDER_PATTERN = "^/topic/orders/([^/]+)$"
        const val PAYMENT_PATTERN = "^/topic/payments/([^/]+)$"

        fun legacyPropertyStream(tenantId: UUID, propertyId: UUID) =
            "/topic/tenants/$tenantId/properties/$propertyId/stream"

        fun propertyOperations(propertyId: UUID) =
            "/topic/properties/$propertyId/operations"

        fun outletOrders(outletId: UUID) =
            "/topic/outlets/$outletId/orders"

        fun outletKitchen(outletId: UUID) =
            "/topic/outlets/$outletId/kitchen"

        fun order(orderId: UUID) = "/topic/orders/$orderId"

        fun payment(paymentTransactionId: UUID) =
            "/topic/payments/$paymentTransactionId"
    }
}
