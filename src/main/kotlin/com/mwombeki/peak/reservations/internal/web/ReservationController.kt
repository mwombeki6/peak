package com.mwombeki.peak.reservations.internal.web

import com.mwombeki.peak.reservations.api.AmendReservationRequest
import com.mwombeki.peak.reservations.api.AddReservationGuestRequest
import com.mwombeki.peak.reservations.api.CancelReservationRequest
import com.mwombeki.peak.reservations.api.CreateGuestRequest
import com.mwombeki.peak.reservations.api.CreateReservationRequest
import com.mwombeki.peak.reservations.api.GuestIdentityDocumentResponse
import com.mwombeki.peak.reservations.api.GuestIdentityPort
import com.mwombeki.peak.reservations.api.GuestIdentityVerificationReceipt
import com.mwombeki.peak.reservations.api.GuestResponse
import com.mwombeki.peak.reservations.api.ManualGuestIdentityVerificationRequest
import com.mwombeki.peak.reservations.api.ReservationGuestResponse
import com.mwombeki.peak.reservations.api.ReservationIdentityReadinessResponse
import com.mwombeki.peak.reservations.api.ReservationMutationReceipt
import com.mwombeki.peak.reservations.api.ReservationNotFoundException
import com.mwombeki.peak.reservations.api.ReservationPort
import com.mwombeki.peak.reservations.api.ReservationResponse
import com.mwombeki.peak.reservations.api.RevokeGuestIdentityRequest
import com.mwombeki.peak.reservations.api.UpdateGuestRequest
import com.mwombeki.peak.reservations.api.VerifyGuestIdentityRequest
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}")
class ReservationController(
    private val reservationPort: ReservationPort,
    private val guestIdentityPort: GuestIdentityPort,
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

    @PatchMapping("/guests/{guestId}")
    fun updateGuest(
        @PathVariable propertyId: UUID,
        @PathVariable guestId: UUID,
        @RequestBody request: UpdateGuestRequest,
    ): GuestResponse {
        return guestIdentityPort.updateGuest(propertyId, guestId, request)
    }

    @GetMapping("/guests/{guestId}/identity-documents")
    fun listGuestIdentityDocuments(
        @PathVariable propertyId: UUID,
        @PathVariable guestId: UUID,
    ): List<GuestIdentityDocumentResponse> {
        return guestIdentityPort.listDocuments(propertyId, guestId)
    }

    @PostMapping("/guests/{guestId}/identity-documents/verify")
    fun verifyGuestIdentity(
        @PathVariable propertyId: UUID,
        @PathVariable guestId: UUID,
        @RequestBody request: VerifyGuestIdentityRequest,
    ): GuestIdentityVerificationReceipt {
        return guestIdentityPort.verifyIdentity(propertyId, guestId, request)
    }

    @PostMapping("/guests/{guestId}/identity-documents/manual-verification")
    fun manuallyVerifyGuestIdentity(
        @PathVariable propertyId: UUID,
        @PathVariable guestId: UUID,
        @RequestBody request: ManualGuestIdentityVerificationRequest,
    ): GuestIdentityVerificationReceipt {
        return guestIdentityPort.manuallyVerifyIdentity(propertyId, guestId, request)
    }

    @PostMapping("/guests/{guestId}/identity-documents/{documentId}/revoke")
    fun revokeGuestIdentity(
        @PathVariable propertyId: UUID,
        @PathVariable guestId: UUID,
        @PathVariable documentId: UUID,
        @RequestBody request: RevokeGuestIdentityRequest,
    ): GuestIdentityDocumentResponse {
        return guestIdentityPort.revokeIdentity(propertyId, guestId, documentId, request)
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

    @PostMapping("/reservations/{reservationId}/guests")
    fun addReservationGuest(
        @PathVariable propertyId: UUID,
        @PathVariable reservationId: UUID,
        @RequestBody request: AddReservationGuestRequest,
    ): ReservationGuestResponse {
        return guestIdentityPort.addReservationGuest(propertyId, reservationId, request)
    }

    @GetMapping("/reservations/{reservationId}/guests")
    fun listReservationGuests(
        @PathVariable propertyId: UUID,
        @PathVariable reservationId: UUID,
    ): List<ReservationGuestResponse> {
        return guestIdentityPort.listReservationGuests(propertyId, reservationId)
    }

    @DeleteMapping("/reservations/{reservationId}/guests/{guestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeReservationGuest(
        @PathVariable propertyId: UUID,
        @PathVariable reservationId: UUID,
        @PathVariable guestId: UUID,
    ) {
        guestIdentityPort.removeReservationGuest(propertyId, reservationId, guestId)
    }

    @GetMapping("/reservations/{reservationId}/identity-readiness")
    fun reservationIdentityReadiness(
        @PathVariable propertyId: UUID,
        @PathVariable reservationId: UUID,
    ): ReservationIdentityReadinessResponse {
        return guestIdentityPort.identityReadiness(propertyId, reservationId)
    }
}
