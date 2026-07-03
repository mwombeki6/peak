package com.mwombeki.peak.inventory.internal

import com.mwombeki.peak.inventory.api.ConsumePosRecipesCommand
import com.mwombeki.peak.inventory.api.ConsumePosRecipesResult
import com.mwombeki.peak.inventory.api.CreateInventoryItemRequest
import com.mwombeki.peak.inventory.api.CreateInventoryLocationRequest
import com.mwombeki.peak.inventory.api.InventoryCommandResponse
import com.mwombeki.peak.inventory.api.InventoryConflictException
import com.mwombeki.peak.inventory.api.InventoryItemResponse
import com.mwombeki.peak.inventory.api.InventoryLevelResponse
import com.mwombeki.peak.inventory.api.InventoryLocationResponse
import com.mwombeki.peak.inventory.api.InventoryNotFoundException
import com.mwombeki.peak.inventory.api.InventoryPort
import com.mwombeki.peak.inventory.api.PosConsumptionSnapshot
import com.mwombeki.peak.inventory.api.ReceiveStockCommand
import com.mwombeki.peak.inventory.api.ReceiveStockResult
import com.mwombeki.peak.inventory.api.RecipeComponentResponse
import com.mwombeki.peak.inventory.api.RecipeResponse
import com.mwombeki.peak.inventory.api.ReturnPosConsumptionCommand
import com.mwombeki.peak.inventory.api.StockAdjustmentRequest
import com.mwombeki.peak.inventory.api.StockCommandRequest
import com.mwombeki.peak.inventory.api.StockMovementResponse
import com.mwombeki.peak.inventory.api.TransferStockRequest
import com.mwombeki.peak.inventory.api.UpdateInventoryItemRequest
import com.mwombeki.peak.inventory.api.UpdateInventoryLocationRequest
import com.mwombeki.peak.inventory.api.UpsertRecipeRequest
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.util.Locale
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class InventoryService(
    private val jdbc: JdbcTemplate,
    private val commands: InventoryCommandExecutor,
    private val outbox: OutboxPort,
) : InventoryPort {
    fun listItems(propertyId: UUID): List<InventoryItemResponse> =
        commands.read(propertyId) { actor ->
            jdbc.query(
                """
                SELECT DISTINCT ii.id, ii.name, ii.sku, ii.category, ii.unit,
                       ii.reorder_level, ii.is_active
                FROM inventory_items ii
                WHERE ii.tenant_id = ?
                  AND (
                      EXISTS (
                          SELECT 1 FROM stock_levels sl
                          JOIN inventory_locations il
                            ON il.tenant_id = sl.tenant_id AND il.id = sl.location_id
                          WHERE sl.tenant_id = ii.tenant_id AND sl.item_id = ii.id
                            AND il.property_id = ?
                      )
                      OR ii.is_active
                  )
                ORDER BY ii.name
                """.trimIndent(),
                ::mapItem, actor.tenantId, propertyId,
            )
        }

    fun createItem(
        propertyId: UUID,
        request: CreateInventoryItemRequest,
    ) = commands.mutate(
        propertyId, "inventory.item.create", request, ITEMS,
        InventoryItemResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO inventory_items (
                id, tenant_id, name, sku, category, unit, reorder_level,
                cost_per_unit, current_stock, is_active
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, true)
            """.trimIndent(),
            id, actor.tenantId, request.name.required(), request.sku.clean(),
            request.category.clean(), request.unit.required(),
            request.reorderLevel.quantity(true),
        )
        item(actor.tenantId, id).also {
            commands.effects(
                actor, propertyId, "inventory.item.created", ITEMS, id,
                mapOf("itemId" to id, "name" to it.name), key,
            )
        }
    }

    fun getItem(propertyId: UUID, itemId: UUID) = commands.read(propertyId) { actor ->
        item(actor.tenantId, itemId)
    }

    fun updateItem(
        propertyId: UUID,
        itemId: UUID,
        request: UpdateInventoryItemRequest,
    ) = commands.mutate(
        propertyId, "inventory.item.update", mapOf("itemId" to itemId, "request" to request),
        ITEMS, InventoryItemResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        item(actor.tenantId, itemId)
        jdbc.update(
            """
            UPDATE inventory_items SET
                name = COALESCE(?, name), sku = COALESCE(?, sku),
                category = COALESCE(?, category),
                reorder_level = COALESCE(?, reorder_level),
                is_active = COALESCE(?, is_active), updated_at = now()
            WHERE tenant_id = ? AND id = ?
            """.trimIndent(),
            request.name?.required(), request.sku?.clean(), request.category?.clean(),
            request.reorderLevel?.quantity(true), request.active, actor.tenantId, itemId,
        )
        item(actor.tenantId, itemId).also {
            commands.effects(
                actor, propertyId, "inventory.item.updated", ITEMS, itemId,
                mapOf("itemId" to itemId), key,
            )
        }
    }

    fun deactivateItem(propertyId: UUID, itemId: UUID) = updateItem(
        propertyId, itemId, UpdateInventoryItemRequest(active = false),
    )

    fun listLocations(propertyId: UUID): List<InventoryLocationResponse> =
        commands.read(propertyId) { actor ->
            jdbc.query(
                "$LOCATION_SELECT ORDER BY name",
                ::mapLocation, actor.tenantId, propertyId,
            )
        }

    fun createLocation(
        propertyId: UUID,
        request: CreateInventoryLocationRequest,
    ) = commands.mutate(
        propertyId, "inventory.location.create", request, LOCATIONS,
        InventoryLocationResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        val type = request.type.locationType()
        request.outletId?.let { requireOutlet(actor.tenantId, propertyId, it) }
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO inventory_locations (
                id, tenant_id, property_id, outlet_id, name, type
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, actor.tenantId, propertyId, request.outletId,
            request.name.required(), type,
        )
        location(actor.tenantId, propertyId, id).also {
            commands.effects(
                actor, propertyId, "inventory.location.created", LOCATIONS, id,
                mapOf("locationId" to id, "type" to type), key,
            )
        }
    }

    fun getLocation(propertyId: UUID, locationId: UUID) = commands.read(propertyId) { actor ->
        location(actor.tenantId, propertyId, locationId)
    }

    fun updateLocation(
        propertyId: UUID,
        locationId: UUID,
        request: UpdateInventoryLocationRequest,
    ) = commands.mutate(
        propertyId, "inventory.location.update",
        mapOf("locationId" to locationId, "request" to request), LOCATIONS,
        InventoryLocationResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        location(actor.tenantId, propertyId, locationId)
        request.outletId?.let { requireOutlet(actor.tenantId, propertyId, it) }
        jdbc.update(
            """
            UPDATE inventory_locations SET name = COALESCE(?, name),
                type = COALESCE(?, type), outlet_id = COALESCE(?, outlet_id),
                updated_at = now()
            WHERE tenant_id = ? AND property_id = ? AND id = ?
            """.trimIndent(),
            request.name?.required(), request.type?.locationType(), request.outletId,
            actor.tenantId, propertyId, locationId,
        )
        location(actor.tenantId, propertyId, locationId).also {
            commands.effects(
                actor, propertyId, "inventory.location.updated", LOCATIONS, locationId,
                mapOf("locationId" to locationId), key,
            )
        }
    }

    fun deleteLocation(propertyId: UUID, locationId: UUID) = commands.mutate(
        propertyId, "inventory.location.delete", mapOf("locationId" to locationId), LOCATIONS,
        InventoryLocationResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        val current = location(actor.tenantId, propertyId, locationId)
        val changed = jdbc.update(
            """
            DELETE FROM inventory_locations il
            WHERE il.tenant_id = ? AND il.property_id = ? AND il.id = ?
              AND NOT EXISTS (
                  SELECT 1 FROM stock_levels sl
                  WHERE sl.tenant_id = il.tenant_id AND sl.location_id = il.id
                    AND sl.quantity <> 0
              )
              AND NOT EXISTS (
                  SELECT 1 FROM menu_item_recipes mir
                  WHERE mir.tenant_id = il.tenant_id AND mir.location_id = il.id
              )
            """.trimIndent(),
            actor.tenantId, propertyId, locationId,
        )
        if (changed != 1) {
            throw InventoryConflictException("Location with stock or recipes cannot be deleted")
        }
        current.also {
            commands.effects(
                actor, propertyId, "inventory.location.deleted", LOCATIONS, locationId,
                mapOf("locationId" to locationId), key,
            )
        }
    }

    fun listRecipes(propertyId: UUID): List<RecipeResponse> =
        commands.read(propertyId) { actor ->
            val rows = jdbc.query(
                """
                SELECT menu_item_id, inventory_item_id, location_id, quantity, unit
                FROM menu_item_recipes
                WHERE tenant_id = ? AND property_id = ?
                ORDER BY menu_item_id, inventory_item_id, location_id
                """.trimIndent(),
                { rs, _ ->
                    Pair(
                        rs.getObject("menu_item_id", UUID::class.java),
                        RecipeComponentResponse(
                            rs.getObject("inventory_item_id", UUID::class.java),
                            rs.getObject("location_id", UUID::class.java),
                            rs.getBigDecimal("quantity"), rs.getString("unit"),
                        ),
                    )
                },
                actor.tenantId, propertyId,
            )
            rows.groupBy({ it.first }, { it.second }).map { RecipeResponse(it.key, it.value) }
        }

    fun upsertRecipe(
        propertyId: UUID,
        request: UpsertRecipeRequest,
    ) = commands.mutate(
        propertyId, "inventory.recipe.upsert", request, RECIPES,
        RecipeResponse::class.java, { it.menuItemId }, { it.copy(replayed = true) },
    ) { actor, key ->
        requireMenuItem(actor.tenantId, propertyId, request.menuItemId)
        request.components.forEach {
            item(actor.tenantId, it.inventoryItemId)
            location(actor.tenantId, propertyId, it.locationId)
        }
        jdbc.update(
            "DELETE FROM menu_item_recipes WHERE tenant_id = ? AND property_id = ? AND menu_item_id = ?",
            actor.tenantId, propertyId, request.menuItemId,
        )
        request.components.forEach {
            jdbc.update(
                """
                INSERT INTO menu_item_recipes (
                    tenant_id, property_id, menu_item_id, inventory_item_id,
                    location_id, quantity, unit
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                actor.tenantId, propertyId, request.menuItemId, it.inventoryItemId,
                it.locationId, it.quantity.quantity(), it.unit.required(),
            )
        }
        RecipeResponse(
            request.menuItemId,
            request.components.map {
                RecipeComponentResponse(
                    it.inventoryItemId, it.locationId, it.quantity.quantity(), it.unit.required(),
                )
            },
        ).also {
            commands.effects(
                actor, propertyId, "inventory.recipe.updated", RECIPES, request.menuItemId,
                mapOf("menuItemId" to request.menuItemId, "components" to it.components.size), key,
            )
        }
    }

    fun deleteRecipe(propertyId: UUID, menuItemId: UUID) = commands.mutate(
        propertyId, "inventory.recipe.delete", mapOf("menuItemId" to menuItemId), RECIPES,
        RecipeResponse::class.java, { it.menuItemId }, { it.copy(replayed = true) },
    ) { actor, key ->
        val components = jdbc.query(
            """
            SELECT inventory_item_id, location_id, quantity, unit
            FROM menu_item_recipes
            WHERE tenant_id = ? AND property_id = ? AND menu_item_id = ?
            ORDER BY inventory_item_id, location_id
            """.trimIndent(),
            { rs, _ ->
                RecipeComponentResponse(
                    rs.uuid("inventory_item_id"), rs.uuid("location_id"),
                    rs.getBigDecimal("quantity"), rs.getString("unit"),
                )
            },
            actor.tenantId, propertyId, menuItemId,
        )
        if (components.isEmpty()) throw InventoryNotFoundException("Recipe was not found")
        jdbc.update(
            "DELETE FROM menu_item_recipes WHERE tenant_id = ? AND property_id = ? AND menu_item_id = ?",
            actor.tenantId, propertyId, menuItemId,
        )
        RecipeResponse(menuItemId, components).also {
            commands.effects(
                actor, propertyId, "inventory.recipe.deleted", RECIPES, menuItemId,
                mapOf("menuItemId" to menuItemId), key,
            )
        }
    }

    fun levels(propertyId: UUID): List<InventoryLevelResponse> =
        commands.read(propertyId) { actor ->
            jdbc.query(
                """
                SELECT sl.item_id, ii.name item_name, sl.location_id, il.name location_name,
                       sl.quantity, sl.reorder_level, sl.average_cost,
                       round(sl.quantity * sl.average_cost, 2) stock_value
                FROM stock_levels sl
                JOIN inventory_items ii
                  ON ii.tenant_id = sl.tenant_id AND ii.id = sl.item_id
                JOIN inventory_locations il
                  ON il.tenant_id = sl.tenant_id AND il.id = sl.location_id
                WHERE sl.tenant_id = ? AND il.property_id = ?
                ORDER BY il.name, ii.name
                """.trimIndent(),
                { rs, _ ->
                    InventoryLevelResponse(
                        rs.uuid("item_id"), rs.getString("item_name"),
                        rs.uuid("location_id"), rs.getString("location_name"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("reorder_level"),
                        rs.getBigDecimal("average_cost"), rs.getBigDecimal("stock_value"),
                    )
                },
                actor.tenantId, propertyId,
            )
        }

    fun movements(propertyId: UUID): List<StockMovementResponse> =
        commands.read(propertyId) { actor ->
            jdbc.query(
                "$MOVEMENT_SELECT WHERE sm.tenant_id = ? AND sm.property_id = ? ORDER BY sm.created_at DESC LIMIT 1000",
                ::mapMovement, actor.tenantId, propertyId,
            )
        }

    fun openingBalances(propertyId: UUID, request: StockCommandRequest) =
        stockCommand(propertyId, "opening_balance", request) { line ->
            requireNotNull(line.unitCost) { "Opening balance requires unitCost" }.cost()
        }

    fun waste(propertyId: UUID, request: StockCommandRequest) =
        stockCommand(propertyId, "waste", request) { null }

    fun adjust(propertyId: UUID, request: StockAdjustmentRequest) =
        commands.mutate(
            propertyId, "inventory.adjustment", request, BATCHES,
            InventoryCommandResponse::class.java, { it.batchId }, { it.copy(replayed = true) },
        ) { actor, key ->
            require(request.lines.all { it.quantityDelta.signum() != 0 }) {
                "Adjustment quantityDelta cannot be zero"
            }
            val batch = createBatch(
                actor.tenantId, propertyId, "adjustment", key, actor.tenantUserId,
            )
            val rows = request.lines.sortedBy { "${it.inventoryItemId}:${it.locationId}" }.map {
                item(actor.tenantId, it.inventoryItemId)
                location(actor.tenantId, propertyId, it.locationId)
                val positive = it.quantityDelta.signum() > 0
                val cost = if (positive) {
                    requireNotNull(it.unitCost) { "Positive adjustment requires unitCost" }.cost()
                } else {
                    lockedAverage(actor.tenantId, it.inventoryItemId, it.locationId)
                }
                appendMovement(
                    actor.tenantId, propertyId, batch, it.inventoryItemId, it.locationId,
                    if (positive) "positive_adjustment" else "negative_adjustment",
                    it.quantityDelta.abs().quantity(), cost, "adjustment", key,
                    actor.tenantUserId,
                )
            }
            InventoryCommandResponse(batch, rows).also {
                commands.effects(
                    actor, propertyId, "inventory.adjusted", BATCHES, batch,
                    mapOf("batchId" to batch, "reason" to request.reason), key,
                )
            }
        }

    fun transfer(propertyId: UUID, request: TransferStockRequest) =
        commands.mutate(
            propertyId, "inventory.transfer", request, TRANSFERS,
            InventoryCommandResponse::class.java, { it.batchId }, { it.copy(replayed = true) },
        ) { actor, key ->
            require(request.sourceLocationId != request.destinationLocationId) {
                "Transfer locations must differ"
            }
            location(actor.tenantId, propertyId, request.sourceLocationId)
            location(actor.tenantId, propertyId, request.destinationLocationId)
            val lines = request.lines.sortedBy { it.inventoryItemId.toString() }
            lockLevels(
                actor.tenantId,
                lines.flatMap {
                    listOf(
                        it.inventoryItemId to request.sourceLocationId,
                        it.inventoryItemId to request.destinationLocationId,
                    )
                },
            )
            val transferId = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO inventory_transfers (
                    id, tenant_id, property_id, source_location_id,
                    destination_location_id, created_by
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                transferId, actor.tenantId, propertyId, request.sourceLocationId,
                request.destinationLocationId, actor.tenantUserId,
            )
            val batch = createBatch(
                actor.tenantId, propertyId, "transfer", transferId, actor.tenantUserId,
            )
            jdbc.execute("SET CONSTRAINTS fk_stock_movements_pair DEFERRED")
            val movements = mutableListOf<StockMovementResponse>()
            lines.forEach {
                val quantity = it.quantity.quantity()
                val cost = lockedAverage(
                    actor.tenantId, it.inventoryItemId, request.sourceLocationId,
                )
                val outId = UUID.randomUUID()
                val inId = UUID.randomUUID()
                movements += appendMovement(
                    actor.tenantId, propertyId, batch, it.inventoryItemId,
                    request.sourceLocationId, "transfer_out", quantity, cost,
                    "transfer", transferId, actor.tenantUserId, transferId, inId, outId,
                )
                movements += appendMovement(
                    actor.tenantId, propertyId, batch, it.inventoryItemId,
                    request.destinationLocationId, "transfer_in", quantity, cost,
                    "transfer", transferId, actor.tenantUserId, transferId, outId, inId,
                )
            }
            InventoryCommandResponse(batch, movements).also {
                commands.effects(
                    actor, propertyId, "inventory.transferred", TRANSFERS, transferId,
                    mapOf("transferId" to transferId, "batchId" to batch), key,
                )
            }
        }

    override fun receiveStock(command: ReceiveStockCommand): ReceiveStockResult {
        requireTransaction()
        val batch = createBatch(
            command.tenantId, command.propertyId, "purchase_receipt",
            command.receiptId, command.receivedBy,
        )
        val result = linkedMapOf<UUID, UUID>()
        command.lines.sortedBy { "${it.inventoryItemId}:${it.locationId}" }.forEach {
            item(command.tenantId, it.inventoryItemId)
            location(command.tenantId, command.propertyId, it.locationId)
            val movement = appendMovement(
                command.tenantId, command.propertyId, batch, it.inventoryItemId,
                it.locationId, "receipt", it.quantity.quantity(), it.unitCost.cost(),
                "purchase_receipt", command.receiptId, command.receivedBy,
            )
            result[it.purchaseOrderItemId] = movement.id
        }
        return ReceiveStockResult(batch, result)
    }

    override fun consumePosRecipes(command: ConsumePosRecipesCommand): ConsumePosRecipesResult {
        requireTransaction()
        val recipes = command.lines.flatMap { line ->
            jdbc.query(
                """
                SELECT inventory_item_id, location_id, quantity
                FROM menu_item_recipes
                WHERE tenant_id = ? AND property_id = ? AND menu_item_id = ?
                ORDER BY inventory_item_id, location_id
                """.trimIndent(),
                { rs, _ ->
                    RecipeUse(
                        line.posOrderItemId, line.menuItemId,
                        rs.uuid("inventory_item_id"), rs.uuid("location_id"),
                        rs.getBigDecimal("quantity").quantity(), line.quantity.quantity(),
                    )
                },
                command.tenantId, command.propertyId, line.menuItemId,
            )
        }
        lockLevels(
            command.tenantId,
            recipes.map { it.inventoryItemId to it.locationId }.distinct(),
        )
        val batch = createBatch(
            command.tenantId, command.propertyId, "pos_consumption",
            command.kitchenTicketId, command.actorId,
        )
        val snapshots = recipes.map {
            val consumed = it.perItem.multiply(it.orderQuantity).quantity()
            val cost = lockedAverage(command.tenantId, it.inventoryItemId, it.locationId)
            val movement = appendMovement(
                command.tenantId, command.propertyId, batch, it.inventoryItemId,
                it.locationId, "consumption", consumed, cost,
                "kitchen_ticket", command.kitchenTicketId, command.actorId,
            )
            PosConsumptionSnapshot(
                it.posOrderItemId, it.menuItemId, it.inventoryItemId, it.locationId,
                it.perItem, it.orderQuantity, consumed, cost, movement.id,
            )
        }
        return ConsumePosRecipesResult(batch, snapshots)
    }

    override fun returnPosConsumption(command: ReturnPosConsumptionCommand): UUID {
        requireTransaction()
        val batch = createBatch(
            command.tenantId, command.propertyId, "pos_return",
            command.returnCommandId, command.actorId,
        )
        command.snapshots.sortedBy { "${it.inventoryItemId}:${it.locationId}" }.forEach {
            appendMovement(
                command.tenantId, command.propertyId, batch, it.inventoryItemId,
                it.locationId, "return", it.consumedQuantity.quantity(), it.unitCost.cost(),
                "pos_void", command.returnCommandId, command.actorId,
            )
        }
        return batch
    }

    private fun stockCommand(
        propertyId: UUID,
        movementType: String,
        request: StockCommandRequest,
        costProvider: (com.mwombeki.peak.inventory.api.StockCommandLine) -> BigDecimal?,
    ) = commands.mutate(
        propertyId, "inventory.$movementType", request, BATCHES,
        InventoryCommandResponse::class.java, { it.batchId }, { it.copy(replayed = true) },
    ) { actor, key ->
        val batch = createBatch(
            actor.tenantId, propertyId, movementType, key, actor.tenantUserId,
        )
        val rows = request.lines.sortedBy { "${it.inventoryItemId}:${it.locationId}" }.map {
            item(actor.tenantId, it.inventoryItemId)
            location(actor.tenantId, propertyId, it.locationId)
            val cost = costProvider(it) ?: lockedAverage(
                actor.tenantId, it.inventoryItemId, it.locationId,
            )
            appendMovement(
                actor.tenantId, propertyId, batch, it.inventoryItemId, it.locationId,
                movementType, it.quantity.quantity(), cost, movementType, key,
                actor.tenantUserId,
            )
        }
        InventoryCommandResponse(batch, rows).also {
            commands.effects(
                actor, propertyId, "inventory.$movementType.posted", BATCHES, batch,
                mapOf("batchId" to batch, "reason" to request.reason), key,
            )
        }
    }

    private fun createBatch(
        tenantId: UUID, propertyId: UUID, type: String, commandId: UUID, actorId: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO inventory_movement_batches (
                id, tenant_id, property_id, command_type, command_id, created_by
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, tenantId, propertyId, type, commandId, actorId,
        )
        return id
    }

    private fun appendMovement(
        tenantId: UUID,
        propertyId: UUID,
        batchId: UUID,
        itemId: UUID,
        locationId: UUID,
        type: String,
        quantity: BigDecimal,
        unitCost: BigDecimal,
        sourceType: String,
        sourceId: UUID,
        actorId: UUID,
        transferId: UUID? = null,
        pairedId: UUID? = null,
        movementId: UUID = UUID.randomUUID(),
    ): StockMovementResponse {
        val movement = jdbc.query(
            """
            INSERT INTO stock_movements (
                id, tenant_id, property_id, batch_id, item_id, location_id,
                type, quantity, unit_cost, total_cost, source_type, source_id,
                created_by, transfer_id, paired_movement_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, round(? * ?, 2), ?, ?, ?, ?, ?)
            RETURNING id, batch_id, item_id, location_id, type, quantity,
                      unit_cost, total_cost, balance_after, average_cost_after,
                      paired_movement_id, created_at
            """.trimIndent(),
            ::mapMovement,
            movementId, tenantId, propertyId, batchId, itemId, locationId,
            type, quantity, unitCost, quantity, unitCost, sourceType, sourceId,
            actorId, transferId, pairedId,
        ).single()
        emitLowStockIfCrossed(tenantId, propertyId, movement)
        return movement
    }

    private fun emitLowStockIfCrossed(
        tenantId: UUID,
        propertyId: UUID,
        movement: StockMovementResponse,
    ) {
        val crossing = jdbc.query(
            """
            SELECT id, quantity, reorder_level
            FROM low_stock_crossings
            WHERE tenant_id = ? AND property_id = ? AND movement_id = ?
            """.trimIndent(),
            { rs, _ ->
                Triple(
                    rs.uuid("id"), rs.getBigDecimal("quantity"),
                    rs.getBigDecimal("reorder_level"),
                )
            },
            tenantId, propertyId, movement.id,
        ).singleOrNull() ?: return
        outbox.enqueue(
            OutboxEventCommand(
                aggregateType = "inventory_items",
                aggregateId = movement.itemId,
                tenantId = tenantId,
                propertyId = propertyId,
                eventType = "inventory.low_stock",
                destination = OutboxDestination.PLATFORM,
                payload = mapOf(
                    "crossingId" to crossing.first, "itemId" to movement.itemId,
                    "locationId" to movement.locationId, "quantity" to crossing.second,
                    "reorderLevel" to crossing.third,
                ),
            ),
        )
    }

    private fun lockLevels(tenantId: UUID, keys: List<Pair<UUID, UUID>>) {
        if (keys.isEmpty()) return
        keys.distinct().sortedBy { "${it.first}:${it.second}" }.forEach {
            jdbc.query(
                """
                SELECT quantity FROM stock_levels
                WHERE tenant_id = ? AND item_id = ? AND location_id = ?
                FOR UPDATE
                """.trimIndent(),
                { rs, _ -> rs.getBigDecimal("quantity") },
                tenantId, it.first, it.second,
            )
        }
    }

    private fun lockedAverage(tenantId: UUID, itemId: UUID, locationId: UUID): BigDecimal =
        jdbc.query(
            """
            SELECT average_cost FROM stock_levels
            WHERE tenant_id = ? AND item_id = ? AND location_id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getBigDecimal("average_cost") },
            tenantId, itemId, locationId,
        ).singleOrNull() ?: throw InventoryConflictException(
            "No stock exists for item $itemId at location $locationId",
        )

    private fun item(tenantId: UUID, id: UUID) = jdbc.query(
        """
        SELECT id, name, sku, category, unit, reorder_level, is_active
        FROM inventory_items WHERE tenant_id = ? AND id = ?
        """.trimIndent(),
        ::mapItem, tenantId, id,
    ).singleOrNull() ?: throw InventoryNotFoundException("Inventory item was not found")

    private fun location(tenantId: UUID, propertyId: UUID, id: UUID) = jdbc.query(
        "$LOCATION_SELECT AND id = ?",
        ::mapLocation, tenantId, propertyId, id,
    ).singleOrNull() ?: throw InventoryNotFoundException("Inventory location was not found")

    private fun requireOutlet(tenantId: UUID, propertyId: UUID, outletId: UUID) {
        val exists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM outlets WHERE tenant_id = ? AND property_id = ? AND id = ? AND deleted_at IS NULL)",
            Boolean::class.java, tenantId, propertyId, outletId,
        ) == true
        if (!exists) throw InventoryNotFoundException("Outlet was not found")
    }

    private fun requireMenuItem(tenantId: UUID, propertyId: UUID, menuItemId: UUID) {
        val exists = jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM menu_items mi
                JOIN menu_categories mc ON mc.tenant_id = mi.tenant_id AND mc.id = mi.category_id
                JOIN outlets o ON o.tenant_id = mc.tenant_id AND o.id = mc.outlet_id
                WHERE mi.tenant_id = ? AND o.property_id = ? AND mi.id = ?
                  AND mi.deleted_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java, tenantId, propertyId, menuItemId,
        ) == true
        if (!exists) throw InventoryNotFoundException("Menu item was not found")
    }

    private fun mapItem(rs: ResultSet, ignored: Int) = InventoryItemResponse(
        rs.uuid("id"), rs.getString("name"), rs.getString("sku"),
        rs.getString("category"), rs.getString("unit"),
        rs.getBigDecimal("reorder_level"), rs.getBoolean("is_active"),
    )

    private fun mapLocation(rs: ResultSet, ignored: Int) = InventoryLocationResponse(
        rs.uuid("id"), rs.uuid("property_id"), rs.getString("name"),
        rs.getString("type"), rs.uuidOrNull("outlet_id"),
    )

    private fun mapMovement(rs: ResultSet, ignored: Int) = StockMovementResponse(
        rs.uuid("id"), rs.uuid("batch_id"), rs.uuid("item_id"), rs.uuid("location_id"),
        rs.getString("type"), rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_cost"),
        rs.getBigDecimal("total_cost"), rs.getBigDecimal("balance_after"),
        rs.getBigDecimal("average_cost_after"), rs.uuidOrNull("paired_movement_id"),
        rs.getTimestamp("created_at").toInstant(),
    )

    private fun BigDecimal.quantity(allowZero: Boolean = false): BigDecimal {
        require(if (allowZero) signum() >= 0 else signum() > 0) {
            "Quantity must be ${if (allowZero) "non-negative" else "positive"}"
        }
        require(stripTrailingZeros().scale() <= 3) { "Quantity supports three decimal places" }
        return setScale(3, RoundingMode.UNNECESSARY)
    }

    private fun BigDecimal.cost(): BigDecimal {
        require(signum() >= 0) { "Cost must be non-negative" }
        require(stripTrailingZeros().scale() <= 6) { "Cost supports six decimal places" }
        return setScale(6, RoundingMode.UNNECESSARY)
    }

    private fun String.locationType(): String = trim().lowercase(Locale.ROOT).also {
        require(it in setOf("store", "kitchen", "bar", "housekeeping", "maintenance")) {
            "Unsupported inventory location type"
        }
    }

    private fun String.required() = trim().takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Value is required")
    private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }
    private fun ResultSet.uuid(column: String) = getObject(column, UUID::class.java)
    private fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)
    private fun requireTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Inventory module commands require an active owning transaction"
        }
    }

    private data class RecipeUse(
        val posOrderItemId: UUID,
        val menuItemId: UUID,
        val inventoryItemId: UUID,
        val locationId: UUID,
        val perItem: BigDecimal,
        val orderQuantity: BigDecimal,
    )

    private companion object {
        const val ITEMS = "inventory_items"
        const val LOCATIONS = "inventory_locations"
        const val RECIPES = "menu_item_recipes"
        const val BATCHES = "inventory_movement_batches"
        const val TRANSFERS = "inventory_transfers"
        const val LOCATION_SELECT = """
            SELECT id, property_id, name, type, outlet_id
            FROM inventory_locations
            WHERE tenant_id = ? AND property_id = ?
        """
        const val MOVEMENT_SELECT = """
            SELECT sm.id, sm.batch_id, sm.item_id, sm.location_id, sm.type,
                   sm.quantity, sm.unit_cost, sm.total_cost, sm.balance_after,
                   sm.average_cost_after, sm.paired_movement_id, sm.created_at
            FROM stock_movements sm
        """
    }
}
