package com.mwombeki.peak.reservations.internal

import com.mwombeki.peak.reservations.api.GuestIdentityDocumentType
import com.mwombeki.peak.reservations.api.ReservationGuestRelationship
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuestIdentityPolicyTests {

    private val policy = GuestIdentityPolicy()
    private val checkInDate = LocalDate.of(2026, 7, 1)

    @Test
    fun acceptsVerifiedNidaForTanzanianAdult() {
        val guestId = UUID.randomUUID()
        val result = policy.evaluate(
            reservationId = UUID.randomUUID(),
            checkInDate = checkInDate,
            expectedAdults = 1,
            expectedChildren = 0,
            occupants = listOf(adult(guestId, "TZ")),
            documents = mapOf(
                guestId to listOf(
                    IdentityDocument(GuestIdentityDocumentType.NIDA, true, null),
                ),
            ),
        )

        assertTrue(result.ready)
    }

    @Test
    fun rejectsExpiredPassportForForeignAdult() {
        val guestId = UUID.randomUUID()
        val result = policy.evaluate(
            reservationId = UUID.randomUUID(),
            checkInDate = checkInDate,
            expectedAdults = 1,
            expectedChildren = 0,
            occupants = listOf(adult(guestId, "KE")),
            documents = mapOf(
                guestId to listOf(
                    IdentityDocument(
                        GuestIdentityDocumentType.PASSPORT,
                        true,
                        checkInDate.minusDays(1),
                    ),
                ),
            ),
        )

        assertFalse(result.ready)
        assertTrue(result.occupants.single().reasons.contains("valid_verified_identity_required"))
    }

    @Test
    fun acceptsMinorOnlyWithAttestedVerifiedAdultGuardian() {
        val guardianId = UUID.randomUUID()
        val childId = UUID.randomUUID()
        val occupants = listOf(
            adult(guardianId, "TZ"),
            IdentityOccupant(
                guestId = childId,
                dateOfBirth = LocalDate.of(2015, 1, 1),
                nationality = "TZ",
                relationship = ReservationGuestRelationship.CHILD,
                guardianGuestId = guardianId,
                guardianAttestedAt = Instant.parse("2026-06-30T10:00:00Z"),
            ),
        )
        val result = policy.evaluate(
            reservationId = UUID.randomUUID(),
            checkInDate = checkInDate,
            expectedAdults = 1,
            expectedChildren = 1,
            occupants = occupants,
            documents = mapOf(
                guardianId to listOf(
                    IdentityDocument(GuestIdentityDocumentType.NIDA, true, null),
                ),
            ),
        )

        assertTrue(result.ready)
    }

    @Test
    fun rejectsReservationWhenDeclaredOccupancyDoesNotMatchAttachedGuests() {
        val guestId = UUID.randomUUID()
        val result = policy.evaluate(
            reservationId = UUID.randomUUID(),
            checkInDate = checkInDate,
            expectedAdults = 2,
            expectedChildren = 0,
            occupants = listOf(adult(guestId, "TZ")),
            documents = mapOf(
                guestId to listOf(
                    IdentityDocument(GuestIdentityDocumentType.NIDA, true, null),
                ),
            ),
        )

        assertFalse(result.ready)
        assertTrue(result.reasons.contains("occupant_count_mismatch"))
        assertTrue(result.reasons.contains("adult_count_mismatch"))
    }

    private fun adult(guestId: UUID, nationality: String): IdentityOccupant {
        return IdentityOccupant(
            guestId = guestId,
            dateOfBirth = LocalDate.of(1990, 1, 1),
            nationality = nationality,
            relationship = ReservationGuestRelationship.ADULT,
            guardianGuestId = null,
            guardianAttestedAt = null,
        )
    }
}
