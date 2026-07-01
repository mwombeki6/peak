package com.mwombeki.peak.reservations.api

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
enum class GuestIdentityDocumentType(val databaseValue: String) {
    NIDA("nida"),
    PASSPORT("passport"),
    DRIVING_LICENCE("driving_licence"),
    VOTER_ID("voter_id"),
    RESIDENCE_PERMIT("residence_permit"),
    BIRTH_CERTIFICATE("birth_certificate"),
    OTHER_RECOGNISED("other_recognised"),
}

enum class GuestIdentityVerificationStatus(val databaseValue: String) {
    UNVERIFIED("unverified"),
    PENDING("pending"),
    VERIFIED("verified"),
    FAILED("failed"),
    EXPIRED("expired"),
    REVOKED("revoked"),
    LEGACY_UNVERIFIED("legacy_unverified"),
}

enum class ReservationGuestRelationship(val databaseValue: String) {
    ADULT("adult"),
    CHILD("child"),
    DEPENDENT("dependent"),
}

data class UpdateGuestRequest(
    val fullName: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val dateOfBirth: LocalDate? = null,
    val nationality: String? = null,
    val email: String? = null,
    val phonePrimary: String? = null,
)

data class VerifyGuestIdentityRequest(
    val documentType: GuestIdentityDocumentType,
    val documentNumber: String,
    val issuingCountry: String? = null,
    val issuingAuthority: String? = null,
    val issuedAt: LocalDate? = null,
    val expiresAt: LocalDate? = null,
)

data class ManualGuestIdentityVerificationRequest(
    val documentType: GuestIdentityDocumentType,
    val documentNumber: String,
    val issuingCountry: String? = null,
    val issuingAuthority: String? = null,
    val issuedAt: LocalDate? = null,
    val expiresAt: LocalDate? = null,
    val attestationReason: String,
)

data class RevokeGuestIdentityRequest(
    val reason: String,
)

data class GuestIdentityDocumentResponse(
    val id: UUID,
    val guestId: UUID,
    val documentType: GuestIdentityDocumentType,
    val maskedDocumentNumber: String,
    val issuingCountry: String?,
    val issuingAuthority: String?,
    val issuedAt: LocalDate?,
    val expiresAt: LocalDate?,
    val verificationStatus: GuestIdentityVerificationStatus,
    val verificationMethod: String?,
    val verificationProvider: String?,
    val verifiedAt: Instant?,
    val verificationExpiresAt: Instant?,
    val revokedAt: Instant?,
)

data class GuestIdentityVerificationReceipt(
    val attemptId: UUID,
    val document: GuestIdentityDocumentResponse,
    val failureCode: String?,
    val changed: Boolean,
    val replayed: Boolean,
)

data class AddReservationGuestRequest(
    val guestId: UUID,
    val relationship: ReservationGuestRelationship,
    val guardianGuestId: UUID? = null,
    val guardianAttestation: Boolean = false,
)

data class ReservationGuestResponse(
    val guestId: UUID,
    val fullName: String,
    val primary: Boolean,
    val relationship: ReservationGuestRelationship,
    val guardianGuestId: UUID?,
)

data class ReservationGuestIdentityReadiness(
    val guestId: UUID,
    val ready: Boolean,
    val reasons: List<String>,
)

data class ReservationIdentityReadinessResponse(
    val reservationId: UUID,
    val ready: Boolean,
    val occupants: List<ReservationGuestIdentityReadiness>,
    val reasons: List<String>,
)

@NamedInterface("api")
data class GuestIdentityProviderCommand(
    val documentType: GuestIdentityDocumentType,
    val documentNumber: String,
    val fullName: String,
    val dateOfBirth: LocalDate,
    val nationality: String,
    val correlationId: String,
)

@NamedInterface("api")
sealed interface GuestIdentityProviderResult {
    @NamedInterface("api")
    data class Verified(
        val providerReference: String,
        val expiresAt: Instant? = null,
    ) : GuestIdentityProviderResult

    @NamedInterface("api")
    data class Rejected(
        val failureCode: String,
        val providerReference: String? = null,
    ) : GuestIdentityProviderResult

    @NamedInterface("api")
    data class Unavailable(
        val failureCode: String,
    ) : GuestIdentityProviderResult
}
