package com.mwombeki.peak.reservations.internal.web

import com.mwombeki.peak.reservations.api.AmendReservationRequest
import com.mwombeki.peak.reservations.api.CancelReservationRequest
import com.mwombeki.peak.reservations.api.CreateGuestRequest
import com.mwombeki.peak.reservations.api.CreateReservationRequest
import com.mwombeki.peak.reservations.api.GuestResponse
import com.mwombeki.peak.reservations.api.ReservationMutationReceipt
import com.mwombeki.peak.reservations.api.ReservationNotFoundException
import com.mwombeki.peak.reservations.api.ReservationPort
import com.mwombeki.peak.reservations.api.ReservationResponse
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}")
class ReservationController(
    private val reservationPort: ReservationPort,
) {
    @PostMapping("/guests")
    fun createGuest(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateGuestRequest,
    ): GuestResponse {
        return reservationPort.createGuest(propertyId, request)
    }

    @GetMapping("/guests")
    fun listGuests(@PathVariable propertyId: UUID): List<GuestResponse> {
        return reservationPort.listGuests(propertyId)
    }

    @GetMapping("/guests/{guestId}")
    fun getGuest(
        @PathVariable propertyId: UUID,
        @PathVariable guestId: UUID,
    ): GuestResponse {
        return reservationPort.getGuest(propertyId, guestId)
            ?: throw ReservationNotFoundException("Guest was not found")
    }

    @PostMapping("/reservations")
    fun createReservation(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateReservationRequest,
    ): ReservationMutationReceipt {
        return reservationPort.createReservation(propertyId, request)
    }

    @GetMapping("/reservations")
    fun listReservations(@PathVariable propertyId: UUID): List<ReservationResponse> {
        return reservationPort.listReservations(propertyId)
    }

    @GetMapping("/reservations/{reservationId}")
    fun getReservation(
        @PathVariable propertyId: UUID,
        @PathVariable reservationId: UUID,
    ): ReservationResponse {
        return reservationPort.getReservation(propertyId, reservationId)
            ?: throw ReservationNotFoundException("Reservation was not found")
    }

    @PatchMapping("/reservations/{reservationId}")
    fun amendReservation(
        @PathVariable propertyId: UUID,
        @PathVariable reservationId: UUID,
        @RequestBody request: AmendReservationRequest,
    ): ReservationMutationReceipt {
        return reservationPort.amendReservation(propertyId, reservationId, request)
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    fun cancelReservation(
        @PathVariable propertyId: UUID,
        @PathVariable reservationId: UUID,
        @RequestBody request: CancelReservationRequest,
    ): ReservationMutationReceipt {
        return reservationPort.cancelReservation(propertyId, reservationId, request)
    }
}
