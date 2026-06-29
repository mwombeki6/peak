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
