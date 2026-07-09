package com.mwombeki.peak.inventory.api

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface InventoryCloseSnapshotPort {
    fun closeSnapshotSummary(
        tenantId: UUID,
        propertyId: UUID,
        businessDate: LocalDate,
    ): InventoryCloseSnapshotSummary
}

@NamedInterface("api")
data class InventoryCloseSnapshotSummary(
    val lowStockItems: Int,
    val wasteTotal: BigDecimal,
    val inventoryValue: BigDecimal,
)
