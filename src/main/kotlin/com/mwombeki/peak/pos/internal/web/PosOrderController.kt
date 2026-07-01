package com.mwombeki.peak.pos.internal.web

import com.mwombeki.peak.pos.api.*
import com.mwombeki.peak.pos.internal.PosOrderService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/pos-orders")
class PosOrderController(
    private val posOrderService: PosOrderService
) {
    @PostMapping
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER', 'ROLE_CASHIER')")
    fun createOrder(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateOrderRequest
    ): ResponseEntity<Map<String, UUID>> {
        val orderId = posOrderService.createOrder(propertyId, request)
        return ResponseEntity.ok(mapOf("orderId" to orderId))
    }

    @PostMapping("/{orderId}/items")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER', 'ROLE_CASHIER')")
    fun addItem(
        @PathVariable propertyId: UUID,
        @PathVariable orderId: UUID,
        @RequestBody request: AddOrderItemRequest
    ): ResponseEntity<Map<String, String>> {
        posOrderService.addItemToOrder(orderId, request)
        return ResponseEntity.ok(mapOf("status" to "Item added successfully."))
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER', 'ROLE_CASHIER')")
    fun getOrder(
        @PathVariable propertyId: UUID,
        @PathVariable orderId: UUID
    ): ResponseEntity<PosOrderResponse> {
        return ResponseEntity.ok(posOrderService.getOrder(orderId))
    }

    @PostMapping("/{orderId}/settle")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER', 'ROLE_CASHIER')")
    fun settleOrder(
        @PathVariable propertyId: UUID,
        @PathVariable orderId: UUID,
        @RequestBody request: PosOrderSettlementRequest
    ): ResponseEntity<Map<String, String>> {
        posOrderService.settleOrder(orderId, request)
        return ResponseEntity.ok(mapOf("status" to "Order settled successfully."))
    }
}
