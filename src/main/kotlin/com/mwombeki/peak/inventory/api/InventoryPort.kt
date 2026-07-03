package com.mwombeki.peak.inventory.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface InventoryPort {
    fun receiveStock(command: ReceiveStockCommand): ReceiveStockResult
    fun consumePosRecipes(command: ConsumePosRecipesCommand): ConsumePosRecipesResult
    fun returnPosConsumption(command: ReturnPosConsumptionCommand): UUID
}
