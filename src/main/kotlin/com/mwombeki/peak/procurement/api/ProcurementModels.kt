package com.mwombeki.peak.procurement.api

import com.mwombeki.peak.shared.exception.BusinessException
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.http.HttpStatus

enum class PurchaseOrderStatus {
    DRAFT, SUBMITTED, APPROVED, REJECTED, PARTIALLY_RECEIVED, RECEIVED, CANCELLED,
}

data class CreateSupplierRequest(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:Size(max = 50) val code: String? = null,
    @field:Size(max = 200) val contactName: String? = null,
    @field:Email @field:Size(max = 254) val contactEmail: String? = null,
    @field:Size(max = 20) val contactPhone: String? = null,
)

data class UpdateSupplierRequest(
    @field:Size(max = 200) val name: String? = null,
    @field:Size(max = 50) val code: String? = null,
    @field:Email @field:Size(max = 254) val contactEmail: String? = null,
    @field:Size(max = 20) val contactPhone: String? = null,
    val active: Boolean? = null,
)

data class SupplierResponse(
    val id: UUID,
    val name: String,
    val code: String?,
    val contactName: String?,
    val contactEmail: String?,
    val contactPhone: String?,
    val active: Boolean,
    val replayed: Boolean = false,
)

data class PurchaseOrderLineRequest(
    @field:NotNull val inventoryItemId: UUID,
    @field:DecimalMin("0.001") val quantity: BigDecimal,
    @field:DecimalMin("0.00") val unitPrice: BigDecimal,
)

data class CreatePurchaseOrderRequest(
    @field:NotNull val supplierId: UUID,
    val expectedDeliveryDate: LocalDate? = null,
    @field:NotEmpty val lines: List<@Valid PurchaseOrderLineRequest>,
)

data class PurchaseOrderLineResponse(
    val id: UUID,
    val inventoryItemId: UUID,
    val quantity: BigDecimal,
    val receivedQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val unitPrice: BigDecimal,
    val totalPrice: BigDecimal,
)

data class PurchaseOrderResponse(
    val id: UUID,
    val propertyId: UUID,
    val orderNumber: String,
    val supplierId: UUID,
    val currency: String,
    val totalAmount: BigDecimal,
    val status: PurchaseOrderStatus,
    val createdBy: UUID?,
    val approvedBy: UUID?,
    val lines: List<PurchaseOrderLineResponse>,
    val replayed: Boolean = false,
)

data class ProcurementReasonRequest(
    @field:NotBlank @field:Size(min = 3, max = 1000) val reason: String,
)

data class PurchaseReceiptLineRequest(
    @field:NotNull val purchaseOrderItemId: UUID,
    @field:NotNull val locationId: UUID,
    @field:DecimalMin("0.001") val quantity: BigDecimal,
)

data class CreatePurchaseReceiptRequest(
    @field:Size(max = 100) val supplierReference: String? = null,
    @field:NotEmpty val lines: List<@Valid PurchaseReceiptLineRequest>,
)

data class PurchaseReceiptLineResponse(
    val purchaseOrderItemId: UUID,
    val inventoryItemId: UUID,
    val locationId: UUID,
    val quantity: BigDecimal,
    val unitCost: BigDecimal,
    val stockMovementId: UUID,
)

data class PurchaseReceiptResponse(
    val id: UUID,
    val propertyId: UUID,
    val purchaseOrderId: UUID,
    val receiptNumber: String,
    val currency: String,
    val totalAmount: BigDecimal,
    val receivedBy: UUID,
    val receivedAt: Instant,
    val lines: List<PurchaseReceiptLineResponse>,
    val replayed: Boolean = false,
)

open class ProcurementException(
    message: String, status: HttpStatus, code: String,
) : BusinessException(message, status, code)
class ProcurementNotFoundException(message: String) :
    ProcurementException(message, HttpStatus.NOT_FOUND, "PROCUREMENT_NOT_FOUND")
class ProcurementConflictException(message: String) :
    ProcurementException(message, HttpStatus.CONFLICT, "PROCUREMENT_CONFLICT")
