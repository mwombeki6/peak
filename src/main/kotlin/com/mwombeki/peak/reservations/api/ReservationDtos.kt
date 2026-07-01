package com.mwombeki.peak.reservations.api

import com.mwombeki.peak.shared.exception.BusinessException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.modulith.NamedInterface

data class GuestResponse(
    val id: UUID,
    val tenantId: UUID,
    val fullName: String,
    val firstName: String?,
    val lastName: String?,
    val email: String?,
    val phonePrimary: String?,
    val dateOfBirth: LocalDate?,
    val nationality: String?,
    val vipLevel: String,
    val blacklisted: Boolean,
)

data class CreateGuestRequest(
    val fullName: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val phonePrimary: String? = null,
    val dateOfBirth: LocalDate? = null,
    val nationality: String? = null,
    val notes: String? = null,
)

@NamedInterface("api")
data class CreateReservationRequest(
    val primaryGuestId: UUID,
    val roomTypeId: UUID,
    val roomId: UUID? = null,
    val checkInDate: LocalDate,
    val checkOutDate: LocalDate,
    val adults: Int = 1,
    val children: Int = 0,
    val ratePerNight: BigDecimal,
    val specialRequests: String? = null,
    val internalNotes: String? = null,
)

data class AmendReservationRequest(
    val roomTypeId: UUID? = null,
    val roomId: UUID? = null,
    val checkInDate: LocalDate? = null,
    val checkOutDate: LocalDate? = null,
    val adults: Int? = null,
    val children: Int? = null,
    val ratePerNight: BigDecimal? = null,
    val specialRequests: String? = null,
    val internalNotes: String? = null,
)

data class CancelReservationRequest(
    val reason: String,
    val cancellationFee: BigDecimal = BigDecimal.ZERO,
)

data class ReservationResponse(
    val id: UUID,
    val tenantId: UUID,
    val propertyId: UUID,
    val primaryGuestId: UUID,
    val confirmationNumber: String,
    val status: String,
    val checkInDate: LocalDate,
    val checkOutDate: LocalDate,
    val roomTypeId: UUID,
    val roomId: UUID?,
    val ratePerNight: BigDecimal,
    val totalAmount: BigDecimal,
    val totalPaid: BigDecimal,
    val folioId: UUID?,
)

data class ReservationMutationReceipt(
    val reservationId: UUID,
    val propertyId: UUID,
    val status: String,
    val folioId: UUID?,
    val changed: Boolean,
    val replayed: Boolean,
)

sealed class ReservationException(
    message: String,
    status: HttpStatus,
    code: String,
) : BusinessException(message = message, status = status, errorCode = code)

class ReservationNotFoundException(message: String) : ReservationException(
    message = message,
    status = HttpStatus.NOT_FOUND,
    code = "RESERVATION_NOT_FOUND",
)

class ReservationConflictException(message: String) : ReservationException(
    message = message,
    status = HttpStatus.CONFLICT,
    code = "RESERVATION_CONFLICT",
)

class ReservationInProgressException(message: String) : ReservationException(
    message = message,
    status = HttpStatus.CONFLICT,
    code = "RESERVATION_COMMAND_IN_PROGRESS",
)

class GuestIdentityIncompleteException(message: String) : ReservationException(
    message = message,
    status = HttpStatus.CONFLICT,
    code = "GUEST_IDENTITY_INCOMPLETE",
)
