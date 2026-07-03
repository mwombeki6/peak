package com.mwombeki.peak.inventory.internal.web

import com.mwombeki.peak.inventory.api.CreateInventoryItemRequest
import com.mwombeki.peak.inventory.api.CreateInventoryLocationRequest
import com.mwombeki.peak.inventory.api.StockAdjustmentRequest
import com.mwombeki.peak.inventory.api.StockCommandRequest
import com.mwombeki.peak.inventory.api.TransferStockRequest
import com.mwombeki.peak.inventory.api.UpdateInventoryItemRequest
import com.mwombeki.peak.inventory.api.UpdateInventoryLocationRequest
import com.mwombeki.peak.inventory.api.UpsertRecipeRequest
import com.mwombeki.peak.inventory.internal.InventoryService
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/inventory")
class InventoryController(private val service: InventoryService) {
    @GetMapping("/items")
    fun items(@PathVariable propertyId: UUID) = service.listItems(propertyId)

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    fun createItem(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: CreateInventoryItemRequest,
    ) = service.createItem(propertyId, request)

    @GetMapping("/items/{itemId}")
    fun item(@PathVariable propertyId: UUID, @PathVariable itemId: UUID) =
        service.getItem(propertyId, itemId)

    @PutMapping("/items/{itemId}")
    fun updateItem(
        @PathVariable propertyId: UUID, @PathVariable itemId: UUID,
        @Valid @RequestBody request: UpdateInventoryItemRequest,
    ) = service.updateItem(propertyId, itemId, request)

    @DeleteMapping("/items/{itemId}")
    fun deactivateItem(@PathVariable propertyId: UUID, @PathVariable itemId: UUID) =
        service.deactivateItem(propertyId, itemId)

    @GetMapping("/locations")
    fun locations(@PathVariable propertyId: UUID) = service.listLocations(propertyId)

    @PostMapping("/locations")
    @ResponseStatus(HttpStatus.CREATED)
    fun createLocation(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: CreateInventoryLocationRequest,
    ) = service.createLocation(propertyId, request)

    @GetMapping("/locations/{locationId}")
    fun location(@PathVariable propertyId: UUID, @PathVariable locationId: UUID) =
        service.getLocation(propertyId, locationId)

    @PutMapping("/locations/{locationId}")
    fun updateLocation(
        @PathVariable propertyId: UUID, @PathVariable locationId: UUID,
        @Valid @RequestBody request: UpdateInventoryLocationRequest,
    ) = service.updateLocation(propertyId, locationId, request)

    @DeleteMapping("/locations/{locationId}")
    fun deleteLocation(
        @PathVariable propertyId: UUID,
        @PathVariable locationId: UUID,
    ) = service.deleteLocation(propertyId, locationId)

    @GetMapping("/recipes")
    fun recipes(@PathVariable propertyId: UUID) = service.listRecipes(propertyId)

    @PutMapping("/recipes")
    fun upsertRecipe(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: UpsertRecipeRequest,
    ) = service.upsertRecipe(propertyId, request)

    @DeleteMapping("/recipes/{menuItemId}")
    fun deleteRecipe(
        @PathVariable propertyId: UUID,
        @PathVariable menuItemId: UUID,
    ) = service.deleteRecipe(propertyId, menuItemId)

    @GetMapping("/levels")
    fun levels(@PathVariable propertyId: UUID) = service.levels(propertyId)

    @GetMapping("/movements")
    fun movements(@PathVariable propertyId: UUID) = service.movements(propertyId)

    @PostMapping("/opening-balances")
    fun openingBalances(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: StockCommandRequest,
    ) = service.openingBalances(propertyId, request)

    @PostMapping("/adjustments")
    fun adjustments(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: StockAdjustmentRequest,
    ) = service.adjust(propertyId, request)

    @PostMapping("/waste")
    fun waste(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: StockCommandRequest,
    ) = service.waste(propertyId, request)

    @PostMapping("/transfers")
    fun transfer(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: TransferStockRequest,
    ) = service.transfer(propertyId, request)
}
