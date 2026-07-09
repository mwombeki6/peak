package com.mwombeki.peak.reservations.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface ReservationPort {
    fun createGuestInCurrentTransaction(
        tenantId: UUID,
        propertyId: UUID,
        request: CreateGuestRequest,
    ): GuestResponse

    fun createReservationInCurrentTransaction(
        tenantId: UUID,
        propertyId: UUID,
        request: CreateReservationRequest,
        idempotencyKeyId: UUID,
    ): ReservationMutationReceipt

    fun createGuest(propertyId: UUID, request: CreateGuestRequest): GuestResponse
    fun listGuests(propertyId: UUID): List<GuestResponse>
    fun getGuest(propertyId: UUID, guestId: UUID): GuestResponse?

    fun createReservation(propertyId: UUID, request: CreateReservationRequest): ReservationMutationReceipt
    fun amendReservation(
        propertyId: UUID,
        reservationId: UUID,
        request: AmendReservationRequest,
    ): ReservationMutationReceipt
    fun cancelReservation(
        propertyId: UUID,
        reservationId: UUID,
        request: CancelReservationRequest,
    ): ReservationMutationReceipt
    fun listReservations(propertyId: UUID): List<ReservationResponse>
    fun getReservation(propertyId: UUID, reservationId: UUID): ReservationResponse?
}

@NamedInterface("api")
interface ReservationTransitionPort {
    fun requireCheckInSnapshot(
        tenantId: UUID,
        propertyId: UUID,
        reservationId: UUID,
    ): ReservationCheckInSnapshot

    fun markCheckedIn(
        tenantId: UUID,
        propertyId: UUID,
        reservationId: UUID,
        reservationRoomId: UUID,
        roomId: UUID,
    )

    fun markCheckedOut(
        tenantId: UUID,
        propertyId: UUID,
        reservationId: UUID,
    )

    fun operationalSummary(
        tenantId: UUID,
        propertyId: UUID,
        businessDate: java.time.LocalDate,
    ): ReservationOperationalSummary
}

@NamedInterface("api")
data class ReservationCheckInSnapshot(
    val reservationId: UUID,
    val checkInDate: java.time.LocalDate,
    val checkOutDate: java.time.LocalDate,
    val reservationRoomId: UUID,
    val roomTypeId: UUID,
    val roomId: UUID?,
    val folioId: UUID?,
)

@NamedInterface("api")
data class ReservationOperationalSummary(
    val overdueCheckedInStays: Int,
)

@NamedInterface("api")
data class ReservationCloseSnapshotSummary(
    val roomsSold: Int,
    val occupiedRooms: Int,
    val arrivals: Int,
    val departures: Int,
    val noShows: Int,
    val overdueStays: Int,
)

@NamedInterface("api")
interface ReservationCloseSnapshotPort {
    fun closeSnapshotSummary(
        tenantId: UUID,
        propertyId: UUID,
        businessDate: java.time.LocalDate,
    ): ReservationCloseSnapshotSummary
}
