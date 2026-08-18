package com.mwombeki.peak.pos.api

import com.mwombeki.peak.shared.exception.BusinessException
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus

data class OpenPosSessionRequest(
    @field:NotNull
    val outletId: UUID,
    @field:DecimalMin("0.00")
    val openingFloat: BigDecimal = BigDecimal.ZERO,
    @field:Size(max = 500)
    val notes: String? = null,
)

data class ClosePosSessionRequest(
    @field:DecimalMin("0.00")
    val actualCash: BigDecimal,
    @field:Size(max = 500)
    val notes: String? = null,
)

data class ApprovePosVarianceRequest(
    @field:NotBlank
    @field:Size(min = 10, max = 500)
    val reason: String,
)

data class PosSessionResponse(
    val id: UUID,
    val propertyId: UUID,
    val outletId: UUID,
    val cashierId: UUID,
    val status: String,
    val openingFloat: BigDecimal,
    val expectedCash: BigDecimal,
    val closingCash: BigDecimal?,
    val variance: BigDecimal?,
    val openedAt: Instant,
    val closedAt: Instant?,
    val closedBy: UUID?,
    val varianceApprovedBy: UUID?,
    val replayed: Boolean = false,
)

data class PosSessionSummaryResponse(
    val session: PosSessionResponse,
    val orderCount: Long,
    val closedOrderCount: Long,
    val grossSales: BigDecimal,
)

data class CreatePosOrderRequest(
    @field:NotNull
    val sessionId: UUID,
    @field:NotBlank
    val orderType: String = "dine_in",
    @field:Size(max = 20)
    val tableNumber: String? = null,
    @field:NotBlank
    @field:Size(max = 100)
    val clientOperationId: String = "",
)

data class AddPosOrderItemRequest(
    @field:NotNull
    val menuItemId: UUID,
    @field:DecimalMin(value = "0.001")
    val quantity: BigDecimal = BigDecimal.ONE,
    @field:Size(max = 20)
    val modifiers: List<@Size(max = 100) String> = emptyList(),
    @field:Size(max = 500)
    val specialRequest: String? = null,
    @field:NotBlank
    @field:Size(max = 100)
    val clientOperationId: String = "",
)

data class SendPosOrderRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val clientOperationId: String,
)

enum class PosVoidDisposition {
    RETURN_TO_STOCK,
    WASTE,
}

data class VoidPosOrderItemRequest(
    val disposition: PosVoidDisposition? = null,
    @field:NotBlank
    @field:Size(min = 3, max = 500)
    val reason: String,
)

data class PosItemVoidResponse(
    val orderId: UUID,
    val itemId: UUID,
    val disposition: String,
    val returnBatchId: UUID?,
    val replayed: Boolean = false,
)

enum class KitchenTicketStatus {
    PENDING,
    PREPARING,
    READY,
    DELIVERED,
    VOIDED,
}

data class KitchenTicketItemResponse(
    val id: UUID,
    val posOrderItemId: UUID,
    val itemName: String,
    val quantity: BigDecimal,
    val modifiers: List<String>,
    val specialRequest: String?,
)

data class KitchenTicketResponse(
    val id: UUID,
    val propertyId: UUID,
    val orderId: UUID,
    val outletId: UUID,
    val ticketNumber: String,
    val status: KitchenTicketStatus,
    val sentAt: Instant,
    val readyAt: Instant?,
    val deliveredAt: Instant?,
    val items: List<KitchenTicketItemResponse>,
    val replayed: Boolean = false,
)

data class KitchenTicketReasonRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 500)
    val reason: String,
)

data class SettlePosOrderRequest(
    @field:NotBlank
    val paymentMethod: String,
    val folioId: UUID? = null,
    /**
     * In-house stay selected from [PosRoomChargeCandidateResponse]. Peak resolves the folio
     * server-side so a PIN till never needs a folio UUID.
     *
     * Re-validated at post time: the stay must still be in-house, the room still assigned,
     * and the folio still open. A candidate found before checkout must fail after it.
     */
    val stayId: UUID? = null,
    /**
     * The room the guest named, required for a room charge.
     *
     * A folio id on its own proves nothing about who is being charged, and a waiter never sees
     * one — they hear "put it on 204" across a restaurant. This is the input a POS client
     * actually has, and it is what Peak verifies against the folio's checked-in stay.
     */
    @field:Size(max = 20)
    val roomNumber: String? = null,
    val providerAccountId: UUID? = null,
    @field:Size(max = 20)
    val phoneNumber: String? = null,
)

/**
 * Minimal in-house match for posting F&B to a guest folio from a PIN till.
 *
 * Deliberately excludes folio, reservation, passport, phone, and email. Search is not a
 * substitute for GET /rooms or GET /reservations.
 */
data class PosRoomChargeCandidateResponse(
    val stayId: UUID,
    val roomId: UUID,
    val roomNumber: String,
    val guestDisplayName: String,
    val postingEligible: Boolean,
)

data class PosOrderResponse(
    val id: UUID,
    val propertyId: UUID,
    val outletId: UUID,
    val sessionId: UUID,
    val orderNumber: String,
    val orderType: String,
    val tableNumber: String?,
    val status: String,
    val settlementStatus: String,
    val settlementMethod: String?,
    val folioId: UUID?,
    val paymentTransactionId: UUID?,
    val subtotal: BigDecimal,
    val taxAmount: BigDecimal,
    val totalAmount: BigDecimal,
    val createdAt: Instant,
    val settledAt: Instant?,
    val servedBy: UUID? = null,
    val items: List<PosOrderItemResponse>,
    val replayed: Boolean = false,
)

data class PosOrderItemResponse(
    val id: UUID,
    val menuItemId: UUID,
    val name: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val subtotal: BigDecimal,
    val taxAmount: BigDecimal,
    val totalPrice: BigDecimal,
    val modifiers: List<String>,
    val specialRequest: String?,
    val serviceState: String = "UNSENT",
    val voidDisposition: String? = null,
)

data class CreatePosOutletRequest(
    @field:NotNull
    val revenueCenterId: UUID,
    @field:NotBlank
    @field:Size(max = 120)
    val name: String,
    @field:NotBlank
    val type: String = "RESTAURANT",
)

data class CreatePosMenuCategoryRequest(
    @field:NotNull
    val outletId: UUID,
    @field:NotBlank
    @field:Size(max = 120)
    val name: String,
)

data class CreatePosMenuItemRequest(
    @field:NotNull
    val categoryId: UUID,
    @field:NotNull
    val taxRateId: UUID,
    @field:NotBlank
    @field:Size(max = 160)
    val name: String,
    @field:DecimalMin(value = "0.01")
    val price: BigDecimal,
)

data class PosOutletResponse(
    val id: UUID,
    val propertyId: UUID,
    val revenueCenterId: UUID?,
    val name: String,
    val type: String,
    val isActive: Boolean,
)

data class PosMenuCategoryResponse(
    val id: UUID,
    val outletId: UUID,
    val name: String,
)

data class PosMenuItemResponse(
    val id: UUID,
    val categoryId: UUID,
    val name: String,
    val price: BigDecimal,
    val taxRateId: UUID?,
    val isAvailable: Boolean,
)

data class PosConfigurationResponse(
    val id: UUID,
    val propertyId: UUID,
    val resourceType: String,
    val replayed: Boolean = false,
)

open class PosException(
    message: String,
    status: HttpStatus,
    code: String,
) : BusinessException(message, status, code)

class PosNotFoundException(message: String) :
    PosException(message, HttpStatus.NOT_FOUND, "POS_NOT_FOUND")

class PosConflictException(message: String) :
    PosException(message, HttpStatus.CONFLICT, "POS_CONFLICT")

/**
 * A tenant whose business verification isn't complete has no backend enforcement anywhere else
 * against operating hospitality commands — tenant_allows_operational_writes() only checks
 * lifecycle status (trial/active), never business verification, and every other operationally
 * guarded table intentionally stays that way (see PosCommandExecutor.requireTenantBusinessVerified
 * for why this check is POS-scoped rather than a change to that shared function). A tenant
 * created through the direct platform-registration path (POST /api/v1/platform/tenants, which
 * never runs the applicant->KYB->approval gate) can otherwise reach POS with zero verification.
 */
class PosTenantNotVerifiedException(message: String) :
    PosException(message, HttpStatus.FORBIDDEN, "POS_TENANT_NOT_VERIFIED")
