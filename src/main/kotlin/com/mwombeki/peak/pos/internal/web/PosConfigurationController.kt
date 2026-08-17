package com.mwombeki.peak.pos.internal.web

import com.mwombeki.peak.pos.api.CreatePosMenuCategoryRequest
import com.mwombeki.peak.pos.api.CreatePosMenuItemRequest
import com.mwombeki.peak.pos.api.CreatePosOutletRequest
import com.mwombeki.peak.pos.api.PosConfigurationResponse
import com.mwombeki.peak.pos.api.PosMenuCategoryResponse
import com.mwombeki.peak.pos.api.PosMenuItemResponse
import com.mwombeki.peak.pos.internal.PosConfigurationService
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/pos-config")
class PosConfigurationController(
    private val service: PosConfigurationService,
) {
    @PostMapping("/outlets")
    @ResponseStatus(HttpStatus.CREATED)
    fun createOutlet(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: CreatePosOutletRequest,
    ): PosConfigurationResponse = service.createOutlet(propertyId, request)

    @PostMapping("/menu-categories")
    @ResponseStatus(HttpStatus.CREATED)
    fun createCategory(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: CreatePosMenuCategoryRequest,
    ): PosConfigurationResponse = service.createCategory(propertyId, request)

    @PostMapping("/menu-items")
    @ResponseStatus(HttpStatus.CREATED)
    fun createMenuItem(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: CreatePosMenuItemRequest,
    ): PosConfigurationResponse = service.createMenuItem(propertyId, request)

    @GetMapping("/menu-categories")
    fun listMenuCategories(
        @PathVariable propertyId: UUID,
        @RequestParam outletId: UUID,
    ): List<PosMenuCategoryResponse> = service.listMenuCategories(propertyId, outletId)

    @GetMapping("/menu-items")
    fun listMenuItems(
        @PathVariable propertyId: UUID,
        @RequestParam outletId: UUID,
    ): List<PosMenuItemResponse> = service.listMenuItems(propertyId, outletId)
}
