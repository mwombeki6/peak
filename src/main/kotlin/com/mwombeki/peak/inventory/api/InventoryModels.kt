package com.mwombeki.peak.inventory.api

import com.mwombeki.peak.shared.exception.BusinessException
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus

data class CreateInventoryItemRequest(
    @field:NotBlank @field:Size(max = 160) val name: String,
    @field:Size(max = 50) val sku: String? = null,
    @field:Size(max = 100) val category: String? = null,
    @field:NotBlank @field:Size(max = 20) val unit: String,
    @field:DecimalMin("0.000") val reorderLevel: BigDecimal = BigDecimal.ZERO,
)

data class UpdateInventoryItemRequest(
    @field:Size(max = 160) val name: String? = null,
    @field:Size(max = 50) val sku: String? = null,
    @field:Size(max = 100) val category: String? = null,
    @field:DecimalMin("0.000") val reorderLevel: BigDecimal? = null,
    val active: Boolean? = null,
)

data class InventoryItemResponse(
    val id: UUID,
    val name: String,
    val sku: String?,
    val category: String?,
    val unit: String,
    val reorderLevel: BigDecimal,
    val active: Boolean,
    val replayed: Boolean = false,
)

data class CreateInventoryLocationRequest(
    @field:NotBlank @field:Size(max = 160) val name: String,
    @field:NotBlank val type: String,
    val outletId: UUID? = null,
)

data class UpdateInventoryLocationRequest(
    @field:Size(max = 160) val name: String? = null,
    val type: String? = null,
    val outletId: UUID? = null,
)

data class InventoryLocationResponse(
    val id: UUID,
    val propertyId: UUID,
    val name: String,
    val type: String,
    val outletId: UUID?,
    val replayed: Boolean = false,
)

data class RecipeComponentRequest(
    @field:NotNull val inventoryItemId: UUID,
    @field:NotNull val locationId: UUID,
    @field:DecimalMin("0.001") val quantity: BigDecimal,
    @field:NotBlank @field:Size(max = 20) val unit: String,
)

data class UpsertRecipeRequest(
    @field:NotNull val menuItemId: UUID,
    @field:NotEmpty val components: List<@Valid RecipeComponentRequest>,
)

data class RecipeComponentResponse(
    val inventoryItemId: UUID,
    val locationId: UUID,
    val quantity: BigDecimal,
    val unit: String,
)

data class RecipeResponse(
    val menuItemId: UUID,
    val components: List<RecipeComponentResponse>,
    val replayed: Boolean = false,
)

data class StockCommandLine(
    @field:NotNull val inventoryItemId: UUID,
    @field:NotNull val locationId: UUID,
    @field:DecimalMin("0.001") val quantity: BigDecimal,
    @field:DecimalMin("0.000000") val unitCost: BigDecimal? = null,
)

data class StockCommandRequest(
    @field:NotEmpty val lines: List<@Valid StockCommandLine>,
    @field:Size(max = 500) val reason: String? = null,
)

data class StockAdjustmentLine(
    @field:NotNull val inventoryItemId: UUID,
    @field:NotNull val locationId: UUID,
    val quantityDelta: BigDecimal,
    @field:DecimalMin("0.000000") val unitCost: BigDecimal? = null,
)

data class StockAdjustmentRequest(
    @field:NotEmpty val lines: List<@Valid StockAdjustmentLine>,
    @field:NotBlank @field:Size(max = 500) val reason: String,
)

data class TransferStockRequest(
    @field:NotNull val sourceLocationId: UUID,
    @field:NotNull val destinationLocationId: UUID,
    @field:NotEmpty val lines: List<@Valid TransferLine>,
)

data class TransferLine(
    @field:NotNull val inventoryItemId: UUID,
    @field:DecimalMin("0.001") val quantity: BigDecimal,
)

data class InventoryLevelResponse(
    val itemId: UUID,
    val itemName: String,
    val locationId: UUID,
    val locationName: String,
    val quantity: BigDecimal,
    val reorderLevel: BigDecimal,
    val averageCost: BigDecimal,
    val stockValue: BigDecimal,
)

data class StockMovementResponse(
    val id: UUID,
    val batchId: UUID,
    val itemId: UUID,
    val locationId: UUID,
    val type: String,
    val quantity: BigDecimal,
    val unitCost: BigDecimal,
    val totalCost: BigDecimal,
    val balanceAfter: BigDecimal,
    val averageCostAfter: BigDecimal,
    val pairedMovementId: UUID?,
    val createdAt: Instant,
)

data class InventoryCommandResponse(
    val batchId: UUID,
    val movements: List<StockMovementResponse>,
    val replayed: Boolean = false,
)

data class ReceiveStockLine(
    val purchaseOrderItemId: UUID,
    val inventoryItemId: UUID,
    val locationId: UUID,
    val quantity: BigDecimal,
    val unitCost: BigDecimal,
)

data class ReceiveStockCommand(
    val tenantId: UUID,
    val propertyId: UUID,
    val receiptId: UUID,
    val receivedBy: UUID,
    val lines: List<ReceiveStockLine>,
)

data class ReceiveStockResult(
    val batchId: UUID,
    val movementIdsByPurchaseOrderItem: Map<UUID, UUID>,
)

data class PosRecipeLine(
    val posOrderItemId: UUID,
    val menuItemId: UUID,
    val quantity: BigDecimal,
)

data class ConsumePosRecipesCommand(
    val tenantId: UUID,
    val propertyId: UUID,
    val kitchenTicketId: UUID,
    val actorId: UUID,
    val lines: List<PosRecipeLine>,
)

data class PosConsumptionSnapshot(
    val posOrderItemId: UUID,
    val menuItemId: UUID,
    val inventoryItemId: UUID,
    val locationId: UUID,
    val quantityPerItem: BigDecimal,
    val orderItemQuantity: BigDecimal,
    val consumedQuantity: BigDecimal,
    val unitCost: BigDecimal,
    val stockMovementId: UUID,
)

data class ConsumePosRecipesResult(
    val batchId: UUID,
    val snapshots: List<PosConsumptionSnapshot>,
)

data class ReturnPosConsumptionCommand(
    val tenantId: UUID,
    val propertyId: UUID,
    val returnCommandId: UUID,
    val actorId: UUID,
    val snapshots: List<PosConsumptionSnapshot>,
)

open class InventoryException(
    message: String, status: HttpStatus, code: String,
) : BusinessException(message, status, code)
class InventoryNotFoundException(message: String) :
    InventoryException(message, HttpStatus.NOT_FOUND, "INVENTORY_NOT_FOUND")
class InventoryConflictException(message: String) :
    InventoryException(message, HttpStatus.CONFLICT, "INVENTORY_CONFLICT")
