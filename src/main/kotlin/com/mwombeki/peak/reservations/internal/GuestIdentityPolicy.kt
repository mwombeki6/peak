package com.mwombeki.peak.reservations.internal

import com.mwombeki.peak.reservations.api.GuestIdentityDocumentType
import com.mwombeki.peak.reservations.api.ReservationGuestIdentityReadiness
import com.mwombeki.peak.reservations.api.ReservationGuestRelationship
import com.mwombeki.peak.reservations.api.ReservationIdentityReadinessResponse
import java.time.LocalDate
import java.time.Period
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class GuestIdentityPolicy {

    fun evaluate(
        reservationId: UUID,
        checkInDate: LocalDate,
        expectedAdults: Int,
        expectedChildren: Int,
        occupants: List<IdentityOccupant>,
        documents: Map<UUID, List<IdentityDocument>>,
    ): ReservationIdentityReadinessResponse {
        val baseResults = occupants.associate { occupant ->
            occupant.guestId to evaluateBase(checkInDate, occupant, documents[occupant.guestId].orEmpty())
        }
        val results = occupants.map { occupant ->
            val base = baseResults.getValue(occupant.guestId)
            if (!occupant.isMinorAt(checkInDate)) {
                base
            } else {
                val guardian = occupant.guardianGuestId?.let { guardianId ->
                    occupants.singleOrNull { it.guestId == guardianId }
                }
                val guardianResult = guardian?.let { baseResults[it.guestId] }
                val reasons = base.reasons.toMutableList()
                if (occupant.guardianGuestId == null || occupant.guardianAttestedAt == null) {
                    reasons += "guardian_attestation_required"
                } else if (guardian == null || guardian.isMinorAt(checkInDate)) {
                    reasons += "verified_adult_guardian_required"
                } else if (guardianResult?.ready != true) {
                    reasons += "guardian_identity_not_verified"
                }
                ReservationGuestIdentityReadiness(
                    guestId = occupant.guestId,
                    ready = reasons.isEmpty(),
                    reasons = reasons.distinct(),
                )
            }
        }

        val overallReasons = mutableListOf<String>()
        if (occupants.size != expectedAdults + expectedChildren) {
            overallReasons += "occupant_count_mismatch"
        }
        val classifiedAdults = occupants.count { it.ageAt(checkInDate)?.let { age -> age >= ADULT_AGE } == true }
        val classifiedChildren = occupants.count { it.ageAt(checkInDate)?.let { age -> age < ADULT_AGE } == true }
        if (classifiedAdults != expectedAdults) {
            overallReasons += "adult_count_mismatch"
        }
        if (classifiedChildren != expectedChildren) {
            overallReasons += "child_count_mismatch"
        }
        if (occupants.isEmpty()) {
            overallReasons += "reservation_has_no_occupants"
        }

        return ReservationIdentityReadinessResponse(
            reservationId = reservationId,
            ready = overallReasons.isEmpty() && results.all { it.ready },
            occupants = results,
            reasons = overallReasons,
        )
    }

    private fun evaluateBase(
        checkInDate: LocalDate,
        occupant: IdentityOccupant,
        documents: List<IdentityDocument>,
    ): ReservationGuestIdentityReadiness {
        val reasons = mutableListOf<String>()
        val age = occupant.ageAt(checkInDate)
        if (occupant.dateOfBirth == null) {
            reasons += "date_of_birth_required"
        }
        if (occupant.nationality.isNullOrBlank()) {
            reasons += "nationality_required"
        }
        if (age != null && age < ADULT_AGE) {
            if (occupant.relationship == ReservationGuestRelationship.ADULT) {
                reasons += "minor_relationship_required"
            }
        } else if (age != null && occupant.relationship != ReservationGuestRelationship.ADULT) {
            reasons += "adult_relationship_required"
        }

        if (age == null || age >= ADULT_AGE) {
            val acceptedTypes = if (occupant.nationality.equals(TANZANIA, ignoreCase = true)) {
                TANZANIAN_ADULT_DOCUMENTS
            } else {
                FOREIGN_ADULT_DOCUMENTS
            }
            val hasValidDocument = documents.any {
                it.documentType in acceptedTypes &&
                        it.verified &&
                        (it.expiresAt == null || !it.expiresAt.isBefore(checkInDate))
            }
            if (!hasValidDocument) {
                reasons += "valid_verified_identity_required"
            }
        }

        return ReservationGuestIdentityReadiness(
            guestId = occupant.guestId,
            ready = reasons.isEmpty(),
            reasons = reasons,
        )
    }

    private fun IdentityOccupant.ageAt(date: LocalDate): Int? {
        val birthDate = dateOfBirth ?: return null
        if (birthDate.isAfter(date)) {
            return null
        }
        return Period.between(birthDate, date).years
    }

    private fun IdentityOccupant.isMinorAt(date: LocalDate): Boolean {
        return ageAt(date)?.let { it < ADULT_AGE } == true
    }

    private companion object {
        const val ADULT_AGE = 18
        const val TANZANIA = "TZ"

        val TANZANIAN_ADULT_DOCUMENTS = setOf(
            GuestIdentityDocumentType.NIDA,
            GuestIdentityDocumentType.PASSPORT,
            GuestIdentityDocumentType.DRIVING_LICENCE,
            GuestIdentityDocumentType.VOTER_ID,
            GuestIdentityDocumentType.RESIDENCE_PERMIT,
        )
        val FOREIGN_ADULT_DOCUMENTS = setOf(
            GuestIdentityDocumentType.PASSPORT,
            GuestIdentityDocumentType.NIDA,
            GuestIdentityDocumentType.RESIDENCE_PERMIT,
        )
    }
}

data class IdentityOccupant(
    val guestId: UUID,
    val dateOfBirth: LocalDate?,
    val nationality: String?,
    val relationship: ReservationGuestRelationship,
    val guardianGuestId: UUID?,
    val guardianAttestedAt: java.time.Instant?,
)

data class IdentityDocument(
    val documentType: GuestIdentityDocumentType,
    val verified: Boolean,
    val expiresAt: LocalDate?,
)
