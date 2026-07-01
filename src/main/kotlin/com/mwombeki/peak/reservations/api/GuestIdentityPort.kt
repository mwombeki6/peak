package com.mwombeki.peak.reservations.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface GuestIdentityPort {
    fun updateGuest(propertyId: UUID, guestId: UUID, request: UpdateGuestRequest): GuestResponse
    fun listDocuments(propertyId: UUID, guestId: UUID): List<GuestIdentityDocumentResponse>
    fun verifyIdentity(
        propertyId: UUID,
        guestId: UUID,
        request: VerifyGuestIdentityRequest,
    ): GuestIdentityVerificationReceipt
    fun manuallyVerifyIdentity(
        propertyId: UUID,
        guestId: UUID,
        request: ManualGuestIdentityVerificationRequest,
    ): GuestIdentityVerificationReceipt
    fun revokeIdentity(
        propertyId: UUID,
        guestId: UUID,
        documentId: UUID,
        request: RevokeGuestIdentityRequest,
    ): GuestIdentityDocumentResponse
    fun addReservationGuest(
        propertyId: UUID,
        reservationId: UUID,
        request: AddReservationGuestRequest,
    ): ReservationGuestResponse
    fun listReservationGuests(propertyId: UUID, reservationId: UUID): List<ReservationGuestResponse>
    fun removeReservationGuest(propertyId: UUID, reservationId: UUID, guestId: UUID)
    fun identityReadiness(propertyId: UUID, reservationId: UUID): ReservationIdentityReadinessResponse
}

@NamedInterface("api")
interface GuestIdentityReadinessPort {
    fun requireReadyInCurrentTransaction(tenantId: UUID, propertyId: UUID, reservationId: UUID)
}

@NamedInterface("api")
interface GuestIdentityVerificationProvider {
    val providerId: String
    fun verify(command: GuestIdentityProviderCommand): GuestIdentityProviderResult
}
