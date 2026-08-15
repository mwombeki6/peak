package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.inventory.api.ConsumePosRecipesCommand
import com.mwombeki.peak.inventory.api.InventoryPort
import com.mwombeki.peak.inventory.api.PosConsumptionSnapshot
import com.mwombeki.peak.inventory.api.PosRecipeLine
import com.mwombeki.peak.inventory.api.ReturnPosConsumptionCommand
import com.mwombeki.peak.pos.api.KitchenTicketItemResponse
import com.mwombeki.peak.pos.api.KitchenTicketReasonRequest
import com.mwombeki.peak.pos.api.KitchenTicketResponse
import com.mwombeki.peak.pos.api.KitchenTicketStatus
import com.mwombeki.peak.pos.api.PosConflictException
import com.mwombeki.peak.pos.api.PosItemVoidResponse
import com.mwombeki.peak.pos.api.PosNotFoundException
import com.mwombeki.peak.pos.api.PosVoidDisposition
import com.mwombeki.peak.pos.api.SendPosOrderRequest
import com.mwombeki.peak.pos.api.VoidPosOrderItemRequest
import com.mwombeki.peak.realtime.api.RealtimeEventRequest
import com.mwombeki.peak.realtime.api.RealtimeEventTypes
import com.mwombeki.peak.realtime.api.RealtimePort
import java.math.BigDecimal
import java.sql.ResultSet
import java.util.Locale
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class PosKitchenService(
    private val jdbc: JdbcTemplate,
    private val commands: PosCommandExecutor,
    private val inventory: InventoryPort,
    private val realtime: ObjectProvider<RealtimePort>,
    private val mapper: ObjectMapper,
) {
    fun send(
        propertyId: UUID,
        orderId: UUID,
        request: SendPosOrderRequest,
    ): KitchenTicketResponse = commands.mutate(
        propertyId, "pos.order.send", mapOf("orderId" to orderId, "request" to request),
        KITCHEN_TICKETS, KitchenTicketResponse::class.java, KitchenTicketResponse::id,
        { it.copy(replayed = true) },
    ) { actor, key ->
        val operationId = request.clientOperationId.operationId()
        existingTicket(actor.tenantId, propertyId, orderId, operationId)?.let {
            return@mutate it.copy(replayed = true)
        }
        val order = requireOrder(actor.tenantId, propertyId, orderId)
        if (order.status != "open") {
            throw PosConflictException("Only an open POS order can be sent")
        }
        val items = unsentItems(actor.tenantId, orderId)
        if (items.isEmpty()) {
            throw PosConflictException("POS order has no unsent items")
        }
        val ticketId = UUID.randomUUID()
        val consumed = inventory.consumePosRecipes(
            ConsumePosRecipesCommand(
                actor.tenantId, propertyId, ticketId, actor.tenantUserId,
                items.map { PosRecipeLine(it.id, it.menuItemId, it.quantity) },
            ),
        )
        val number = "KDS-${ticketId.toString().replace("-", "").take(12).uppercase()}"
        jdbc.update(
            """
            INSERT INTO kitchen_tickets (
                id, tenant_id, property_id, order_id, outlet_id, ticket_number,
                client_operation_id, status, sent_by, sent_at, consumption_batch_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', ?, now(), ?)
            """.trimIndent(),
            ticketId, actor.tenantId, propertyId, orderId, order.outletId, number,
            operationId, actor.tenantUserId, consumed.batchId,
        )
        items.forEach { item ->
            jdbc.update(
                """
                INSERT INTO kitchen_ticket_items (
                    id, tenant_id, property_id, kitchen_ticket_id, pos_order_item_id,
                    quantity, item_name, modifiers, special_request
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """.trimIndent(),
                UUID.randomUUID(), actor.tenantId, propertyId, ticketId, item.id,
                item.quantity, item.name, item.modifiersJson, item.specialRequest,
            )
            jdbc.update(
                """
                UPDATE pos_order_items
                SET sent_quantity = quantity, service_state = 'sent', updated_at = now()
                WHERE tenant_id = ? AND order_id = ? AND id = ?
                  AND voided = false AND sent_quantity = 0
                """.trimIndent(),
                actor.tenantId, orderId, item.id,
            )
        }
        consumed.snapshots.forEach { snapshot ->
            jdbc.update(
                """
                INSERT INTO pos_recipe_consumption_snapshots (
                    tenant_id, property_id, kitchen_ticket_id, pos_order_item_id,
                    menu_item_id, inventory_item_id, location_id, quantity_per_item,
                    order_item_quantity, consumed_quantity, unit_cost, stock_movement_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                actor.tenantId, propertyId, ticketId, snapshot.posOrderItemId,
                snapshot.menuItemId, snapshot.inventoryItemId, snapshot.locationId,
                snapshot.quantityPerItem, snapshot.orderItemQuantity,
                snapshot.consumedQuantity, snapshot.unitCost, snapshot.stockMovementId,
            )
        }
ticket(actor.tenantId, propertyId, ticketId).also { created ->
                commands.recordSideEffects(
                    actor, propertyId, "pos.kitchen_ticket.created", KITCHEN_TICKETS,
                    ticketId, mapOf(
                        "ticketId" to ticketId, "orderId" to orderId,
                        "itemCount" to items.size, "consumptionBatchId" to consumed.batchId,
                    ), key,
                )
                realtime.ifAvailable {
                    it.broadcastRealtimeEvent(
                        RealtimeEventRequest(
                            tenantId = actor.tenantId,
                            propertyId = propertyId,
                            outletId = order.outletId,
                            eventType = RealtimeEventTypes.POS_ORDER_SENT,
                            aggregateType = RealtimeEventTypes.AGGREGATE_POS_ORDER,
                            aggregateId = orderId,
                            aggregateVersion = orderVersion(actor.tenantId, orderId),
                            payload = mapOf(
                                "orderId" to orderId,
                                "ticketId" to ticketId,
                                "itemCount" to items.size,
                            ),
                        ),
                    )
                    it.broadcastRealtimeEvent(
                        RealtimeEventRequest(
                            tenantId = actor.tenantId,
                            propertyId = propertyId,
                            outletId = order.outletId,
                            eventType = RealtimeEventTypes.KITCHEN_TICKET_CREATED,
                            aggregateType = RealtimeEventTypes.AGGREGATE_KITCHEN_TICKET,
                            aggregateId = ticketId,
                            aggregateVersion = 0,
                            payload = mapOf(
                                "ticketId" to ticketId,
                                "orderId" to orderId,
                                "ticketNumber" to created.ticketNumber,
                                "itemCount" to items.size,
                                "status" to created.status.name.lowercase(),
                            ),
                        ),
                    )
                }
            }
        }

    fun voidItem(
        propertyId: UUID,
        orderId: UUID,
        itemId: UUID,
        request: VoidPosOrderItemRequest,
    ): PosItemVoidResponse = commands.mutate(
        propertyId, "pos.order.item.void",
        mapOf("orderId" to orderId, "itemId" to itemId, "request" to request),
        POS_ORDER_ITEMS, PosItemVoidResponse::class.java, { it.itemId },
        { it.copy(replayed = true) },
    ) { actor, key ->
        val order = requireOrder(actor.tenantId, propertyId, orderId)
        if (order.status != "open") throw PosConflictException("Only open order items can be voided")
        val item = requireItem(actor.tenantId, orderId, itemId)
        if (item.voided) throw PosConflictException("POS item is already voided")
        val sent = item.sentQuantity > BigDecimal.ZERO
        val disposition = if (!sent) {
            "no_stock_effect"
        } else {
            when (request.disposition) {
                PosVoidDisposition.RETURN_TO_STOCK -> "return_to_stock"
                PosVoidDisposition.WASTE -> "waste"
                null -> throw PosConflictException(
                    "Post-send void requires RETURN_TO_STOCK or WASTE disposition",
                )
            }
        }
        val snapshots = if (sent) snapshots(actor.tenantId, propertyId, itemId) else emptyList()
        val returnBatch = if (disposition == "return_to_stock") {
            if (snapshots.isEmpty()) {
                throw PosConflictException("Sent item has no consumption snapshot")
            }
            inventory.returnPosConsumption(
                ReturnPosConsumptionCommand(
                    actor.tenantId, propertyId, itemId, actor.tenantUserId, snapshots,
                ),
            )
        } else null
        jdbc.update(
            """
            UPDATE pos_order_items SET voided = true, service_state = 'voided',
                void_disposition = ?, voided_by = ?, voided_at = now(),
                void_reason = ?, updated_at = now()
            WHERE tenant_id = ? AND order_id = ? AND id = ? AND voided = false
            """.trimIndent(),
            disposition, actor.tenantUserId, request.reason.required(),
            actor.tenantId, orderId, itemId,
        )
        if (returnBatch != null) {
            jdbc.update(
                """
                UPDATE pos_recipe_consumption_snapshots
                SET returned_quantity = consumed_quantity
                WHERE tenant_id = ? AND property_id = ? AND pos_order_item_id = ?
                  AND returned_quantity = 0
                """.trimIndent(),
                actor.tenantId, propertyId, itemId,
            )
        }
        jdbc.update(
            """
            INSERT INTO pos_item_void_dispositions (
                tenant_id, property_id, pos_order_id, pos_order_item_id,
                disposition, reason, actor_id, return_batch_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            actor.tenantId, propertyId, orderId, itemId, disposition,
            request.reason.required(), actor.tenantUserId, returnBatch,
        )
        recalculate(actor.tenantId, propertyId, orderId)
        PosItemVoidResponse(orderId, itemId, disposition.uppercase(), returnBatch).also {
            commands.recordSideEffects(
                actor, propertyId, "pos.order.item.voided", POS_ORDER_ITEMS, itemId,
                mapOf(
                    "orderId" to orderId, "itemId" to itemId,
                    "disposition" to disposition, "returnBatchId" to returnBatch,
                ), key,
            )
            realtime.ifAvailable {
                it.broadcastRealtimeEvent(
                    RealtimeEventRequest(
                        tenantId = actor.tenantId,
                        propertyId = propertyId,
                        outletId = order.outletId,
                        eventType = RealtimeEventTypes.POS_ORDER_ITEM_VOIDED,
                        aggregateType = RealtimeEventTypes.AGGREGATE_POS_ORDER,
                        aggregateId = orderId,
                        aggregateVersion = orderVersion(actor.tenantId, orderId),
                        payload = mapOf(
                            "orderId" to orderId, "itemId" to itemId,
                            "disposition" to disposition, "returnBatchId" to returnBatch,
                        ),
                    ),
                )
            }
        }
    }

    fun listTickets(propertyId: UUID): List<KitchenTicketResponse> =
        commands.read(propertyId) { actor ->
            jdbc.query(
                "$TICKET_SELECT ORDER BY kt.created_at DESC LIMIT 500",
                { rs, _ -> mapTicket(rs, actor.tenantId) },
                actor.tenantId, propertyId,
            )
        }

    fun transition(
        propertyId: UUID,
        ticketId: UUID,
        action: String,
        reason: KitchenTicketReasonRequest?,
    ): KitchenTicketResponse = commands.mutate(
        propertyId, "pos.kitchen_ticket.$action",
        mapOf("ticketId" to ticketId, "reason" to reason), KITCHEN_TICKETS,
        KitchenTicketResponse::class.java, KitchenTicketResponse::id,
        { it.copy(replayed = true) },
    ) { actor, key ->
        val current = ticket(actor.tenantId, propertyId, ticketId, true)
        val target = when (action.lowercase(Locale.ROOT)) {
            "prepare" -> {
                requireStatus(current, KitchenTicketStatus.PENDING)
                "preparing"
            }
            "ready" -> {
                requireStatus(current, KitchenTicketStatus.PREPARING)
                "ready"
            }
            "deliver" -> {
                requireStatus(current, KitchenTicketStatus.READY)
                "delivered"
            }
            "void" -> {
                if (current.status !in setOf(
                        KitchenTicketStatus.PENDING, KitchenTicketStatus.PREPARING,
                    )
                ) throw PosConflictException("Ticket cannot be voided from ${current.status}")
                val activeItems = jdbc.queryForObject(
                    """
                    SELECT count(*) FROM kitchen_ticket_items kti
                    JOIN pos_order_items poi
                      ON poi.tenant_id = kti.tenant_id AND poi.id = kti.pos_order_item_id
                    WHERE kti.tenant_id = ? AND kti.kitchen_ticket_id = ?
                      AND poi.voided = false
                    """.trimIndent(),
                    Int::class.java, actor.tenantId, ticketId,
                ) ?: 0
                if (activeItems > 0) {
                    throw PosConflictException("Void ticket items with stock disposition first")
                }
                requireNotNull(reason).reason.required()
                "voided"
            }
            else -> throw IllegalArgumentException("Unsupported kitchen-ticket action")
        }
        jdbc.update(
            """
            UPDATE kitchen_tickets SET status = ?,
                ready_at = CASE WHEN ? = 'ready' THEN now() ELSE ready_at END,
                delivered_at = CASE WHEN ? = 'delivered' THEN now() ELSE delivered_at END,
                voided_by = CASE WHEN ? = 'voided' THEN ? ELSE voided_by END,
                voided_at = CASE WHEN ? = 'voided' THEN now() ELSE voided_at END,
                void_reason = CASE WHEN ? = 'voided' THEN ? ELSE void_reason END,
                updated_at = now()
            WHERE tenant_id = ? AND property_id = ? AND id = ? AND status = ?
            """.trimIndent(),
            target, target, target, target, actor.tenantUserId, target, target,
            reason?.reason?.trim(), actor.tenantId, propertyId, ticketId,
            current.status.name.lowercase(),
        )
        if (target != "voided") {
            jdbc.update(
                """
                UPDATE pos_order_items poi SET service_state = ?, updated_at = now()
                FROM kitchen_ticket_items kti
                WHERE kti.tenant_id = poi.tenant_id
                  AND kti.pos_order_item_id = poi.id
                  AND kti.tenant_id = ? AND kti.kitchen_ticket_id = ?
                  AND poi.voided = false
                """.trimIndent(),
                target, actor.tenantId, ticketId,
            )
        }
        ticket(actor.tenantId, propertyId, ticketId).also {
            commands.recordSideEffects(
                actor, propertyId, "pos.kitchen_ticket.$target", KITCHEN_TICKETS,
                ticketId, mapOf("ticketId" to ticketId, "status" to target), key,
            )
            realtime.ifAvailable {
                it.broadcastRealtimeEvent(
                    RealtimeEventRequest(
                        tenantId = actor.tenantId,
                        propertyId = propertyId,
                        outletId = current.outletId,
                        eventType = "pos.kitchen_ticket.$target",
                        aggregateType = RealtimeEventTypes.AGGREGATE_KITCHEN_TICKET,
                        aggregateId = ticketId,
                        aggregateVersion = ticketVersion(actor.tenantId, ticketId),
                        payload = mapOf(
                            "ticketId" to ticketId,
                            "orderId" to current.orderId,
                            "status" to target,
                        ),
                    ),
                )
            }
        }
    }

    private fun existingTicket(
        tenantId: UUID, propertyId: UUID, orderId: UUID, operationId: String,
    ) = jdbc.query(
        "$TICKET_SELECT AND kt.order_id = ? AND kt.client_operation_id = ?",
        { rs, _ -> mapTicket(rs, tenantId) },
        tenantId, propertyId, orderId, operationId,
    ).singleOrNull()

    private fun ticket(
        tenantId: UUID, propertyId: UUID, id: UUID, lock: Boolean = false,
    ) = jdbc.query(
        "$TICKET_SELECT AND kt.id = ? ${if (lock) "FOR UPDATE OF kt" else ""}",
        { rs, _ -> mapTicket(rs, tenantId) },
        tenantId, propertyId, id,
    ).singleOrNull() ?: throw PosNotFoundException("Kitchen ticket was not found")

    private fun mapTicket(rs: ResultSet, tenantId: UUID): KitchenTicketResponse {
        val id = rs.getObject("id", UUID::class.java)
        return KitchenTicketResponse(
            id, rs.getObject("property_id", UUID::class.java),
            rs.getObject("order_id", UUID::class.java),
            rs.getObject("outlet_id", UUID::class.java), rs.getString("ticket_number"),
            KitchenTicketStatus.valueOf(rs.getString("status").uppercase(Locale.ROOT)),
            rs.getTimestamp("sent_at").toInstant(),
            rs.getTimestamp("ready_at")?.toInstant(),
            rs.getTimestamp("delivered_at")?.toInstant(),
            ticketItems(tenantId, id),
        )
    }

    private fun ticketItems(tenantId: UUID, ticketId: UUID) = jdbc.query(
        """
        SELECT id, pos_order_item_id, item_name, quantity, modifiers::text,
               special_request
        FROM kitchen_ticket_items
        WHERE tenant_id = ? AND kitchen_ticket_id = ?
        ORDER BY created_at, id
        """.trimIndent(),
        { rs, _ ->
            KitchenTicketItemResponse(
                rs.getObject("id", UUID::class.java),
                rs.getObject("pos_order_item_id", UUID::class.java),
                rs.getString("item_name"), rs.getBigDecimal("quantity"),
                mapper.readValue(rs.getString("modifiers"), Array<String>::class.java).toList(),
                rs.getString("special_request"),
            )
        },
        tenantId, ticketId,
    )

    private fun requireOrder(tenantId: UUID, propertyId: UUID, orderId: UUID) =
        jdbc.query(
            """
            SELECT id, outlet_id, status FROM pos_orders
            WHERE tenant_id = ? AND property_id = ? AND id = ? AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                OrderRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("outlet_id", UUID::class.java), rs.getString("status"),
                )
            },
            tenantId, propertyId, orderId,
        ).singleOrNull() ?: throw PosNotFoundException("POS order was not found")

    private fun unsentItems(tenantId: UUID, orderId: UUID) = jdbc.query(
        """
        SELECT id, menu_item_id, item_name, quantity - sent_quantity quantity,
               modifiers::text, special_request, sent_quantity, voided
        FROM pos_order_items
        WHERE tenant_id = ? AND order_id = ? AND voided = false
          AND sent_quantity < quantity
        ORDER BY created_at, id
        FOR UPDATE
        """.trimIndent(),
        ::mapItem, tenantId, orderId,
    )

    private fun requireItem(tenantId: UUID, orderId: UUID, itemId: UUID) =
        jdbc.query(
            """
            SELECT id, menu_item_id, item_name, quantity, modifiers::text,
                   special_request, sent_quantity, voided
            FROM pos_order_items
            WHERE tenant_id = ? AND order_id = ? AND id = ?
            FOR UPDATE
            """.trimIndent(),
            ::mapItem, tenantId, orderId, itemId,
        ).singleOrNull() ?: throw PosNotFoundException("POS item was not found")

    private fun mapItem(rs: ResultSet, ignored: Int) = ItemRow(
        rs.getObject("id", UUID::class.java),
        rs.getObject("menu_item_id", UUID::class.java), rs.getString("item_name"),
        rs.getBigDecimal("quantity"), rs.getString("modifiers"),
        rs.getString("special_request"), rs.getBigDecimal("sent_quantity"),
        rs.getBoolean("voided"),
    )

    private fun snapshots(tenantId: UUID, propertyId: UUID, itemId: UUID) = jdbc.query(
        """
        SELECT pos_order_item_id, menu_item_id, inventory_item_id, location_id,
               quantity_per_item, order_item_quantity, consumed_quantity,
               unit_cost, stock_movement_id
        FROM pos_recipe_consumption_snapshots
        WHERE tenant_id = ? AND property_id = ? AND pos_order_item_id = ?
          AND returned_quantity = 0
        ORDER BY inventory_item_id, location_id
        FOR UPDATE
        """.trimIndent(),
        { rs, _ ->
            PosConsumptionSnapshot(
                rs.getObject("pos_order_item_id", UUID::class.java),
                rs.getObject("menu_item_id", UUID::class.java),
                rs.getObject("inventory_item_id", UUID::class.java),
                rs.getObject("location_id", UUID::class.java),
                rs.getBigDecimal("quantity_per_item"),
                rs.getBigDecimal("order_item_quantity"),
                rs.getBigDecimal("consumed_quantity"), rs.getBigDecimal("unit_cost"),
                rs.getObject("stock_movement_id", UUID::class.java),
            )
        },
        tenantId, propertyId, itemId,
    )

    private fun recalculate(tenantId: UUID, propertyId: UUID, orderId: UUID) {
        jdbc.update(
            """
            UPDATE pos_orders po SET
                subtotal = x.subtotal, tax_amount = x.tax_amount,
                total_amount = x.total_amount, updated_at = now()
            FROM (
                SELECT order_id,
                    COALESCE(sum(subtotal) FILTER (WHERE voided = false), 0) subtotal,
                    COALESCE(sum(tax_amount) FILTER (WHERE voided = false), 0) tax_amount,
                    COALESCE(sum(total_price) FILTER (WHERE voided = false), 0) total_amount
                FROM pos_order_items WHERE tenant_id = ? AND order_id = ?
                GROUP BY order_id
            ) x
            WHERE po.tenant_id = ? AND po.property_id = ? AND po.id = x.order_id
            """.trimIndent(),
            tenantId, orderId, tenantId, propertyId,
        )
    }

    private fun orderVersion(tenantId: UUID, orderId: UUID): Long {
        return jdbc.queryForObject(
            "SELECT version FROM pos_orders WHERE tenant_id = ? AND id = ?",
            Long::class.java,
            tenantId,
            orderId,
        ) ?: 0L
    }

    private fun ticketVersion(tenantId: UUID, ticketId: UUID): Long {
        return jdbc.queryForObject(
            "SELECT version FROM kitchen_tickets WHERE tenant_id = ? AND id = ?",
            Long::class.java,
            tenantId,
            ticketId,
        ) ?: 0L
    }

    private fun requireStatus(ticket: KitchenTicketResponse, expected: KitchenTicketStatus) {
        if (ticket.status != expected) {
            throw PosConflictException("Ticket must be $expected")
        }
    }

    private fun String.operationId() = trim().takeIf { it.isNotEmpty() && it.length <= 100 }
        ?: throw IllegalArgumentException("clientOperationId is required")
    private fun String.required() = trim().takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Reason is required")

    private data class OrderRow(val id: UUID, val outletId: UUID, val status: String)
    private data class ItemRow(
        val id: UUID,
        val menuItemId: UUID,
        val name: String,
        val quantity: BigDecimal,
        val modifiersJson: String,
        val specialRequest: String?,
        val sentQuantity: BigDecimal,
        val voided: Boolean,
    )

    private companion object {
        const val POS_ORDER_ITEMS = "pos_order_items"
        const val KITCHEN_TICKETS = "kitchen_tickets"
        const val TICKET_SELECT = """
            SELECT kt.id, kt.property_id, kt.order_id, kt.outlet_id,
                   kt.ticket_number, kt.status, kt.sent_at, kt.ready_at,
                   kt.delivered_at
            FROM kitchen_tickets kt
            WHERE kt.tenant_id = ? AND kt.property_id = ?
        """
    }
}
