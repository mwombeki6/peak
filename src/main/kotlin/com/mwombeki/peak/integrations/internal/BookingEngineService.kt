package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.integrations.api.PublicBookingPort
import com.mwombeki.peak.integrations.api.PublicBookingSessionRequest
import com.mwombeki.peak.integrations.api.PublicBookingSessionResponse
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class BookingEngineService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val publicRequestScopeResolver: PublicRequestScopeResolver,
    private val databaseSessionContext: DatabaseSessionContext,
) : PublicBookingPort {

    @Transactional
    override fun createPublicSession(
        propertyId: UUID,
        request: PublicBookingSessionRequest,
    ): PublicBookingSessionResponse {
        val context = requestContextHolder.current()
        val scope = publicRequestScopeResolver.resolve(
            context = context,
            propertyId = propertyId,
            moduleId = BOOKING_ENGINE_MODULE,
        )
        requestContextHolder.set(context.withPublicScope(scope))

        val nights = ChronoUnit.DAYS.between(request.checkInDate, request.checkOutDate)
        require(nights > 0) {
            "Check-out date must be after check-in date"
        }

        val moduleActive = jdbcTemplate.queryForObject(
            "SELECT can_access_public_module(?, ?, 'booking_engine')",
            Boolean::class.java,
            scope.tenantId,
            scope.propertyId,
        ) ?: false

        if (!moduleActive) {
            throw IllegalStateException("Public Booking Engine is disabled for this property.")
        }

        databaseSessionContext.bind(requestContextHolder.current().identity)

        val newSessionId = UUID.randomUUID()
        val expirationTime = Instant.now().plusSeconds(900)

        jdbcTemplate.update(
            """
            INSERT INTO booking_sessions (
                id,
                tenant_id,
                property_id,
                check_in_date,
                check_out_date,
                guest_name,
                guest_email,
                status,
                expires_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, 'payment_pending', ?)
            """.trimIndent(),
            newSessionId,
            scope.tenantId,
            scope.propertyId,
            request.checkInDate,
            request.checkOutDate,
            request.guestName,
            request.guestEmail,
            java.sql.Timestamp.from(expirationTime)
        )

        jdbcTemplate.update(
            """
            INSERT INTO booking_session_rooms (
                tenant_id,
                session_id,
                room_type_id,
                nightly_rate,
                nights
            )
            VALUES (?, ?, ?, 0, ?)
            """.trimIndent(),
            scope.tenantId,
            newSessionId,
            request.roomTypeId,
            nights.toInt(),
        )

        return PublicBookingSessionResponse(
            sessionId = newSessionId,
            status = "payment_pending",
            totalAmount = 0.0,
            expiresAt = expirationTime
        )
    }

    private companion object {
        const val BOOKING_ENGINE_MODULE = "booking_engine"
    }
}
