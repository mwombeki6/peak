package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.pos.api.PosConflictException
import com.mwombeki.peak.pos.api.PosRoomChargeCandidateResponse
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * Purpose-built in-house search for posting F&B to a guest folio from a PIN till.
 *
 * GET /rooms and GET /reservations stay STRONG. This returns only what a waiter
 * needs to confirm the room: stay, room number, and a display name. No passport,
 * phone, email, folio UUID, or reservation dump.
 *
 * A match is not a posting right. [requireEligibleStay] re-checks in-house status,
 * room assignment, and an open folio at settle time so a candidate found before
 * checkout cannot be charged after it.
 */
@Service
class PosRoomChargeService(
    private val jdbcTemplate: JdbcTemplate,
    private val commandExecutor: PosCommandExecutor,
) {
    fun listCandidates(
        propertyId: UUID,
        query: String?,
    ): List<PosRoomChargeCandidateResponse> {
        val needle = query?.trim().orEmpty()
        if (needle.isEmpty()) {
            return emptyList()
        }
        require(needle.length <= MAX_QUERY_LENGTH) {
            "query must not exceed $MAX_QUERY_LENGTH characters"
        }
        return commandExecutor.read(propertyId) { actor ->
            jdbcTemplate.query(
                """
                SELECT s.id AS stay_id,
                       s.room_id,
                       rm.room_number,
                       $GUEST_DISPLAY_SQL AS guest_display_name,
                       (
                           s.status = 'checked_in'
                           AND rr.status = 'checked_in'
                           AND rr.room_id = s.room_id
                           AND f.status = 'open'
                           AND f.deleted_at IS NULL
                       ) AS posting_eligible
                FROM stays s
                JOIN rooms rm
                  ON rm.tenant_id = s.tenant_id
                 AND rm.id = s.room_id
                JOIN reservations r
                  ON r.tenant_id = s.tenant_id
                 AND r.id = s.reservation_id
                JOIN guests g
                  ON g.tenant_id = r.tenant_id
                 AND g.id = r.primary_guest_id
                JOIN folios f
                  ON f.tenant_id = r.tenant_id
                 AND f.reservation_id = r.id
                 AND f.deleted_at IS NULL
                JOIN reservation_rooms rr
                  ON rr.tenant_id = r.tenant_id
                 AND rr.reservation_id = r.id
                 AND rr.room_id = s.room_id
                WHERE s.tenant_id = ?
                  AND r.property_id = ?
                  AND rm.property_id = ?
                  AND s.status = 'checked_in'
                  AND (
                      strpos(lower(rm.room_number), lower(?)) > 0
                      OR strpos(lower($GUEST_DISPLAY_SQL), lower(?)) > 0
                  )
                ORDER BY rm.room_number, s.id
                LIMIT $MAX_RESULTS
                """.trimIndent(),
                { rs, _ ->
                    PosRoomChargeCandidateResponse(
                        stayId = rs.getObject("stay_id", UUID::class.java),
                        roomId = rs.getObject("room_id", UUID::class.java),
                        roomNumber = rs.getString("room_number"),
                        guestDisplayName = rs.getString("guest_display_name"),
                        postingEligible = rs.getBoolean("posting_eligible"),
                    )
                },
                actor.tenantId,
                propertyId,
                propertyId,
                needle,
                needle,
            )
        }
    }

    fun requireEligibleStay(
        tenantId: UUID,
        propertyId: UUID,
        stayId: UUID,
        expectedRoomNumber: String?,
        expectedFolioId: UUID?,
    ): RoomChargeTarget {
        val target = jdbcTemplate.query(
            """
            SELECT s.id AS stay_id,
                   s.room_id,
                   rm.room_number,
                   f.id AS folio_id
            FROM stays s
            JOIN rooms rm
              ON rm.tenant_id = s.tenant_id
             AND rm.id = s.room_id
            JOIN reservations r
              ON r.tenant_id = s.tenant_id
             AND r.id = s.reservation_id
            JOIN folios f
              ON f.tenant_id = r.tenant_id
             AND f.reservation_id = r.id
             AND f.deleted_at IS NULL
            JOIN reservation_rooms rr
              ON rr.tenant_id = r.tenant_id
             AND rr.reservation_id = r.id
             AND rr.room_id = s.room_id
            WHERE s.tenant_id = ?
              AND r.property_id = ?
              AND rm.property_id = ?
              AND s.id = ?
              AND s.status = 'checked_in'
              AND rr.status = 'checked_in'
              AND rr.room_id = s.room_id
              AND f.status = 'open'
            """.trimIndent(),
            { rs, _ ->
                RoomChargeTarget(
                    stayId = rs.getObject("stay_id", UUID::class.java),
                    roomId = rs.getObject("room_id", UUID::class.java),
                    roomNumber = rs.getString("room_number"),
                    folioId = rs.getObject("folio_id", UUID::class.java),
                )
            },
            tenantId,
            propertyId,
            propertyId,
            stayId,
        ).singleOrNull()
            ?: throw PosConflictException(
                "That stay is no longer in-house, so it cannot take a room charge",
            )

        val namedRoom = expectedRoomNumber?.trim().orEmpty()
        if (namedRoom.isNotEmpty() && !target.roomNumber.equals(namedRoom, ignoreCase = true)) {
            throw PosConflictException(
                "That stay belongs to room ${target.roomNumber}, not room $namedRoom",
            )
        }
        if (expectedFolioId != null && expectedFolioId != target.folioId) {
            throw PosConflictException(
                "That stay does not match the named folio",
            )
        }
        return target
    }

    data class RoomChargeTarget(
        val stayId: UUID,
        val roomId: UUID,
        val roomNumber: String,
        val folioId: UUID,
    )

    private companion object {
        const val MAX_QUERY_LENGTH = 80
        const val MAX_RESULTS = 20
        const val GUEST_DISPLAY_SQL = """
            COALESCE(
                NULLIF(btrim(g.full_name), ''),
                NULLIF(btrim(concat_ws(' ', NULLIF(btrim(g.first_name), ''), NULLIF(btrim(g.last_name), ''))), ''),
                'Guest'
            )
        """
    }
}
