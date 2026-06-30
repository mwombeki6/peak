package com.mwombeki.peak.pos.api

import java.math.BigDecimal
import java.util.UUID

data class OpenSessionRequest(
    val propertyId: UUID,
    val startingFloat: BigDecimal,
)

data class CloseSessionRequest(
    val expectedAmount: BigDecimal,
    val actualAmount: BigDecimal
)

data class PosSessionResponse(
    val sessionId: UUID,
    val status: String,
    val openedBy: String,
    val startingFloat: BigDecimal
)

data class FolioTransferRequest(
    val folioId: UUID,
    val amount: java.math.BigDecimal,
    val description: String
)

data class PosOrderSettlementRequest(
    val paymentMethod: String, // 'CASH' or 'MOBILE_MONEY'
    val amount: BigDecimal,
    val providerReference: String? = null
)

data class ApproveVarianceRequest(
    val supervisorNotes: String
)

data class CreateOrderRequest(
    val sessionId: UUID,
)

data class AddOrderItemRequest(
    val description: String,
    val quantity: Int,
    val unitPrice: BigDecimal
)

data class PosOrderResponse(
    val orderId: UUID,
    val sessionId: UUID,
    val status: String,
    val totalAmount: BigDecimal,
    val items: List<PosOrderItemResponse>
)

data class PosOrderItemResponse(
    val itemId: UUID,
    val description: String,
    val quantity: Int,
    val unitPrice: BigDecimal,
    val totalPrice: BigDecimal
)