package com.mwombeki.peak.realtime.internal

import com.mwombeki.peak.realtime.api.RealtimeEventTypes
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealtimeDestinationRouterTests {

    private val router = RealtimeDestinationRouter()
    private val tenantId = UUID.randomUUID()
    private val propertyId = UUID.randomUUID()
    private val outletId = UUID.randomUUID()
    private val orderId = UUID.randomUUID()
    private val ticketId = UUID.randomUUID()
    private val paymentId = UUID.randomUUID()

    @Test
    fun `order event reaches property operations, legacy stream, outlet orders and order`() {
        val destinations = router.destinations(
            event(RealtimeEventTypes.AGGREGATE_POS_ORDER, orderId, outletId),
        )

        assertEquals(
            setOf(
                RealtimeDestinationRouter.propertyOperations(propertyId),
                RealtimeDestinationRouter.legacyPropertyStream(tenantId, propertyId),
                RealtimeDestinationRouter.outletOrders(outletId),
                RealtimeDestinationRouter.order(orderId),
            ),
            destinations,
        )
    }

    @Test
    fun `kitchen ticket event reaches outlet kitchen but not outlet orders`() {
        val destinations = router.destinations(
            event(RealtimeEventTypes.AGGREGATE_KITCHEN_TICKET, ticketId, outletId),
        )

        assertTrue(RealtimeDestinationRouter.outletKitchen(outletId) in destinations)
        assertTrue(RealtimeDestinationRouter.outletOrders(outletId) !in destinations)
        assertTrue(RealtimeDestinationRouter.order(orderId) !in destinations)
    }

    @Test
    fun `print job event reaches outlet kitchen like a ticket`() {
        val destinations = router.destinations(
            event(RealtimeEventTypes.AGGREGATE_PRINT_JOB, ticketId, outletId),
        )

        assertTrue(RealtimeDestinationRouter.outletKitchen(outletId) in destinations)
        assertTrue(RealtimeDestinationRouter.outletOrders(outletId) !in destinations)
    }

    @Test
    fun `payment event reaches payment stream but not any order stream`() {
        val destinations = router.destinations(
            event(RealtimeEventTypes.AGGREGATE_PAYMENT_TRANSACTION, paymentId, null),
        )

        assertEquals(
            setOf(
                RealtimeDestinationRouter.propertyOperations(propertyId),
                RealtimeDestinationRouter.legacyPropertyStream(tenantId, propertyId),
                RealtimeDestinationRouter.payment(paymentId),
            ),
            destinations,
        )
    }

    @Test
    fun `order event without outlet still reaches property and order streams`() {
        val destinations = router.destinations(
            event(RealtimeEventTypes.AGGREGATE_POS_ORDER, orderId, null),
        )

        assertTrue(RealtimeDestinationRouter.propertyOperations(propertyId) in destinations)
        assertTrue(RealtimeDestinationRouter.order(orderId) in destinations)
        assertTrue(destinations.none { it.startsWith("/topic/outlets/") })
    }

    @Test
    fun `unrouted aggregate reaches property streams only`() {
        val destinations = router.destinations(
            event("some.other.aggregate", null, null),
        )

        assertEquals(
            setOf(
                RealtimeDestinationRouter.propertyOperations(propertyId),
                RealtimeDestinationRouter.legacyPropertyStream(tenantId, propertyId),
            ),
            destinations,
        )
    }

    private fun event(
        aggregateType: String,
        aggregateId: UUID?,
        outletId: UUID?,
    ) = StoredRealtimeEvent(
        sequenceId = 1L,
        eventId = UUID.randomUUID(),
        tenantId = tenantId,
        propertyId = propertyId,
        eventType = "pos.order.updated",
        payload = emptyMap(),
        createdAt = Instant.now(),
        schemaVersion = 1,
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        aggregateVersion = 1L,
        outletId = outletId,
    )
}

class RealtimeDestinationParserTests {

    private val propertyId = UUID.randomUUID()
    private val outletId = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val orderId = UUID.randomUUID()
    private val paymentId = UUID.randomUUID()

    @Test
    fun `parses legacy property stream with tenant and property`() {
        assertEquals(
            RealtimeSubscriptionTarget.PropertyStream(tenantId, propertyId),
            RealtimeDestinationParser.parse(
                "/topic/tenants/$tenantId/properties/$propertyId/stream",
            ),
        )
    }

    @Test
    fun `parses property operations without tenant`() {
        assertEquals(
            RealtimeSubscriptionTarget.PropertyOperations(propertyId),
            RealtimeDestinationParser.parse("/topic/properties/$propertyId/operations"),
        )
    }

    @Test
    fun `parses outlet orders and kitchen separately`() {
        assertEquals(
            RealtimeSubscriptionTarget.Outlet(outletId, orders = true),
            RealtimeDestinationParser.parse("/topic/outlets/$outletId/orders"),
        )
        assertEquals(
            RealtimeSubscriptionTarget.Outlet(outletId, orders = false),
            RealtimeDestinationParser.parse("/topic/outlets/$outletId/kitchen"),
        )
    }

    @Test
    fun `parses order and payment streams`() {
        assertEquals(
            RealtimeSubscriptionTarget.Order(orderId),
            RealtimeDestinationParser.parse("/topic/orders/$orderId"),
        )
        assertEquals(
            RealtimeSubscriptionTarget.Payment(paymentId),
            RealtimeDestinationParser.parse("/topic/payments/$paymentId"),
        )
    }

    @Test
    fun `rejects malformed or unknown destinations`() {
        assertNull(RealtimeDestinationParser.parse("/topic/unknown/$propertyId"))
        assertNull(RealtimeDestinationParser.parse("/topic/outlets/not-a-uuid/orders"))
        assertNull(RealtimeDestinationParser.parse("/queue/properties/$propertyId/operations"))
        assertNull(RealtimeDestinationParser.parse(""))
        assertNull(RealtimeDestinationParser.parse("/topic/properties//operations"))
    }

    @Test
    fun `round trip between router and parser for every routed destination`() {
        val targets = listOf(
            RealtimeSubscriptionTarget.PropertyStream(tenantId, propertyId),
            RealtimeSubscriptionTarget.PropertyOperations(propertyId),
            RealtimeSubscriptionTarget.Outlet(outletId, orders = true),
            RealtimeSubscriptionTarget.Outlet(outletId, orders = false),
            RealtimeSubscriptionTarget.Order(orderId),
            RealtimeSubscriptionTarget.Payment(paymentId),
        )
        targets.forEach { target ->
            val destination = when (target) {
                is RealtimeSubscriptionTarget.PropertyStream ->
                    RealtimeDestinationRouter.legacyPropertyStream(
                        target.tenantId, target.propertyId,
                    )
                is RealtimeSubscriptionTarget.PropertyOperations ->
                    RealtimeDestinationRouter.propertyOperations(target.propertyId)
                is RealtimeSubscriptionTarget.Outlet ->
                    if (target.orders) {
                        RealtimeDestinationRouter.outletOrders(target.outletId)
                    } else {
                        RealtimeDestinationRouter.outletKitchen(target.outletId)
                    }
                is RealtimeSubscriptionTarget.Order ->
                    RealtimeDestinationRouter.order(target.orderId)
                is RealtimeSubscriptionTarget.Payment ->
                    RealtimeDestinationRouter.payment(target.paymentTransactionId)
            }
            assertEquals(target, RealtimeDestinationParser.parse(destination))
        }
    }
}