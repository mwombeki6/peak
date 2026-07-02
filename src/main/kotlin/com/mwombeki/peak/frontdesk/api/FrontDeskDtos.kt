package com.mwombeki.peak.frontdesk.api

import com.mwombeki.peak.reservations.api.CreateReservationRequest
import com.mwombeki.peak.shared.exception.BusinessException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.http.HttpStatus

data class CheckInRequest(
    val reservationId: UUID,
    val roomId: UUID? = null,
)

data class WalkInRequest(
    val primaryGuestId: UUID,
    val reservation: WalkInReservationRequest,
)

data class WalkInReservationRequest(
    val roomTypeId: UUID,
    val roomId: UUID,
    val checkInDate: LocalDate,
    val checkOutDate: LocalDate,
    val adults: Int = 1,
    val children: Int = 0,
    val ratePerNight: BigDecimal,
    val specialRequests: String? = null,
    val internalNotes: String? = null,
) {
    fun toReservationRequest(primaryGuestId: UUID): CreateReservationRequest {
        return CreateReservationRequest(
            primaryGuestId = primaryGuestId,
            roomTypeId = roomTypeId,
            roomId = roomId,
            checkInDate = checkInDate,
            checkOutDate = checkOutDate,
            adults = adults,
            children = children,
            ratePerNight = ratePerNight,
            specialRequests = specialRequests,
            internalNotes = internalNotes,
        )
    }
}

data class CheckoutRequest(
    val reason: String? = null,
)

data class UnpaidCheckoutOverrideRequest(
    val reason: String,
)

data class StayResponse(
    val id: UUID,
    val tenantId: UUID,
    val propertyId: UUID,
    val reservationId: UUID,
    val roomId: UUID,
    val status: String,
    val checkInTime: Instant?,
    val checkOutTime: Instant?,
    val folioId: UUID?,
)

data class FrontDeskMutationReceipt(
    val propertyId: UUID,
    val reservationId: UUID,
    val stayId: UUID?,
    val folioId: UUID?,
    val status: String,
    val changed: Boolean,
    val replayed: Boolean,
)

sealed class FrontDeskException(
    message: String,
    status: HttpStatus,
    code: String,
) : BusinessException(message = message, status = status, errorCode = code)

class FrontDeskNotFoundException(message: String) : FrontDeskException(
    message = message,
    status = HttpStatus.NOT_FOUND,
    code = "FRONTDESK_NOT_FOUND",
)

class FrontDeskConflictException(message: String) : FrontDeskException(
    message = message,
    status = HttpStatus.CONFLICT,
    code = "FRONTDESK_CONFLICT",
)

class FrontDeskInProgressException(message: String) : FrontDeskException(
    message = message,
    status = HttpStatus.CONFLICT,
    code = "FRONTDESK_COMMAND_IN_PROGRESS",
)
