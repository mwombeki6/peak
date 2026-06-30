package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.pos.api.*
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.*

@Service
class PosOrderService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
) {
    private fun resolveTenant(): UUID {
        val context = requestContextHolder.current()
        return when (val identity = context.identity) {
            is RequestIdentity.Tenant -> identity.tenantId
            else -> throw IllegalStateException("Security Violation: Action requires an active Tenant identity.")
        }
    }

    @Transactional
    fun createOrder(propertyId: UUID, request: CreateOrderRequest): UUID {
        val tenantId = resolveTenant()
        val orderId = UUID.randomUUID()

        // Verify session is open and belongs to the property/tenant
        val sessionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM pos_sessions WHERE id = ? AND property_id = ? AND tenant_id = ? AND status = 'OPEN'",
            Int::class.java, request.sessionId, propertyId, tenantId
        ) ?: 0

        if (sessionCount == 0) {
            throw IllegalStateException("Cannot create order: Session not found or not open.")
        }

        jdbcTemplate.update(
            """
            INSERT INTO pos_orders (id, tenant_id, property_id, session_id, status, total_amount, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'OPEN', 0, NOW(), NOW())
            """.trimIndent(),
            orderId, tenantId, propertyId, request.sessionId
        )

        return orderId
    }

    @Transactional
    fun addItemToOrder(orderId: UUID, request: AddOrderItemRequest) {
        val tenantId = resolveTenant()
        val itemId = UUID.randomUUID()
        val totalPrice = request.unitPrice.multiply(BigDecimal(request.quantity))

        // Verify order is open and belongs to the tenant
        val orderStatus = jdbcTemplate.queryForList(
            "SELECT status FROM pos_orders WHERE id = ? AND tenant_id = ?",
            String::class.java, orderId, tenantId
        ).firstOrNull() ?: throw IllegalStateException("Order not found.")

        if (orderStatus != "OPEN") {
            throw IllegalStateException("Cannot add items to a $orderStatus order.")
        }

        // Insert item
        jdbcTemplate.update(
            """
            INSERT INTO pos_order_items (id, order_id, description, quantity, unit_price, total_price)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            itemId, orderId, request.description, request.quantity, request.unitPrice, totalPrice
        )

        // Update order total
        jdbcTemplate.update(
            "UPDATE pos_orders SET total_amount = total_amount + ?, updated_at = NOW() WHERE id = ?",
            totalPrice, orderId
        )
    }

    @Transactional
    fun settleOrder(orderId: UUID, request: PosOrderSettlementRequest) {
        val tenantId = resolveTenant()

        // 1. Get order details
        val order = jdbcTemplate.queryForMap(
            "SELECT status, total_amount, session_id FROM pos_orders WHERE id = ? AND tenant_id = ?",
            orderId, tenantId
        )

        if (order["status"] != "OPEN") {
            throw IllegalStateException("Order is already ${order["status"]}")
        }

        val totalAmount = order["total_amount"] as BigDecimal
        if (request.amount < totalAmount) {
            throw IllegalArgumentException("Insufficient payment amount.")
        }

        // 2. Validate Payment Method (Phase 3: CASH, MOBILE_MONEY)
        if (request.paymentMethod !in listOf("CASH", "MOBILE_MONEY")) {
            throw IllegalArgumentException("Unsupported payment method: ${request.paymentMethod}")
        }

        // 3. Update Order Status
        jdbcTemplate.update(
            "UPDATE pos_orders SET status = 'PAID', updated_at = NOW() WHERE id = ?",
            orderId
        )

        // 4. Record Payment (In a real scenario, this would call payments.api)
        // For now, we just log it or update session totals if needed
        println(" [POS Settlement] Order $orderId settled via ${request.paymentMethod}. Amount: ${request.amount}")
    }

    @Transactional(readOnly = true)
    fun getOrder(orderId: UUID): PosOrderResponse {
        val tenantId = resolveTenant()

        val order = jdbcTemplate.queryForMap(
            "SELECT id, session_id, status, total_amount FROM pos_orders WHERE id = ? AND tenant_id = ?",
            orderId, tenantId
        )

        val items = jdbcTemplate.query(
            "SELECT id, description, quantity, unit_price, total_price FROM pos_order_items WHERE order_id = ?",
            { rs, _ ->
                PosOrderItemResponse(
                    itemId = rs.getObject("id", UUID::class.java),
                    description = rs.getString("description"),
                    quantity = rs.getInt("quantity"),
                    unitPrice = rs.getBigDecimal("unit_price"),
                    totalPrice = rs.getBigDecimal("total_price")
                )
            },
            orderId
        )

        return PosOrderResponse(
            orderId = order["id"] as UUID,
            sessionId = order["session_id"] as UUID,
            status = order["status"] as String,
            totalAmount = order["total_amount"] as BigDecimal,
            items = items
        )
    }
}
