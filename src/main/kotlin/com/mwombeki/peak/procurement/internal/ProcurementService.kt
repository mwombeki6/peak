package com.mwombeki.peak.procurement.internal

import com.mwombeki.peak.inventory.api.InventoryPort
import com.mwombeki.peak.inventory.api.ReceiveStockCommand
import com.mwombeki.peak.inventory.api.ReceiveStockLine
import com.mwombeki.peak.procurement.api.CreatePurchaseOrderRequest
import com.mwombeki.peak.procurement.api.CreatePurchaseReceiptRequest
import com.mwombeki.peak.procurement.api.CreateSupplierRequest
import com.mwombeki.peak.procurement.api.ProcurementConflictException
import com.mwombeki.peak.procurement.api.ProcurementNotFoundException
import com.mwombeki.peak.procurement.api.ProcurementPort
import com.mwombeki.peak.procurement.api.ProcurementReasonRequest
import com.mwombeki.peak.procurement.api.PurchaseOrderLineResponse
import com.mwombeki.peak.procurement.api.PurchaseOrderResponse
import com.mwombeki.peak.procurement.api.PurchaseOrderStatus
import com.mwombeki.peak.procurement.api.PurchaseReceiptLineResponse
import com.mwombeki.peak.procurement.api.PurchaseReceiptResponse
import com.mwombeki.peak.procurement.api.SupplierResponse
import com.mwombeki.peak.procurement.api.UpdateSupplierRequest
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.util.Locale
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class ProcurementService(
    private val jdbc: JdbcTemplate,
    private val commands: ProcurementCommandExecutor,
    private val inventory: InventoryPort,
) : ProcurementPort {
    fun listSuppliers(propertyId: UUID): List<SupplierResponse> =
        commands.read(propertyId) { actor ->
            jdbc.query(
                "$SUPPLIER_SELECT ORDER BY name",
                ::mapSupplier, actor.tenantId,
            )
        }

    fun createSupplier(propertyId: UUID, request: CreateSupplierRequest) =
        commands.mutate(
            propertyId, "procurement.supplier.create", request, SUPPLIERS,
            SupplierResponse::class.java, { it.id }, { it.copy(replayed = true) },
        ) { actor, key ->
            val id = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO suppliers (
                    id, tenant_id, name, code, contact_name,
                    contact_email, contact_phone, is_active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, true)
                """.trimIndent(),
                id, actor.tenantId, request.name.required(), request.code.clean(),
                request.contactName.clean(), request.contactEmail.clean(),
                request.contactPhone.clean(),
            )
            supplier(actor.tenantId, id).also {
                commands.effects(
                    actor, propertyId, "procurement.supplier.created", SUPPLIERS, id,
                    mapOf("supplierId" to id, "name" to it.name), key,
                )
            }
        }

    fun getSupplier(propertyId: UUID, supplierId: UUID) =
        commands.read(propertyId) { actor -> supplier(actor.tenantId, supplierId) }

    fun updateSupplier(
        propertyId: UUID,
        supplierId: UUID,
        request: UpdateSupplierRequest,
    ) = commands.mutate(
        propertyId, "procurement.supplier.update",
        mapOf("supplierId" to supplierId, "request" to request), SUPPLIERS,
        SupplierResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        supplier(actor.tenantId, supplierId)
        jdbc.update(
            """
            UPDATE suppliers SET name = COALESCE(?, name), code = COALESCE(?, code),
                contact_email = COALESCE(?, contact_email),
                contact_phone = COALESCE(?, contact_phone),
                is_active = COALESCE(?, is_active), updated_at = now()
            WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
            """.trimIndent(),
            request.name?.required(), request.code?.clean(), request.contactEmail?.clean(),
            request.contactPhone?.clean(), request.active, actor.tenantId, supplierId,
        )
        supplier(actor.tenantId, supplierId).also {
            commands.effects(
                actor, propertyId, "procurement.supplier.updated", SUPPLIERS, supplierId,
                mapOf("supplierId" to supplierId), key,
            )
        }
    }

    override fun listPurchaseOrders(propertyId: UUID): List<PurchaseOrderResponse> =
        commands.read(propertyId) { actor ->
            jdbc.query(
                "$ORDER_SELECT ORDER BY po.created_at DESC",
                { rs, _ -> mapOrder(rs, actor.tenantId) },
                actor.tenantId, propertyId,
            )
        }

    override fun getPurchaseOrder(propertyId: UUID, id: UUID) =
        commands.read(propertyId) { actor -> order(actor.tenantId, propertyId, id) }

    override fun createPurchaseOrder(
        propertyId: UUID,
        request: CreatePurchaseOrderRequest,
    ) = commands.mutate(
        propertyId, "procurement.purchase_order.create", request, ORDERS,
        PurchaseOrderResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        supplier(actor.tenantId, request.supplierId)
        require(request.lines.map { it.inventoryItemId }.distinct().size == request.lines.size) {
            "Purchase-order inventory items must be unique"
        }
        val currency = jdbc.queryForObject(
            "SELECT currency FROM properties WHERE tenant_id = ? AND id = ?",
            String::class.java, actor.tenantId, propertyId,
        ) ?: throw ProcurementNotFoundException("Property was not found")
        val id = UUID.randomUUID()
        val number = "PO-${id.toString().replace("-", "").take(12).uppercase(Locale.ROOT)}"
        val total = request.lines.sumOf {
            it.quantity.quantity().multiply(it.unitPrice.money()).money()
        }.money()
        jdbc.update(
            """
            INSERT INTO purchase_orders (
                id, tenant_id, property_id, supplier_id, order_number,
                expected_delivery_date, total_amount, currency, status, created_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'draft', ?)
            """.trimIndent(),
            id, actor.tenantId, propertyId, request.supplierId, number,
            request.expectedDeliveryDate, total, currency, actor.tenantUserId,
        )
        request.lines.forEach {
            requireInventoryItem(actor.tenantId, it.inventoryItemId)
            val quantity = it.quantity.quantity()
            val price = it.unitPrice.money()
            jdbc.update(
                """
                INSERT INTO purchase_order_items (
                    id, tenant_id, purchase_order_id, inventory_item_id,
                    quantity, unit_price, total_price
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(), actor.tenantId, id, it.inventoryItemId,
                quantity, price, quantity.multiply(price).money(),
            )
        }
        order(actor.tenantId, propertyId, id).also {
            commands.effects(
                actor, propertyId, "procurement.purchase_order.created", ORDERS, id,
                mapOf("purchaseOrderId" to id, "totalAmount" to total), key,
            )
        }
    }

    override fun updatePurchaseOrder(
        propertyId: UUID,
        id: UUID,
        request: CreatePurchaseOrderRequest,
    ) = commands.mutate(
        propertyId, "procurement.purchase_order.update",
        mapOf("id" to id, "request" to request), ORDERS,
        PurchaseOrderResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        val current = order(actor.tenantId, propertyId, id, true)
        if (current.status != PurchaseOrderStatus.DRAFT) {
            throw ProcurementConflictException("Only a draft purchase order can be edited")
        }
        supplier(actor.tenantId, request.supplierId)
        require(request.lines.map { it.inventoryItemId }.distinct().size == request.lines.size) {
            "Purchase-order inventory items must be unique"
        }
        val total = request.lines.sumOf {
            it.quantity.quantity().multiply(it.unitPrice.money()).money()
        }.money()
        jdbc.update(
            """
            UPDATE purchase_orders SET supplier_id = ?, expected_delivery_date = ?,
                total_amount = ?, updated_at = now()
            WHERE tenant_id = ? AND property_id = ? AND id = ? AND status = 'draft'
            """.trimIndent(),
            request.supplierId, request.expectedDeliveryDate, total,
            actor.tenantId, propertyId, id,
        )
        jdbc.update(
            "DELETE FROM purchase_order_items WHERE tenant_id = ? AND purchase_order_id = ?",
            actor.tenantId, id,
        )
        request.lines.forEach {
            requireInventoryItem(actor.tenantId, it.inventoryItemId)
            val quantity = it.quantity.quantity()
            val price = it.unitPrice.money()
            jdbc.update(
                """
                INSERT INTO purchase_order_items (
                    id, tenant_id, purchase_order_id, inventory_item_id,
                    quantity, unit_price, total_price
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(), actor.tenantId, id, it.inventoryItemId,
                quantity, price, quantity.multiply(price).money(),
            )
        }
        order(actor.tenantId, propertyId, id).also {
            commands.effects(
                actor, propertyId, "procurement.purchase_order.updated", ORDERS, id,
                mapOf("purchaseOrderId" to id, "totalAmount" to total), key,
            )
        }
    }

    override fun transitionPurchaseOrder(
        propertyId: UUID,
        id: UUID,
        action: String,
        reason: ProcurementReasonRequest?,
    ) = commands.mutate(
        propertyId, "procurement.purchase_order.$action",
        mapOf("id" to id, "reason" to reason), ORDERS,
        PurchaseOrderResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        val current = order(actor.tenantId, propertyId, id, true)
        val normalized = action.lowercase(Locale.ROOT)
        val target = when (normalized) {
            "submit" -> {
                require(current.status == PurchaseOrderStatus.DRAFT)
                require(current.lines.isNotEmpty()) { "Empty purchase order cannot be submitted" }
                jdbc.update(
                    """
                    UPDATE purchase_orders SET status = 'submitted',
                        submitted_at = now(), updated_at = now()
                    WHERE tenant_id = ? AND property_id = ? AND id = ? AND status = 'draft'
                    """.trimIndent(),
                    actor.tenantId, propertyId, id,
                )
                "submitted"
            }
            "approve" -> {
                require(current.status == PurchaseOrderStatus.SUBMITTED)
                require(current.createdBy != actor.tenantUserId) {
                    "Purchase-order creator cannot approve their own order"
                }
                jdbc.update(
                    """
                    UPDATE purchase_orders SET status = 'approved', approved_by = ?,
                        approved_at = now(), updated_at = now()
                    WHERE tenant_id = ? AND property_id = ? AND id = ?
                      AND status = 'submitted' AND created_by <> ?
                    """.trimIndent(),
                    actor.tenantUserId, actor.tenantId, propertyId, id, actor.tenantUserId,
                )
                "approved"
            }
            "reject" -> {
                require(current.status == PurchaseOrderStatus.SUBMITTED)
                val text = requireNotNull(reason).reason.required()
                jdbc.update(
                    """
                    UPDATE purchase_orders SET status = 'rejected', rejected_by = ?,
                        rejected_at = now(), rejection_reason = ?, updated_at = now()
                    WHERE tenant_id = ? AND property_id = ? AND id = ? AND status = 'submitted'
                    """.trimIndent(),
                    actor.tenantUserId, text, actor.tenantId, propertyId, id,
                )
                "rejected"
            }
            "cancel" -> {
                require(current.status in setOf(
                    PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.SUBMITTED,
                    PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.PARTIALLY_RECEIVED,
                ))
                val text = requireNotNull(reason).reason.required()
                jdbc.update(
                    """
                    UPDATE purchase_orders SET status = 'cancelled', cancelled_by = ?,
                        cancelled_at = now(), cancellation_reason = ?, updated_at = now()
                    WHERE tenant_id = ? AND property_id = ? AND id = ?
                      AND status IN ('draft', 'submitted', 'approved', 'partially_received')
                    """.trimIndent(),
                    actor.tenantUserId, text, actor.tenantId, propertyId, id,
                )
                "cancelled"
            }
            else -> throw IllegalArgumentException("Unsupported purchase-order action")
        }
        jdbc.update(
            """
            INSERT INTO purchase_order_approvals (
                tenant_id, property_id, purchase_order_id, action, actor_id, reason
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            actor.tenantId, propertyId, id, target, actor.tenantUserId, reason?.reason?.clean(),
        )
        order(actor.tenantId, propertyId, id).also {
            commands.effects(
                actor, propertyId, "procurement.purchase_order.$target", ORDERS, id,
                mapOf("purchaseOrderId" to id, "status" to it.status), key,
            )
        }
    }

    override fun receivePurchaseOrder(
        propertyId: UUID,
        id: UUID,
        request: CreatePurchaseReceiptRequest,
    ) = commands.mutate(
        propertyId, "procurement.purchase_order.receive",
        mapOf("id" to id, "request" to request), RECEIPTS,
        PurchaseReceiptResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        val po = order(actor.tenantId, propertyId, id, true)
        if (po.status !in setOf(
                PurchaseOrderStatus.APPROVED,
                PurchaseOrderStatus.PARTIALLY_RECEIVED,
            )
        ) {
            throw ProcurementConflictException("Purchase order is not receivable")
        }
        require(request.lines.map { it.purchaseOrderItemId }.distinct().size == request.lines.size) {
            "Receipt lines must be unique"
        }
        val indexed = po.lines.associateBy { it.id }
        val receiptLines = request.lines.map { requested ->
            val line = indexed[requested.purchaseOrderItemId]
                ?: throw ProcurementNotFoundException("Purchase-order line was not found")
            val quantity = requested.quantity.quantity()
            if (quantity > line.remainingQuantity) {
                throw ProcurementConflictException("Receipt exceeds remaining purchase-order quantity")
            }
            ReceiptDraft(
                line.id, line.inventoryItemId, requested.locationId,
                quantity, line.unitPrice.setScale(6),
            )
        }
        val receiptId = UUID.randomUUID()
        val receiptNumber = "GRN-${receiptId.toString().replace("-", "").take(12).uppercase()}"
        val total = receiptLines.sumOf { it.quantity.multiply(it.unitCost).money() }.money()
        jdbc.update(
            """
            INSERT INTO purchase_receipts (
                id, tenant_id, property_id, purchase_order_id, receipt_number,
                supplier_reference, currency, total_amount, received_by,
                idempotency_key_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            receiptId, actor.tenantId, propertyId, id, receiptNumber,
            request.supplierReference.clean(), po.currency, total,
            actor.tenantUserId, key,
        )
        val stock = inventory.receiveStock(
            ReceiveStockCommand(
                actor.tenantId, propertyId, receiptId, actor.tenantUserId,
                receiptLines.map {
                    ReceiveStockLine(
                        it.purchaseOrderItemId, it.inventoryItemId, it.locationId,
                        it.quantity, it.unitCost,
                    )
                },
            ),
        )
        receiptLines.forEach { line ->
            val movementId = requireNotNull(
                stock.movementIdsByPurchaseOrderItem[line.purchaseOrderItemId],
            )
            jdbc.update(
                """
                INSERT INTO purchase_receipt_lines (
                    tenant_id, property_id, receipt_id, purchase_order_item_id,
                    inventory_item_id, location_id, quantity, unit_cost,
                    line_total, movement_batch_id, stock_movement_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                actor.tenantId, propertyId, receiptId, line.purchaseOrderItemId,
                line.inventoryItemId, line.locationId, line.quantity, line.unitCost,
                line.quantity.multiply(line.unitCost).money(), stock.batchId, movementId,
            )
            val changed = jdbc.update(
                """
                UPDATE purchase_order_items
                SET received_quantity = received_quantity + ?, updated_at = now()
                WHERE tenant_id = ? AND purchase_order_id = ? AND id = ?
                  AND received_quantity + ? <= quantity
                """.trimIndent(),
                line.quantity, actor.tenantId, id, line.purchaseOrderItemId, line.quantity,
            )
            if (changed != 1) {
                throw ProcurementConflictException("Receipt exceeds remaining quantity")
            }
        }
        val remaining = jdbc.queryForObject(
            """
            SELECT count(*) FROM purchase_order_items
            WHERE tenant_id = ? AND purchase_order_id = ? AND received_quantity < quantity
            """.trimIndent(),
            Int::class.java, actor.tenantId, id,
        ) ?: 0
        jdbc.update(
            """
            UPDATE purchase_orders SET status = ?,
                actual_delivery_date = CASE WHEN ? = 'received' THEN current_date ELSE actual_delivery_date END,
                updated_at = now()
            WHERE tenant_id = ? AND property_id = ? AND id = ?
            """.trimIndent(),
            if (remaining == 0) "received" else "partially_received",
            if (remaining == 0) "received" else "partially_received",
            actor.tenantId, propertyId, id,
        )
        receipt(actor.tenantId, propertyId, receiptId).also {
            commands.effects(
                actor, propertyId, "procurement.purchase_order.received", RECEIPTS, receiptId,
                mapOf("receiptId" to receiptId, "purchaseOrderId" to id, "total" to total), key,
            )
        }
    }

    private fun supplier(tenantId: UUID, id: UUID) = jdbc.query(
        "$SUPPLIER_SELECT AND id = ?",
        ::mapSupplier, tenantId, id,
    ).singleOrNull() ?: throw ProcurementNotFoundException("Supplier was not found")

    private fun order(
        tenantId: UUID, propertyId: UUID, id: UUID, lock: Boolean = false,
    ) = jdbc.query(
        "$ORDER_SELECT AND po.id = ? ${if (lock) "FOR UPDATE OF po" else ""}",
        { rs, _ -> mapOrder(rs, tenantId, lock) },
        tenantId, propertyId, id,
    ).singleOrNull() ?: throw ProcurementNotFoundException("Purchase order was not found")

    private fun orderLines(tenantId: UUID, orderId: UUID, lock: Boolean = false) =
        jdbc.query(
            """
            SELECT id, inventory_item_id, quantity, received_quantity, unit_price,
                   total_price
            FROM purchase_order_items
            WHERE tenant_id = ? AND purchase_order_id = ?
            ORDER BY id
            ${if (lock) "FOR UPDATE" else ""}
            """.trimIndent(),
            { rs, _ ->
                val quantity = rs.getBigDecimal("quantity")
                val received = rs.getBigDecimal("received_quantity")
                PurchaseOrderLineResponse(
                    rs.uuid("id"), rs.uuid("inventory_item_id"), quantity, received,
                    quantity.subtract(received), rs.getBigDecimal("unit_price"),
                    rs.getBigDecimal("total_price"),
                )
            },
            tenantId, orderId,
        )

    private fun receipt(tenantId: UUID, propertyId: UUID, id: UUID): PurchaseReceiptResponse {
        val lines = jdbc.query(
            """
            SELECT purchase_order_item_id, inventory_item_id, location_id,
                   quantity, unit_cost, stock_movement_id
            FROM purchase_receipt_lines
            WHERE tenant_id = ? AND property_id = ? AND receipt_id = ?
            ORDER BY id
            """.trimIndent(),
            { rs, _ ->
                PurchaseReceiptLineResponse(
                    rs.uuid("purchase_order_item_id"), rs.uuid("inventory_item_id"),
                    rs.uuid("location_id"), rs.getBigDecimal("quantity"),
                    rs.getBigDecimal("unit_cost"), rs.uuid("stock_movement_id"),
                )
            },
            tenantId, propertyId, id,
        )
        return jdbc.query(
            """
            SELECT id, property_id, purchase_order_id, receipt_number, currency,
                   total_amount, received_by, received_at
            FROM purchase_receipts
            WHERE tenant_id = ? AND property_id = ? AND id = ?
            """.trimIndent(),
            { rs, _ ->
                PurchaseReceiptResponse(
                    rs.uuid("id"), rs.uuid("property_id"), rs.uuid("purchase_order_id"),
                    rs.getString("receipt_number"), rs.getString("currency").trim(),
                    rs.getBigDecimal("total_amount"), rs.uuid("received_by"),
                    rs.getTimestamp("received_at").toInstant(), lines,
                )
            },
            tenantId, propertyId, id,
        ).single()
    }

    private fun requireInventoryItem(tenantId: UUID, itemId: UUID) {
        val exists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM inventory_items WHERE tenant_id = ? AND id = ? AND is_active)",
            Boolean::class.java, tenantId, itemId,
        ) == true
        if (!exists) throw ProcurementNotFoundException("Inventory item was not found")
    }

    private fun mapSupplier(rs: ResultSet, ignored: Int) = SupplierResponse(
        rs.uuid("id"), rs.getString("name"), rs.getString("code"),
        rs.getString("contact_name"), rs.getString("contact_email"),
        rs.getString("contact_phone"), rs.getBoolean("is_active"),
    )

    private fun mapOrder(rs: ResultSet, tenantId: UUID, lockLines: Boolean = false) =
        PurchaseOrderResponse(
            rs.uuid("id"), rs.uuid("property_id"), rs.getString("order_number"),
            rs.uuid("supplier_id"), rs.getString("currency").trim(),
            rs.getBigDecimal("total_amount"),
            PurchaseOrderStatus.valueOf(rs.getString("status").uppercase(Locale.ROOT)),
            rs.uuidOrNull("created_by"), rs.uuidOrNull("approved_by"),
            orderLines(tenantId, rs.uuid("id"), lockLines),
        )

    private fun BigDecimal.quantity(): BigDecimal {
        require(signum() > 0 && stripTrailingZeros().scale() <= 3) {
            "Quantity must be positive with at most three decimal places"
        }
        return setScale(3, RoundingMode.UNNECESSARY)
    }
    private fun BigDecimal.money() = setScale(2, RoundingMode.HALF_UP)
    private fun String.required() = trim().takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Value is required")
    private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }
    private fun ResultSet.uuid(column: String) = getObject(column, UUID::class.java)
    private fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

    private data class ReceiptDraft(
        val purchaseOrderItemId: UUID,
        val inventoryItemId: UUID,
        val locationId: UUID,
        val quantity: BigDecimal,
        val unitCost: BigDecimal,
    )

    private companion object {
        const val SUPPLIERS = "suppliers"
        const val ORDERS = "purchase_orders"
        const val RECEIPTS = "purchase_receipts"
        const val SUPPLIER_SELECT = """
            SELECT id, name, code, contact_name, contact_email, contact_phone, is_active
            FROM suppliers
            WHERE tenant_id = ? AND deleted_at IS NULL
        """
        const val ORDER_SELECT = """
            SELECT po.id, po.property_id, po.order_number, po.supplier_id, po.currency,
                   po.total_amount, po.status, po.created_by, po.approved_by
            FROM purchase_orders po
            WHERE po.tenant_id = ? AND po.property_id = ?
        """
    }
}
