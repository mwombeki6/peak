package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.pos.api.*
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Transactional
class PosOrderServiceIntegrationTests {

    @Autowired
    private lateinit var posOrderService: PosOrderService

    @Autowired
    private lateinit var posSessionService: PosSessionService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    private val tenantId = UUID.randomUUID()
    private val propertyId = UUID.randomUUID()
    private val tenantUserId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Disable foreign key checks for testing since we don't have a reliable way to create tenants/properties
        // without pulling in the entire administration module logic.
        jdbcTemplate.execute("SET session_replication_role = 'replica';")

        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Tenant(tenantId, tenantUserId),
                correlationId = UUID.randomUUID().toString(),
                idempotencyKey = null,
                httpMethod = "POST",
                requestPath = "/api/v1/pos"
            )
        )
    }

    @Test
    fun `should create order and add items and settle`() {
        // 1. Open Session
        val sessionId = posSessionService.openSession(
            OpenSessionRequest(propertyId = propertyId, startingFloat = BigDecimal("100.00"))
        )

        // 2. Create Order
        val orderId = posOrderService.createOrder(
            propertyId = propertyId,
            request = CreateOrderRequest(sessionId = sessionId)
        )
        assertNotNull(orderId)

        // 3. Add Items
        posOrderService.addItemToOrder(
            orderId = orderId,
            request = AddOrderItemRequest(description = "Beer", quantity = 2, unitPrice = BigDecimal("5.00"))
        )
        posOrderService.addItemToOrder(
            orderId = orderId,
            request = AddOrderItemRequest(description = "Chips", quantity = 1, unitPrice = BigDecimal("3.50"))
        )

        // 4. Verify Order Total
        val order = posOrderService.getOrder(orderId)
        assertEquals(BigDecimal("13.5000"), order.totalAmount.stripTrailingZeros())
        assertEquals(2, order.items.size)

        // 5. Settle Order
        posOrderService.settleOrder(
            orderId = orderId,
            request = PosOrderSettlementRequest(paymentMethod = "CASH", amount = BigDecimal("15.00"))
        )

        val settledOrder = posOrderService.getOrder(orderId)
        assertEquals("PAID", settledOrder.status)

        // 6. Verify Session Summary
        val summary = posSessionService.getSessionSummary(sessionId)
        val stats = summary["summary"] as Map<*, *>
        assertEquals(1L, stats["total_orders"])
        assertEquals(BigDecimal("13.5000"), (stats["total_revenue"] as BigDecimal).stripTrailingZeros())
    }
}
