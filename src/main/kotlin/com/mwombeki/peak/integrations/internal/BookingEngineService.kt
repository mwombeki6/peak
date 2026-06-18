package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.integrations.api.PublicBookingPort
import com.mwombeki.peak.integrations.api.PublicBookingSessionRequest
import com.mwombeki.peak.integrations.api.PublicBookingSessionResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class BookingEngineService(
    private val jdbcTemplate: JdbcTemplate
) : PublicBookingPort {

    @Transactional
    override fun createPublicSession(request: PublicBookingSessionRequest): PublicBookingSessionResponse {
        // 1. Enforce safety check from backend-implementation-contract.md
        val moduleActive = jdbcTemplate.queryForObject(
            "SELECT can_access_public_module(?, 'booking_engine')",
            Boolean::class.java,
            request.propertyId
        ) ?: false

        if (!moduleActive) {
            throw IllegalStateException("Public Booking Engine is disabled for this property.")
        }

        val newSessionId = UUID.randomUUID()
        val expirationTime = Instant.now().plusSeconds(900) // Session lasts 15 minutes

        // 2. Persist directly using clean, raw JDBC queries
        jdbcTemplate.update(
            """
            INSERT INTO booking_sessions (id, property_id, room_type_id, guest_name, guest_email, status, expires_at)
            VALUES (?, ?, ?, ?, ?, 'PENDING_PAYMENT', ?)
            """.trimIndent(),
            newSessionId,
            request.propertyId,
            request.roomTypeId,
            request.guestName,
            request.guestEmail,
            java.sql.Timestamp.from(expirationTime)
        )

        return PublicBookingSessionResponse(
            sessionId = newSessionId,
            status = "PENDING_PAYMENT",
            totalAmount = 0.0, // This will connect to your pricing lookup later!
            expiresAt = expirationTime
        )
    }
}