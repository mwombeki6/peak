package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.billing.api.BillingPort
import com.mwombeki.peak.billing.api.PostChargeRequest
import com.mwombeki.peak.payment.api.CollectPosCashPaymentRequest
import com.mwombeki.peak.payment.api.InitiatePosMobileMoneyRequest
import com.mwombeki.peak.payment.api.PaymentPort
import com.mwombeki.peak.payment.api.PaymentStatus
import com.mwombeki.peak.pos.api.AddPosOrderItemRequest
import com.mwombeki.peak.pos.api.CreatePosOrderRequest
import com.mwombeki.peak.pos.api.PosNotFoundException
import com.mwombeki.peak.pos.api.PosOrderItemResponse
import com.mwombeki.peak.pos.api.PosOrderResponse
import com.mwombeki.peak.pos.api.SettlePosOrderRequest
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.util.Locale
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class PosOrderService(
    private val jdbcTemplate: JdbcTemplate,
    private val commandExecutor: PosCommandExecutor,
    private val billingPort: BillingPort,
    private val paymentPort: PaymentPort,
    private val objectMapper: ObjectMapper,
) {
    fun createOrder(
        propertyId: UUID,
        request: CreatePosOrderRequest,
    ): PosOrderResponse {
        return commandExecutor.mutate(
            propertyId = propertyId,
            operationType = "pos.order.create",
            requestPayload = request,
            resourceType = POS_ORDERS,
            replayType = PosOrderResponse::class.java,
            resourceId = PosOrderResponse::id,
            markReplayed = { it.copy(replayed = true) },
        ) { actor, idempotencyKeyId ->
            val session = requireOpenSession(
                actor.tenantId,
                propertyId,
                request.sessionId,
                actor.tenantUserId,
            )
            val orderType = request.orderType.normalizedOrderType()
            val orderId = UUID.randomUUID()
            val orderNumber = "POS-${orderId.toString().replace("-", "").take(12).uppercase()}"
            jdbcTemplate.update(
                """
                INSERT INTO pos_orders (
                    id, tenant_id, property_id, outlet_id, revenue_center_id,
                    session_id, order_number, table_number, order_type, status,
                    settlement_status, served_by, client_operation_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', 'unsettled', ?, ?)
                """.trimIndent(),
                orderId,
                actor.tenantId,
                propertyId,
                session.outletId,
                session.revenueCenterId,
                request.sessionId,
                orderNumber,
                request.tableNumber.normalizedOptional(),
                orderType,
                actor.tenantUserId,
                request.clientOperationId.normalizedClientOperationId(),
            )
            requireOrder(actor.tenantId, propertyId, orderId, lock = false)
                .also {
                    commandExecutor.recordSideEffects(
                        actor = actor,
                        propertyId = propertyId,
                        action = "pos.order.created",
                        aggregateType = POS_ORDERS,
                        aggregateId = orderId,
                        payload = mapOf(
                            "orderId" to orderId,
                            "orderNumber" to orderNumber,
                            "outletId" to session.outletId,
                            "sessionId" to request.sessionId,
                            "orderType" to orderType,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
        }
    }

    fun addItem(
        propertyId: UUID,
        orderId: UUID,
        request: AddPosOrderItemRequest,
    ): PosOrderResponse {
        return commandExecutor.mutate(
            propertyId = propertyId,
            operationType = "pos.order.item.add",
            requestPayload = mapOf("orderId" to orderId, "request" to request),
            resourceType = POS_ORDER_ITEMS,
            replayType = PosOrderResponse::class.java,
            resourceId = PosOrderResponse::id,
            markReplayed = { it.copy(replayed = true) },
        ) { actor, idempotencyKeyId ->
            val order = requireOrder(actor.tenantId, propertyId, orderId, lock = true)
            require(order.status == "open" && order.settlementStatus in setOf("unsettled", "failed")) {
                "Items can be added only to an open order without a pending settlement"
            }
            val quantity = request.quantity.validQuantity()
            val menuItem = requireMenuItem(
                tenantId = actor.tenantId,
                outletId = order.outletId,
                menuItemId = request.menuItemId,
            )
            val amounts = calculateAmounts(
                unitPrice = menuItem.price,
                quantity = quantity,
                taxRate = menuItem.taxRate,
                taxInclusive = menuItem.taxInclusive,
            )
            val itemId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO pos_order_items (
                    id, tenant_id, order_id, menu_item_id, item_name,
                    quantity, unit_price, subtotal, tax_amount, total_price,
                    modifiers, special_request, client_operation_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """.trimIndent(),
                itemId,
                actor.tenantId,
                orderId,
                menuItem.id,
                menuItem.name,
                quantity,
                menuItem.price,
                amounts.subtotal,
                amounts.tax,
                amounts.total,
                objectMapper.writeValueAsString(request.modifiers.map(String::trim)),
                request.specialRequest.normalizedOptional(),
                request.clientOperationId.normalizedClientOperationId(),
            )
            recalculateOrder(actor.tenantId, propertyId, orderId)
            requireOrder(actor.tenantId, propertyId, orderId, lock = false)
                .also {
                    commandExecutor.recordSideEffects(
                        actor = actor,
                        propertyId = propertyId,
                        action = "pos.order.item.added",
                        aggregateType = POS_ORDERS,
                        aggregateId = orderId,
                        payload = mapOf(
                            "orderId" to orderId,
                            "itemId" to itemId,
                            "menuItemId" to menuItem.id,
                            "quantity" to quantity,
                            "totalPrice" to amounts.total,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
        }
    }

    fun settleOrder(
        propertyId: UUID,
        orderId: UUID,
        request: SettlePosOrderRequest,
    ): PosOrderResponse {
        return commandExecutor.mutate(
            propertyId = propertyId,
            operationType = "pos.order.settle",
            requestPayload = mapOf("orderId" to orderId, "request" to request),
            resourceType = POS_ORDERS,
            replayType = PosOrderResponse::class.java,
            resourceId = PosOrderResponse::id,
            markReplayed = { it.copy(replayed = true) },
        ) { actor, idempotencyKeyId ->
            val order = requireOrder(actor.tenantId, propertyId, orderId, lock = true)
            require(order.status == "open") {
                "Only an open POS order can be settled"
            }
            require(order.settlementStatus in setOf("unsettled", "failed")) {
                "POS order already has a pending or completed settlement"
            }
            require(order.totalAmount > BigDecimal.ZERO) {
                "POS order must contain at least one item before settlement"
            }
            val method = request.paymentMethod.normalizedSettlementMethod()
            val paymentTransactionId = when (method) {
                "cash" -> {
                    require(
                        request.folioId == null &&
                                request.providerAccountId == null &&
                                request.phoneNumber == null,
                    ) {
                        "Cash settlement does not accept folio or provider details"
                    }
                    settleCash(
                        actor.tenantId,
                        propertyId,
                        order,
                        idempotencyKeyId,
                    )
                }
                "mobile_money" -> initiateMobileMoney(
                    actor.tenantId,
                    propertyId,
                    order,
                    request,
                    idempotencyKeyId,
                )
                "room_charge" -> {
                    transferToFolio(
                        actor.tenantId,
                        propertyId,
                        order,
                        request,
                        idempotencyKeyId,
                    )
                    null
                }

                else -> error("Unsupported POS settlement method")
            }
            val action = if (method == "mobile_money") {
                "pos.order.payment_initiated"
            } else {
                "pos.order.settled"
            }
            requireOrder(actor.tenantId, propertyId, orderId, lock = false)
                .also {
                    commandExecutor.recordSideEffects(
                        actor = actor,
                        propertyId = propertyId,
                        action = action,
                        aggregateType = POS_ORDERS,
                        aggregateId = orderId,
                        payload = mapOf(
                            "orderId" to orderId,
                            "orderNumber" to order.orderNumber,
                            "settlementMethod" to method,
                            "settlementStatus" to it.settlementStatus,
                            "paymentTransactionId" to paymentTransactionId,
                            "folioId" to request.folioId,
                            "amount" to order.totalAmount,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
        }
    }

    fun getOrder(propertyId: UUID, orderId: UUID): PosOrderResponse {
        return commandExecutor.read(propertyId) { actor ->
            requireOrder(actor.tenantId, propertyId, orderId, lock = false)
        }
    }

    private fun settleCash(
        tenantId: UUID,
        propertyId: UUID,
        order: PosOrderResponse,
        idempotencyKeyId: UUID,
    ): UUID {
        val transaction = paymentPort.collectPosCash(
            tenantId,
            propertyId,
            CollectPosCashPaymentRequest(
                posOrderId = order.id,
                amount = order.totalAmount,
            ),
            idempotencyKeyId,
        )
        check(transaction.status == PaymentStatus.POSTED) {
            "Cash payment was not posted"
        }
        val orderUpdated = jdbcTemplate.update(
            """
            UPDATE pos_orders
            SET status = 'closed',
                settlement_status = 'confirmed',
                settlement_method = 'cash',
                payment_transaction_id = ?,
                settled_at = now(),
                updated_at = now()
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
              AND status = 'open'
              AND settlement_status IN ('unsettled', 'failed')
            """.trimIndent(),
            transaction.id,
            tenantId,
            propertyId,
            order.id,
        )
        check(orderUpdated == 1) {
            "POS order changed concurrently during cash settlement"
        }
        val sessionUpdated = jdbcTemplate.update(
            """
            UPDATE pos_sessions
            SET expected_cash = expected_cash + ?,
                updated_at = now()
            WHERE tenant_id = ?
              AND id = ?
              AND status = 'open'
            """.trimIndent(),
            order.totalAmount,
            tenantId,
            order.sessionId,
        )
        check(sessionUpdated == 1) {
            "POS session closed concurrently during cash settlement"
        }
        return transaction.id
    }

    private fun initiateMobileMoney(
        tenantId: UUID,
        propertyId: UUID,
        order: PosOrderResponse,
        request: SettlePosOrderRequest,
        idempotencyKeyId: UUID,
    ): UUID {
        require(request.folioId == null) {
            "folioId is allowed only for room_charge settlement"
        }
        val providerAccountId = requireNotNull(request.providerAccountId) {
            "providerAccountId is required for mobile_money settlement"
        }
        val phoneNumber = requireNotNull(request.phoneNumber?.normalizedOptional()) {
            "phoneNumber is required for mobile_money settlement"
        }
        val transaction = paymentPort.initiatePosMobileMoney(
            tenantId,
            propertyId,
            InitiatePosMobileMoneyRequest(
                posOrderId = order.id,
                providerAccountId = providerAccountId,
                phoneNumber = phoneNumber,
                amount = order.totalAmount,
            ),
            idempotencyKeyId,
        )
        val updated = jdbcTemplate.update(
            """
            UPDATE pos_orders
            SET settlement_status = 'pending',
                settlement_method = 'mobile_money',
                payment_transaction_id = ?,
                updated_at = now()
            WHERE id = ?
              AND tenant_id = ?
              AND property_id = ?
              AND status = 'open'
              AND settlement_status IN ('unsettled', 'failed')
            """.trimIndent(),
            transaction.id,
            order.id,
            tenantId,
            propertyId,
        )
        check(updated == 1) {
            "POS order changed concurrently during mobile-money initiation"
        }
        return transaction.id
    }

    private fun transferToFolio(
        tenantId: UUID,
        propertyId: UUID,
        order: PosOrderResponse,
        request: SettlePosOrderRequest,
        idempotencyKeyId: UUID,
    ) {
        require(request.providerAccountId == null && request.phoneNumber == null) {
            "Provider details are allowed only for mobile_money settlement"
        }
        val folioId = requireNotNull(request.folioId) {
            "folioId is required for room_charge settlement"
        }
        val effectiveTaxRate = if (order.taxAmount == BigDecimal.ZERO) {
            BigDecimal.ZERO
        } else {
            order.taxAmount.divide(order.subtotal, 8, RoundingMode.HALF_UP)
        }
        billingPort.postPosCharge(
            tenantId = tenantId,
            propertyId = propertyId,
            folioId = folioId,
            request = PostChargeRequest(
                chargeType = "F&B",
                description = "POS order ${order.orderNumber}",
                quantity = BigDecimal.ONE,
                unitPrice = order.subtotal,
                taxRate = effectiveTaxRate,
                sourceType = "pos_order",
                sourceId = order.id,
            ),
            idempotencyKeyId = idempotencyKeyId,
        )
        val updated = jdbcTemplate.update(
            """
            UPDATE pos_orders
            SET status = 'closed',
                settlement_status = 'transferred',
                settlement_method = 'room_charge',
                folio_id = ?,
                settled_at = now(),
                updated_at = now()
            WHERE id = ?
              AND tenant_id = ?
              AND property_id = ?
              AND status = 'open'
              AND settlement_status IN ('unsettled', 'failed')
            """.trimIndent(),
            folioId,
            order.id,
            tenantId,
            propertyId,
        )
        check(updated == 1) {
            "POS order changed concurrently during folio transfer"
        }
    }

    private fun requireOpenSession(
        tenantId: UUID,
        propertyId: UUID,
        sessionId: UUID,
        cashierId: UUID,
    ): PosSessionSnapshot {
        return jdbcTemplate.query(
            """
            SELECT ps.outlet_id, o.revenue_center_id
            FROM pos_sessions ps
            JOIN outlets o
              ON o.tenant_id = ps.tenant_id
             AND o.id = ps.outlet_id
            WHERE ps.tenant_id = ?
              AND o.property_id = ?
              AND ps.id = ?
              AND ps.cashier_id = ?
              AND ps.status = 'open'
              AND o.is_active = true
              AND o.deleted_at IS NULL
            FOR UPDATE OF ps
            """.trimIndent(),
            { rs, _ ->
                PosSessionSnapshot(
                    outletId = rs.getObject("outlet_id", UUID::class.java),
                    revenueCenterId = rs.getObject("revenue_center_id", UUID::class.java),
                )
            },
            tenantId,
            propertyId,
            sessionId,
            cashierId,
        ).singleOrNull() ?: throw PosNotFoundException(
            "Open POS session for the current cashier was not found",
        )
    }

    private fun requireMenuItem(
        tenantId: UUID,
        outletId: UUID,
        menuItemId: UUID,
    ): MenuItemSnapshot {
        return jdbcTemplate.query(
            """
            SELECT mi.id, mi.name, mi.price,
                   COALESCE(tr.rate, mi.vat_rate / 100.0) AS tax_rate,
                   COALESCE(tr.is_inclusive, false) AS tax_inclusive
            FROM menu_items mi
            JOIN menu_categories mc
              ON mc.tenant_id = mi.tenant_id
             AND mc.id = mi.category_id
            LEFT JOIN tax_rates tr
              ON tr.tenant_id = mi.tenant_id
             AND tr.id = mi.tax_rate_id
             AND tr.is_active = true
             AND tr.effective_from <= current_date
             AND (tr.effective_to IS NULL OR tr.effective_to > current_date)
            WHERE mi.tenant_id = ?
              AND mc.outlet_id = ?
              AND mi.id = ?
              AND mi.is_available = true
              AND mi.deleted_at IS NULL
            """.trimIndent(),
            { rs, _ ->
                MenuItemSnapshot(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    price = rs.getBigDecimal("price").money(),
                    taxRate = rs.getBigDecimal("tax_rate"),
                    taxInclusive = rs.getBoolean("tax_inclusive"),
                )
            },
            tenantId,
            outletId,
            menuItemId,
        ).singleOrNull() ?: throw PosNotFoundException(
            "Available menu item was not found for this outlet",
        )
    }

    private fun requireOrder(
        tenantId: UUID,
        propertyId: UUID,
        orderId: UUID,
        lock: Boolean,
    ): PosOrderResponse {
        val lockClause = if (lock) "FOR UPDATE OF po" else ""
        val order = jdbcTemplate.query(
            """
            SELECT po.id, po.property_id, po.outlet_id, po.session_id,
                   po.order_number, po.order_type, po.table_number, po.status,
                   po.settlement_status, po.settlement_method, po.folio_id,
                   po.payment_transaction_id, po.subtotal, po.tax_amount,
                   po.total_amount, po.created_at, po.settled_at
            FROM pos_orders po
            WHERE po.tenant_id = ?
              AND po.property_id = ?
              AND po.id = ?
              AND po.deleted_at IS NULL
            $lockClause
            """.trimIndent(),
            { rs, _ -> mapOrder(rs, emptyList()) },
            tenantId,
            propertyId,
            orderId,
        ).singleOrNull() ?: throw PosNotFoundException("POS order was not found")
        return order.copy(items = listItems(tenantId, orderId))
    }

    private fun listItems(tenantId: UUID, orderId: UUID): List<PosOrderItemResponse> {
        return jdbcTemplate.query(
            """
            SELECT id, menu_item_id, item_name, quantity, unit_price,
                   subtotal, tax_amount, total_price, modifiers::text,
                   special_request, service_state, void_disposition
            FROM pos_order_items
            WHERE tenant_id = ?
              AND order_id = ?
              AND voided = false
            ORDER BY created_at, id
            """.trimIndent(),
            { rs, _ ->
                PosOrderItemResponse(
                    id = rs.getObject("id", UUID::class.java),
                    menuItemId = rs.getObject("menu_item_id", UUID::class.java),
                    name = rs.getString("item_name"),
                    quantity = rs.getBigDecimal("quantity"),
                    unitPrice = rs.getBigDecimal("unit_price").money(),
                    subtotal = rs.getBigDecimal("subtotal").money(),
                    taxAmount = rs.getBigDecimal("tax_amount").money(),
                    totalPrice = rs.getBigDecimal("total_price").money(),
                    modifiers = objectMapper.readValue(
                        rs.getString("modifiers"),
                        Array<String>::class.java,
                    ).toList(),
                    specialRequest = rs.getString("special_request"),
                    serviceState = rs.getString("service_state").uppercase(Locale.ROOT),
                    voidDisposition = rs.getString("void_disposition")?.uppercase(Locale.ROOT),
                )
            },
            tenantId,
            orderId,
        )
    }

    private fun recalculateOrder(tenantId: UUID, propertyId: UUID, orderId: UUID) {
        val updated = jdbcTemplate.update(
            """
            UPDATE pos_orders po
            SET subtotal = totals.subtotal,
                tax_amount = totals.tax_amount,
                total_amount = totals.total_amount,
                updated_at = now()
            FROM (
                SELECT order_id,
                       COALESCE(SUM(subtotal) FILTER (WHERE voided = false), 0) AS subtotal,
                       COALESCE(SUM(tax_amount) FILTER (WHERE voided = false), 0) AS tax_amount,
                       COALESCE(SUM(total_price) FILTER (WHERE voided = false), 0) AS total_amount
                FROM pos_order_items
                WHERE tenant_id = ? AND order_id = ?
                GROUP BY order_id
            ) totals
            WHERE po.tenant_id = ?
              AND po.property_id = ?
              AND po.id = totals.order_id
              AND po.status = 'open'
            """.trimIndent(),
            tenantId,
            orderId,
            tenantId,
            propertyId,
        )
        check(updated == 1) {
            "POS order totals could not be recalculated"
        }
    }

    private fun calculateAmounts(
        unitPrice: BigDecimal,
        quantity: BigDecimal,
        taxRate: BigDecimal,
        taxInclusive: Boolean,
    ): ItemAmounts {
        val gross = unitPrice.multiply(quantity)
        val subtotal = if (taxInclusive && taxRate > BigDecimal.ZERO) {
            gross.divide(BigDecimal.ONE.add(taxRate), 2, RoundingMode.HALF_UP)
        } else {
            gross.money()
        }
        val tax = if (taxInclusive) {
            gross.subtract(subtotal).money()
        } else {
            subtotal.multiply(taxRate).money()
        }
        return ItemAmounts(
            subtotal = subtotal,
            tax = tax,
            total = subtotal.add(tax).money(),
        )
    }

    private fun mapOrder(rs: ResultSet, items: List<PosOrderItemResponse>): PosOrderResponse {
        return PosOrderResponse(
            id = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            outletId = rs.getObject("outlet_id", UUID::class.java),
            sessionId = rs.getObject("session_id", UUID::class.java),
            orderNumber = rs.getString("order_number"),
            orderType = rs.getString("order_type"),
            tableNumber = rs.getString("table_number"),
            status = rs.getString("status"),
            settlementStatus = rs.getString("settlement_status"),
            settlementMethod = rs.getString("settlement_method"),
            folioId = rs.getObject("folio_id", UUID::class.java),
            paymentTransactionId = rs.getObject("payment_transaction_id", UUID::class.java),
            subtotal = rs.getBigDecimal("subtotal").money(),
            taxAmount = rs.getBigDecimal("tax_amount").money(),
            totalAmount = rs.getBigDecimal("total_amount").money(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            settledAt = rs.getTimestamp("settled_at")?.toInstant(),
            items = items,
        )
    }

    private fun String.normalizedOrderType(): String {
        val normalized = trim().lowercase(Locale.ROOT)
        require(normalized in ORDER_TYPES) {
            "Unsupported POS order type"
        }
        return normalized
    }

    private fun String.normalizedSettlementMethod(): String {
        val normalized = trim().lowercase(Locale.ROOT)
        require(normalized in SETTLEMENT_METHODS) {
            "Only cash, mobile_money, and room_charge settlements are supported"
        }
        return normalized
    }

    private fun String.normalizedClientOperationId(): String {
        val normalized = trim()
        require(normalized.isNotEmpty() && normalized.length <= 100) {
            "clientOperationId is required and must not exceed 100 characters"
        }
        return normalized
    }

    private fun String?.normalizedOptional(): String? {
        return this?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun BigDecimal.validQuantity(): BigDecimal {
        require(this > BigDecimal.ZERO && this <= MAX_QUANTITY) {
            "quantity must be greater than zero and no more than $MAX_QUANTITY"
        }
        require(stripTrailingZeros().scale() <= 3) {
            "quantity supports at most three decimal places"
        }
        return setScale(3, RoundingMode.UNNECESSARY)
    }

    private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

    private data class PosSessionSnapshot(
        val outletId: UUID,
        val revenueCenterId: UUID?,
    )

    private data class MenuItemSnapshot(
        val id: UUID,
        val name: String,
        val price: BigDecimal,
        val taxRate: BigDecimal,
        val taxInclusive: Boolean,
    )

    private data class ItemAmounts(
        val subtotal: BigDecimal,
        val tax: BigDecimal,
        val total: BigDecimal,
    )

    private companion object {
        const val POS_ORDERS = "pos_orders"
        const val POS_ORDER_ITEMS = "pos_order_items"
        val ORDER_TYPES = setOf("dine_in", "takeaway", "room_service", "delivery", "bar")
        val SETTLEMENT_METHODS = setOf("cash", "mobile_money", "room_charge")
        val MAX_QUANTITY = BigDecimal("999999.999")
    }
}
