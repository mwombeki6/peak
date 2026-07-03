package com.mwombeki.peak.pos.internal.web

import com.mwombeki.peak.pos.api.AddPosOrderItemRequest
import com.mwombeki.peak.pos.api.CreatePosOrderRequest
import com.mwombeki.peak.pos.api.PosOrderResponse
import com.mwombeki.peak.pos.api.SettlePosOrderRequest
import com.mwombeki.peak.pos.api.SendPosOrderRequest
import com.mwombeki.peak.pos.api.VoidPosOrderItemRequest
import com.mwombeki.peak.pos.internal.PosKitchenService
import com.mwombeki.peak.pos.internal.PosOrderService
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/pos-orders")
class PosOrderController(
    private val posOrderService: PosOrderService,
    private val posKitchenService: PosKitchenService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createOrder(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: CreatePosOrderRequest,
    ): PosOrderResponse = posOrderService.createOrder(propertyId, request)

    @PostMapping("/{orderId}/items")
    fun addItem(
        @PathVariable propertyId: UUID,
        @PathVariable orderId: UUID,
        @Valid @RequestBody request: AddPosOrderItemRequest,
    ): PosOrderResponse = posOrderService.addItem(propertyId, orderId, request)

    @GetMapping("/{orderId}")
    fun getOrder(
        @PathVariable propertyId: UUID,
        @PathVariable orderId: UUID,
    ): PosOrderResponse = posOrderService.getOrder(propertyId, orderId)

    @PostMapping("/{orderId}/settle")
    fun settleOrder(
        @PathVariable propertyId: UUID,
        @PathVariable orderId: UUID,
        @Valid @RequestBody request: SettlePosOrderRequest,
    ): PosOrderResponse = posOrderService.settleOrder(propertyId, orderId, request)

    @PostMapping("/{orderId}/send")
    fun send(
        @PathVariable propertyId: UUID,
        @PathVariable orderId: UUID,
        @Valid @RequestBody request: SendPosOrderRequest,
    ) = posKitchenService.send(propertyId, orderId, request)

    @PostMapping("/{orderId}/items/{itemId}/void")
    fun voidItem(
        @PathVariable propertyId: UUID,
        @PathVariable orderId: UUID,
        @PathVariable itemId: UUID,
        @Valid @RequestBody request: VoidPosOrderItemRequest,
    ) = posKitchenService.voidItem(propertyId, orderId, itemId, request)
}
