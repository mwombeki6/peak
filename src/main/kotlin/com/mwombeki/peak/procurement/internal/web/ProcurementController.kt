package com.mwombeki.peak.procurement.internal.web

import com.mwombeki.peak.procurement.api.CreatePurchaseOrderRequest
import com.mwombeki.peak.procurement.api.CreatePurchaseReceiptRequest
import com.mwombeki.peak.procurement.api.CreateSupplierRequest
import com.mwombeki.peak.procurement.api.ProcurementPort
import com.mwombeki.peak.procurement.api.ProcurementReasonRequest
import com.mwombeki.peak.procurement.api.UpdateSupplierRequest
import com.mwombeki.peak.procurement.internal.ProcurementService
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}")
class ProcurementController(
    private val service: ProcurementService,
    private val port: ProcurementPort,
) {
    @GetMapping("/procurement/suppliers")
    fun suppliers(@PathVariable propertyId: UUID) = service.listSuppliers(propertyId)

    @PostMapping("/procurement/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    fun createSupplier(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: CreateSupplierRequest,
    ) = service.createSupplier(propertyId, request)

    @GetMapping("/procurement/suppliers/{supplierId}")
    fun supplier(@PathVariable propertyId: UUID, @PathVariable supplierId: UUID) =
        service.getSupplier(propertyId, supplierId)

    @PutMapping("/procurement/suppliers/{supplierId}")
    fun updateSupplier(
        @PathVariable propertyId: UUID, @PathVariable supplierId: UUID,
        @Valid @RequestBody request: UpdateSupplierRequest,
    ) = service.updateSupplier(propertyId, supplierId, request)

    @DeleteMapping("/procurement/suppliers/{supplierId}")
    fun deactivateSupplier(
        @PathVariable propertyId: UUID,
        @PathVariable supplierId: UUID,
    ) = service.updateSupplier(
        propertyId, supplierId, UpdateSupplierRequest(active = false),
    )

    @GetMapping("/purchase-orders")
    fun purchaseOrders(@PathVariable propertyId: UUID) = port.listPurchaseOrders(propertyId)

    @PostMapping("/purchase-orders")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPurchaseOrder(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: CreatePurchaseOrderRequest,
    ) = port.createPurchaseOrder(propertyId, request)

    @GetMapping("/purchase-orders/{purchaseOrderId}")
    fun purchaseOrder(
        @PathVariable propertyId: UUID, @PathVariable purchaseOrderId: UUID,
    ) = port.getPurchaseOrder(propertyId, purchaseOrderId)

    @PutMapping("/purchase-orders/{purchaseOrderId}")
    fun updatePurchaseOrder(
        @PathVariable propertyId: UUID,
        @PathVariable purchaseOrderId: UUID,
        @Valid @RequestBody request: CreatePurchaseOrderRequest,
    ) = port.updatePurchaseOrder(propertyId, purchaseOrderId, request)

    @PostMapping("/purchase-orders/{purchaseOrderId}/submit")
    fun submit(@PathVariable propertyId: UUID, @PathVariable purchaseOrderId: UUID) =
        port.transitionPurchaseOrder(propertyId, purchaseOrderId, "submit", null)

    @PostMapping("/purchase-orders/{purchaseOrderId}/approve")
    fun approve(@PathVariable propertyId: UUID, @PathVariable purchaseOrderId: UUID) =
        port.transitionPurchaseOrder(propertyId, purchaseOrderId, "approve", null)

    @PostMapping("/purchase-orders/{purchaseOrderId}/reject")
    fun reject(
        @PathVariable propertyId: UUID, @PathVariable purchaseOrderId: UUID,
        @Valid @RequestBody request: ProcurementReasonRequest,
    ) = port.transitionPurchaseOrder(propertyId, purchaseOrderId, "reject", request)

    @PostMapping("/purchase-orders/{purchaseOrderId}/cancel")
    fun cancel(
        @PathVariable propertyId: UUID, @PathVariable purchaseOrderId: UUID,
        @Valid @RequestBody request: ProcurementReasonRequest,
    ) = port.transitionPurchaseOrder(propertyId, purchaseOrderId, "cancel", request)

    @PostMapping("/purchase-orders/{purchaseOrderId}/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    fun receive(
        @PathVariable propertyId: UUID, @PathVariable purchaseOrderId: UUID,
        @Valid @RequestBody request: CreatePurchaseReceiptRequest,
    ) = port.receivePurchaseOrder(propertyId, purchaseOrderId, request)
}
