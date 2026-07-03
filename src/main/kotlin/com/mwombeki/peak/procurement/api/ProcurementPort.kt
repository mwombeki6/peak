package com.mwombeki.peak.procurement.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface ProcurementPort {
    fun listPurchaseOrders(propertyId: UUID): List<PurchaseOrderResponse>
    fun getPurchaseOrder(propertyId: UUID, id: UUID): PurchaseOrderResponse
    fun createPurchaseOrder(
        propertyId: UUID,
        request: CreatePurchaseOrderRequest,
    ): PurchaseOrderResponse
    fun updatePurchaseOrder(
        propertyId: UUID,
        id: UUID,
        request: CreatePurchaseOrderRequest,
    ): PurchaseOrderResponse
    fun transitionPurchaseOrder(
        propertyId: UUID,
        id: UUID,
        action: String,
        reason: ProcurementReasonRequest?,
    ): PurchaseOrderResponse
    fun receivePurchaseOrder(
        propertyId: UUID,
        id: UUID,
        request: CreatePurchaseReceiptRequest,
    ): PurchaseReceiptResponse
}
