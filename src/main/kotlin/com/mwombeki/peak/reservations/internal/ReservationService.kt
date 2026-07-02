package com.mwombeki.peak.reservations.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.billing.api.BillingPort
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.reservations.api.AmendReservationRequest
import com.mwombeki.peak.reservations.api.CancelReservationRequest
import com.mwombeki.peak.reservations.api.CreateGuestRequest
import com.mwombeki.peak.reservations.api.CreateReservationRequest
import com.mwombeki.peak.reservations.api.GuestResponse
import com.mwombeki.peak.reservations.api.ReservationConflictException
import com.mwombeki.peak.reservations.api.ReservationInProgressException
import com.mwombeki.peak.reservations.api.ReservationMutationReceipt
import com.mwombeki.peak.reservations.api.ReservationNotFoundException
import com.mwombeki.peak.reservations.api.ReservationPort
import com.mwombeki.peak.reservations.api.ReservationResponse
import com.mwombeki.peak.shared.context.TenantActor
import com.mwombeki.peak.shared.context.TenantRequestContext
import io.micrometer.core.instrument.MeterRegistry
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Service
class ReservationService(
    private val jdbcTemplate: JdbcTemplate,
    private val tenantRequestContext: TenantRequestContext,
    private val billingPort: BillingPort,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : ReservationPort {

    override fun createGuestInCurrentTransaction(
        tenantId: UUID,
        propertyId: UUID,
        request: CreateGuestRequest,
    ): GuestResponse {
        tenantRequestContext.requireTenantUsable(tenantId)
        tenantRequestContext.requirePropertyUsable(tenantId, propertyId)
        return createGuestInternal(tenantId, propertyId, request, idempotencyKeyId = null)
    }

    override fun createReservationInCurrentTransaction(
        tenantId: UUID,
        propertyId: UUID,
        request: CreateReservationRequest,
        idempotencyKeyId: UUID,
    ): ReservationMutationReceipt {
        tenantRequestContext.requireTenantUsable(tenantId)
        tenantRequestContext.requirePropertyUsable(tenantId, propertyId)
        return createReservationInternal(currentActor(tenantId), propertyId, request, idempotencyKeyId)
    }

    override fun createGuest(propertyId: UUID, request: CreateGuestRequest): GuestResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "reservations.guest.create",
            requestPayload = request,
            resourceType = GUESTS,
            replayType = GuestResponse::class.java,
        ) { actor, idempotencyKeyId ->
            createGuestInternal(actor.tenantId, propertyId, request, idempotencyKeyId)
        }
    }

    override fun listGuests(propertyId: UUID): List<GuestResponse> {
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                """
                SELECT id, tenant_id, full_name, first_name, last_name, email, phone_primary, date_of_birth,
                       nationality, vip_level, blacklisted
                FROM guests g
                WHERE g.tenant_id = ?
                  AND g.deleted_at IS NULL
                  AND (
                      g.origin_property_id = ?
                      OR EXISTS (
                          SELECT 1
                          FROM reservation_guests rg
                          JOIN reservations r
                            ON r.tenant_id = rg.tenant_id
                           AND r.id = rg.reservation_id
                          WHERE rg.tenant_id = g.tenant_id
                            AND rg.guest_id = g.id
                            AND r.property_id = ?
                            AND r.deleted_at IS NULL
                      )
                  )
                ORDER BY full_name NULLS LAST, last_name NULLS LAST, first_name NULLS LAST, created_at DESC
                LIMIT 500
                """.trimIndent(),
                GuestResponseRowMapper,
                actor.tenantId,
                propertyId,
                propertyId,
            )
        }
    }

    override fun getGuest(propertyId: UUID, guestId: UUID): GuestResponse? {
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                """
                SELECT id, tenant_id, full_name, first_name, last_name, email, phone_primary, date_of_birth,
                       nationality, vip_level, blacklisted
                FROM guests g
                WHERE g.tenant_id = ?
                  AND g.id = ?
                  AND g.deleted_at IS NULL
                  AND (
                      g.origin_property_id = ?
                      OR EXISTS (
                          SELECT 1
                          FROM reservation_guests rg
                          JOIN reservations r
                            ON r.tenant_id = rg.tenant_id
                           AND r.id = rg.reservation_id
                          WHERE rg.tenant_id = g.tenant_id
                            AND rg.guest_id = g.id
                            AND r.property_id = ?
                            AND r.deleted_at IS NULL
                      )
                  )
                """.trimIndent(),
                GuestResponseRowMapper,
                actor.tenantId,
                guestId,
                propertyId,
                propertyId,
            ).singleOrNull()
        }
    }

    override fun createReservation(
        propertyId: UUID,
        request: CreateReservationRequest,
    ): ReservationMutationReceipt {
        return mutate(
            propertyId = propertyId,
            operationType = "reservations.create",
            requestPayload = request,
            resourceType = RESERVATIONS,
            replayType = ReservationMutationReceipt::class.java,
        ) { actor, idempotencyKeyId ->
            createReservationInternal(actor, propertyId, request, idempotencyKeyId)
        }
    }

    override fun amendReservation(
        propertyId: UUID,
        reservationId: UUID,
        request: AmendReservationRequest,
    ): ReservationMutationReceipt {
        return mutate(
            propertyId = propertyId,
            operationType = "reservations.amend",
            requestPayload = mapOf("reservationId" to reservationId, "request" to request),
            resourceType = RESERVATIONS,
            replayType = ReservationMutationReceipt::class.java,
        ) { actor, idempotencyKeyId ->
            val current = requireReservation(actor.tenantId, propertyId, reservationId, lock = true)
            if (current.status !in setOf("pending", "confirmed")) {
                throw ReservationConflictException("Only pending or confirmed reservations can be amended")
            }
            val room = requireReservationRoom(actor.tenantId, reservationId, lock = true)
            val roomTypeId = request.roomTypeId ?: room.roomTypeId
            val roomId = request.roomId ?: room.roomId
            val checkInDate = request.checkInDate ?: current.checkInDate
            val checkOutDate = request.checkOutDate ?: current.checkOutDate
            val adults = request.adults ?: current.adults
            val children = request.children ?: current.children
            val ratePerNight = request.ratePerNight ?: room.ratePerNight
            validateReservationInput(actor.tenantId, propertyId, roomTypeId, roomId, checkInDate, checkOutDate, adults, children, ratePerNight)
            val totalAmount = totalRoomAmount(checkInDate, checkOutDate, ratePerNight)

            jdbcTemplate.update(
                """
                UPDATE reservations
                SET check_in_date = ?,
                    check_out_date = ?,
                    adults = ?,
                    children = ?,
                    total_amount = ?,
                    special_requests = COALESCE(?, special_requests),
                    internal_notes = COALESCE(?, internal_notes),
                    updated_at = now()
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND id = ?
                """.trimIndent(),
                checkInDate,
                checkOutDate,
                adults,
                children,
                totalAmount,
                request.specialRequests?.trimmedOrNull(),
                request.internalNotes?.trimmedOrNull(),
                actor.tenantId,
                propertyId,
                reservationId,
            )
            jdbcTemplate.update(
                """
                UPDATE reservation_rooms
                SET room_type_id = ?,
                    room_id = ?,
                    check_in_date = ?,
                    check_out_date = ?,
                    rate_per_night = ?,
                    updated_at = now()
                WHERE tenant_id = ?
                  AND reservation_id = ?
                  AND id = ?
                """.trimIndent(),
                roomTypeId,
                roomId,
                checkInDate,
                checkOutDate,
                ratePerNight.money(),
                actor.tenantId,
                reservationId,
                room.id,
            )
            jdbcTemplate.update(
                "DELETE FROM reservation_room_nights WHERE tenant_id = ? AND reservation_room_id = ?",
                actor.tenantId,
                room.id,
            )
            insertRoomNights(actor.tenantId, reservationId, room.id, roomTypeId, roomId, checkInDate, checkOutDate, ratePerNight)
            recordSideEffects(
                tenantId = actor.tenantId,
                propertyId = propertyId,
                action = "reservations.amended",
                eventType = "reservations.amended",
                aggregateId = reservationId,
                payload = mapOf("propertyId" to propertyId, "reservationId" to reservationId),
                idempotencyKeyId = idempotencyKeyId,
            )
            val updated = requireReservationResponse(actor.tenantId, propertyId, reservationId)
            ReservationMutationReceipt(reservationId, propertyId, updated.status, updated.folioId, changed = true, replayed = false)
        }
    }

    override fun cancelReservation(
        propertyId: UUID,
        reservationId: UUID,
        request: CancelReservationRequest,
    ): ReservationMutationReceipt {
        return mutate(
            propertyId = propertyId,
            operationType = "reservations.cancel",
            requestPayload = mapOf("reservationId" to reservationId, "request" to request),
            resourceType = RESERVATIONS,
            replayType = ReservationMutationReceipt::class.java,
        ) { actor, idempotencyKeyId ->
            val reservation = requireReservation(actor.tenantId, propertyId, reservationId, lock = true)
            if (reservation.status !in setOf("pending", "confirmed")) {
                throw ReservationConflictException("Only pending or confirmed reservations can be cancelled")
            }
            val cancellationFee = request.cancellationFee.requireNonNegativeMoney("cancellationFee")
            jdbcTemplate.update(
                """
                UPDATE reservations
                SET status = 'cancelled',
                    cancelled_at = now(),
                    cancellation_reason = ?,
                    cancellation_fee = ?,
                    updated_at = now()
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND id = ?
                """.trimIndent(),
                request.reason.normalizedRequired("reason"),
                cancellationFee,
                actor.tenantId,
                propertyId,
                reservationId,
            )
            jdbcTemplate.update(
                """
                UPDATE reservation_rooms
                SET status = 'cancelled', updated_at = now()
                WHERE tenant_id = ? AND reservation_id = ? AND status = 'reserved'
                """.trimIndent(),
                actor.tenantId,
                reservationId,
            )
            recordSideEffects(
                tenantId = actor.tenantId,
                propertyId = propertyId,
                action = "reservations.cancelled",
                eventType = "reservations.cancelled",
                aggregateId = reservationId,
                payload = mapOf(
                    "propertyId" to propertyId,
                    "reservationId" to reservationId,
                    "reason" to request.reason,
                    "cancellationFee" to cancellationFee,
                ),
                idempotencyKeyId = idempotencyKeyId,
            )
            ReservationMutationReceipt(reservationId, propertyId, "cancelled", reservation.folioId, changed = true, replayed = false)
        }
    }

    override fun listReservations(propertyId: UUID): List<ReservationResponse> {
        return read(propertyId) { actor ->
            jdbcTemplate.query(RESERVATION_SELECT + " ORDER BY r.check_in_date DESC, r.created_at DESC LIMIT 500", ::mapReservation, actor.tenantId, propertyId)
        }
    }

    override fun getReservation(propertyId: UUID, reservationId: UUID): ReservationResponse? {
        return read(propertyId) { actor ->
            reservationResponse(actor.tenantId, propertyId, reservationId)
        }
    }

    private fun createGuestInternal(
        tenantId: UUID,
        propertyId: UUID,
        request: CreateGuestRequest,
        idempotencyKeyId: UUID?,
    ): GuestResponse {
        request.dateOfBirth?.let {
            require(!it.isAfter(LocalDate.now())) {
                "dateOfBirth cannot be in the future"
            }
        }
        val fullName = request.fullName?.trimmedOrNull()
            ?: listOfNotNull(request.firstName?.trimmedOrNull(), request.lastName?.trimmedOrNull())
                .joinToString(" ")
                .trimmedOrNull()
            ?: throw IllegalArgumentException("Guest name is required")
        val guestId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO guests (
                id, tenant_id, origin_property_id, guest_number, full_name, first_name, last_name,
                email, phone_primary, date_of_birth, nationality, notes
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            guestId,
            tenantId,
            propertyId,
            guestNumber(),
            fullName,
            request.firstName?.trimmedOrNull(),
            request.lastName?.trimmedOrNull(),
            request.email?.normalizedEmail(),
            request.phonePrimary?.trimmedOrNull(),
            request.dateOfBirth,
            request.nationality?.trimmedOrNull(),
            request.notes?.trimmedOrNull(),
        )
        if (!request.email.isNullOrBlank() || !request.phonePrimary.isNullOrBlank()) {
            jdbcTemplate.update(
                """
                INSERT INTO guest_contacts (tenant_id, guest_id, email, phone)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                tenantId,
                guestId,
                request.email?.normalizedEmail(),
                request.phonePrimary?.trimmedOrNull(),
            )
        }
        val guest = requireNotNull(guestInContext(tenantId, guestId)) {
            "Created guest was not readable"
        }
        recordSideEffects(
            tenantId = tenantId,
            propertyId = propertyId,
            action = "reservations.guest.created",
            eventType = "reservations.guest.created",
            aggregateId = guestId,
            payload = mapOf("propertyId" to propertyId, "guestId" to guestId, "fullName" to fullName),
            idempotencyKeyId = idempotencyKeyId,
            aggregateType = GUESTS,
        )
        return guest
    }

    private fun createReservationInternal(
        actor: TenantActor,
        propertyId: UUID,
        request: CreateReservationRequest,
        idempotencyKeyId: UUID,
    ): ReservationMutationReceipt {
        validateReservationInput(
            actor.tenantId,
            propertyId,
            request.roomTypeId,
            request.roomId,
            request.checkInDate,
            request.checkOutDate,
            request.adults,
            request.children,
            request.ratePerNight,
        )
        requireGuestUsable(actor.tenantId, propertyId, request.primaryGuestId)
        val reservationId = UUID.randomUUID()
        val reservationRoomId = UUID.randomUUID()
        val totalAmount = totalRoomAmount(request.checkInDate, request.checkOutDate, request.ratePerNight)
        jdbcTemplate.update(
            """
            INSERT INTO reservations (
                id, tenant_id, property_id, primary_guest_id, confirmation_number,
                status, check_in_date, check_out_date, adults, children, total_amount,
                special_requests, internal_notes, created_by
            )
            VALUES (?, ?, ?, ?, ?, 'confirmed', ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            reservationId,
            actor.tenantId,
            propertyId,
            request.primaryGuestId,
            confirmationNumber(),
            request.checkInDate,
            request.checkOutDate,
            request.adults,
            request.children,
            totalAmount,
            request.specialRequests?.trimmedOrNull(),
            request.internalNotes?.trimmedOrNull(),
            actor.tenantUserId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO reservation_guests (tenant_id, reservation_id, guest_id, is_primary)
            VALUES (?, ?, ?, true)
            """.trimIndent(),
            actor.tenantId,
            reservationId,
            request.primaryGuestId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO reservation_rooms (
                id, tenant_id, reservation_id, room_type_id, room_id,
                check_in_date, check_out_date, rate_per_night, status
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'reserved')
            """.trimIndent(),
            reservationRoomId,
            actor.tenantId,
            reservationId,
            request.roomTypeId,
            request.roomId,
            request.checkInDate,
            request.checkOutDate,
            request.ratePerNight.money(),
        )
        insertRoomNights(
            actor.tenantId,
            reservationId,
            reservationRoomId,
            request.roomTypeId,
            request.roomId,
            request.checkInDate,
            request.checkOutDate,
            request.ratePerNight,
        )
        val folioId = billingPort.openReservationFolio(actor.tenantId, propertyId, reservationId, idempotencyKeyId)
        jdbcTemplate.update(
            """
            UPDATE reservation_rooms
            SET folio_id = ?, updated_at = now()
            WHERE tenant_id = ? AND id = ?
            """.trimIndent(),
            folioId,
            actor.tenantId,
            reservationRoomId,
        )
        recordSideEffects(
            tenantId = actor.tenantId,
            propertyId = propertyId,
            action = "reservations.created",
            eventType = "reservations.created",
            aggregateId = reservationId,
            payload = mapOf(
                "propertyId" to propertyId,
                "reservationId" to reservationId,
                "folioId" to folioId,
                "checkInDate" to request.checkInDate,
                "checkOutDate" to request.checkOutDate,
                "totalAmount" to totalAmount,
            ),
            idempotencyKeyId = idempotencyKeyId,
        )
        return ReservationMutationReceipt(reservationId, propertyId, "confirmed", folioId, changed = true, replayed = false)
    }

    private fun validateReservationInput(
        tenantId: UUID,
        propertyId: UUID,
        roomTypeId: UUID,
        roomId: UUID?,
        checkInDate: LocalDate,
        checkOutDate: LocalDate,
        adults: Int,
        children: Int,
        ratePerNight: BigDecimal,
    ) {
        require(checkOutDate > checkInDate) {
            "checkOutDate must be after checkInDate"
        }
        require(adults >= 1) {
            "adults must be at least 1"
        }
        require(children >= 0) {
            "children must not be negative"
        }
        ratePerNight.requireNonNegativeMoney("ratePerNight")
        val roomTypeExists = exists(
            """
            SELECT EXISTS (
                SELECT 1
                FROM room_types
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND id = ?
                  AND is_active = true
                  AND deleted_at IS NULL
            )
            """.trimIndent(),
            tenantId,
            propertyId,
            roomTypeId,
        )
        if (!roomTypeExists) {
            throw ReservationNotFoundException("Active room type was not found")
        }
        if (roomId != null) {
            val roomExists = exists(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM rooms
                    WHERE tenant_id = ?
                      AND property_id = ?
                      AND room_type_id = ?
                      AND id = ?
                      AND status IN ('vacant_clean', 'vacant_dirty')
                      AND deleted_at IS NULL
                )
                """.trimIndent(),
                tenantId,
                propertyId,
                roomTypeId,
                roomId,
            )
            if (!roomExists) {
                throw ReservationConflictException("Room is not available for assignment")
            }
        }
    }

    private fun requireGuestUsable(tenantId: UUID, propertyId: UUID, guestId: UUID) {
        val guest = jdbcTemplate.query(
            """
            SELECT blacklisted
            FROM guests g
            WHERE g.tenant_id = ?
              AND g.id = ?
              AND g.deleted_at IS NULL
              AND (
                  g.origin_property_id = ?
                  OR EXISTS (
                      SELECT 1
                      FROM reservation_guests rg
                      JOIN reservations r
                        ON r.tenant_id = rg.tenant_id
                       AND r.id = rg.reservation_id
                      WHERE rg.tenant_id = g.tenant_id
                        AND rg.guest_id = g.id
                        AND r.property_id = ?
                        AND r.deleted_at IS NULL
                  )
              )
            """.trimIndent(),
            { rs, _ -> rs.getBoolean("blacklisted") },
            tenantId,
            guestId,
            propertyId,
            propertyId,
        ).singleOrNull() ?: throw ReservationNotFoundException("Guest was not found")
        if (guest) {
            throw ReservationConflictException("Blacklisted guest cannot be used for reservations")
        }
    }

    private fun insertRoomNights(
        tenantId: UUID,
        reservationId: UUID,
        reservationRoomId: UUID,
        roomTypeId: UUID,
        roomId: UUID?,
        checkInDate: LocalDate,
        checkOutDate: LocalDate,
        ratePerNight: BigDecimal,
    ) {
        var date = checkInDate
        while (date < checkOutDate) {
            jdbcTemplate.update(
                """
                INSERT INTO reservation_room_nights (
                    tenant_id, reservation_id, reservation_room_id, room_id, room_type_id,
                    stay_date, base_rate, tax_amount, fee_amount, discount_amount, final_amount,
                    rate_source
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, 'manual')
                """.trimIndent(),
                tenantId,
                reservationId,
                reservationRoomId,
                roomId,
                roomTypeId,
                date,
                ratePerNight.money(),
                ratePerNight.money(),
            )
            date = date.plusDays(1)
        }
    }

    private fun reservationResponse(tenantId: UUID, propertyId: UUID, reservationId: UUID): ReservationResponse? {
        return jdbcTemplate.query("$RESERVATION_SELECT AND r.id = ?", ::mapReservation, tenantId, propertyId, reservationId)
            .singleOrNull()
    }

    private fun requireReservationResponse(tenantId: UUID, propertyId: UUID, reservationId: UUID): ReservationResponse {
        return reservationResponse(tenantId, propertyId, reservationId)
            ?: throw ReservationNotFoundException("Reservation was not found")
    }

    private fun requireReservation(
        tenantId: UUID,
        propertyId: UUID,
        reservationId: UUID,
        lock: Boolean,
    ): ReservationRecord {
        return jdbcTemplate.query(
            """
            SELECT r.id, r.status, r.check_in_date, r.check_out_date, r.adults, r.children, rr.folio_id
            FROM reservations r
            LEFT JOIN reservation_rooms rr ON rr.tenant_id = r.tenant_id AND rr.reservation_id = r.id
            WHERE r.tenant_id = ?
              AND r.property_id = ?
              AND r.id = ?
              AND r.deleted_at IS NULL
            ${if (lock) "FOR UPDATE OF r" else ""}
            """.trimIndent(),
            { rs, _ ->
                ReservationRecord(
                    id = rs.getObject("id", UUID::class.java),
                    status = rs.getString("status"),
                    checkInDate = rs.getObject("check_in_date", LocalDate::class.java),
                    checkOutDate = rs.getObject("check_out_date", LocalDate::class.java),
                    adults = rs.getInt("adults"),
                    children = rs.getInt("children"),
                    folioId = rs.getObject("folio_id", UUID::class.java),
                )
            },
            tenantId,
            propertyId,
            reservationId,
        ).singleOrNull() ?: throw ReservationNotFoundException("Reservation was not found")
    }

    private fun requireReservationRoom(
        tenantId: UUID,
        reservationId: UUID,
        lock: Boolean,
    ): ReservationRoomRecord {
        return jdbcTemplate.query(
            """
            SELECT id, room_type_id, room_id, rate_per_night
            FROM reservation_rooms
            WHERE tenant_id = ?
              AND reservation_id = ?
            ORDER BY created_at
            LIMIT 1
            ${if (lock) "FOR UPDATE" else ""}
            """.trimIndent(),
            { rs, _ ->
                ReservationRoomRecord(
                    id = rs.getObject("id", UUID::class.java),
                    roomTypeId = rs.getObject("room_type_id", UUID::class.java),
                    roomId = rs.getObject("room_id", UUID::class.java),
                    ratePerNight = rs.getBigDecimal("rate_per_night").money(),
                )
            },
            tenantId,
            reservationId,
        ).singleOrNull() ?: throw ReservationNotFoundException("Reservation room was not found")
    }

    private fun <T : Any> mutate(
        propertyId: UUID,
        operationType: String,
        requestPayload: Any,
        resourceType: String,
        replayType: Class<T>,
        block: (TenantActor, UUID) -> T,
    ): T {
        return requireNotNull(
            transactionTemplate.execute {
                val actor = bindActor(propertyId)
                val reservation = idempotencyPort.reserve(
                    IdempotencyCommand(operationType = operationType, requestPayload = requestPayload, resourceType = resourceType),
                )
                when (reservation) {
                    is IdempotencyReservation.Started -> {
                        try {
                            val response = block(actor, reservation.recordId)
                            idempotencyPort.markSucceeded(reservation.recordId, 200, response, resourceId(response))
                            meterRegistry.counter("peak.reservations.command", "operation", operationType, "result", "succeeded").increment()
                            response
                        } catch (ex: DataIntegrityViolationException) {
                            throw ReservationConflictException(ex.publicDatabaseMessage())
                        }
                    }

                    is IdempotencyReservation.Replay -> {
                        if (reservation.responseBody.isNullOrBlank()) {
                            throw ReservationConflictException("Reservation command replay does not contain a stored response body")
                        }
                        objectMapper.readValue(reservation.responseBody, replayType).withReplayFlag()
                    }

                    is IdempotencyReservation.InProgress -> {
                        meterRegistry.counter("peak.reservations.command", "operation", operationType, "result", "in_progress").increment()
                        throw ReservationInProgressException("Reservation command is already being processed for this idempotency key")
                    }

                    is IdempotencyReservation.Conflict -> {
                        meterRegistry.counter("peak.reservations.command", "operation", operationType, "result", "conflict").increment()
                        throw ReservationConflictException("Idempotency key was already used for a different reservation request")
                    }
                }
            },
        )
    }

    private fun <T> read(propertyId: UUID, block: (TenantActor) -> T): T {
        return transactionTemplate.execute { block(bindActor(propertyId)) }
    }

    private fun bindActor(propertyId: UUID): TenantActor {
        val actor = tenantRequestContext.bind()
        tenantRequestContext.requirePropertyUsable(actor.tenantId, propertyId)
        return actor
    }

    private fun currentActor(tenantId: UUID): TenantActor {
        val actor = tenantRequestContext.bind()
        require(actor.tenantId == tenantId) {
            "Tenant context does not match requested tenant"
        }
        return actor
    }

    private fun recordSideEffects(
        tenantId: UUID,
        propertyId: UUID,
        action: String,
        eventType: String,
        aggregateId: UUID,
        payload: Map<String, Any?>,
        idempotencyKeyId: UUID?,
        aggregateType: String = RESERVATIONS,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = action,
                resource = AuditResource(aggregateType, aggregateId),
                after = payload,
            ),
        )
        if (idempotencyKeyId != null) {
            outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = aggregateType,
                    aggregateId = aggregateId,
                    tenantId = tenantId,
                    propertyId = propertyId,
                    eventType = eventType,
                    destination = OutboxDestination.PLATFORM,
                    payload = payload,
                    idempotencyKeyId = idempotencyKeyId,
                    priority = 4,
                ),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> T.withReplayFlag(): T {
        return when (this) {
            is ReservationMutationReceipt -> copy(replayed = true) as T
            else -> this
        }
    }

    private fun resourceId(response: Any): UUID? {
        return when (response) {
            is GuestResponse -> response.id
            is ReservationMutationReceipt -> response.reservationId
            else -> null
        }
    }

    private fun mapReservation(rs: ResultSet, rowNumber: Int): ReservationResponse {
        return ReservationResponse(
            id = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            primaryGuestId = rs.getObject("primary_guest_id", UUID::class.java),
            confirmationNumber = rs.getString("confirmation_number"),
            status = rs.getString("status"),
            checkInDate = rs.getObject("check_in_date", LocalDate::class.java),
            checkOutDate = rs.getObject("check_out_date", LocalDate::class.java),
            roomTypeId = rs.getObject("room_type_id", UUID::class.java),
            roomId = rs.getObject("room_id", UUID::class.java),
            ratePerNight = rs.getBigDecimal("rate_per_night").money(),
            totalAmount = rs.getBigDecimal("total_amount").money(),
            totalPaid = rs.getBigDecimal("total_paid").money(),
            folioId = rs.getObject("folio_id", UUID::class.java),
        )
    }

    private fun guestInContext(tenantId: UUID, guestId: UUID): GuestResponse? {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, full_name, first_name, last_name, email, phone_primary, date_of_birth,
                   nationality, vip_level, blacklisted
            FROM guests
            WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
            """.trimIndent(),
            GuestResponseRowMapper,
            tenantId,
            guestId,
        ).singleOrNull()
    }

    private fun totalRoomAmount(checkInDate: LocalDate, checkOutDate: LocalDate, ratePerNight: BigDecimal): BigDecimal {
        val nights = ChronoUnit.DAYS.between(checkInDate, checkOutDate)
        require(nights > 0) {
            "Reservation must include at least one night"
        }
        return ratePerNight.money().multiply(BigDecimal(nights)).money()
    }

    private fun exists(sql: String, vararg args: Any?): Boolean {
        return jdbcTemplate.queryForObject(sql, Boolean::class.java, *args) == true
    }

    private data class ReservationRecord(
        val id: UUID,
        val status: String,
        val checkInDate: LocalDate,
        val checkOutDate: LocalDate,
        val adults: Int,
        val children: Int,
        val folioId: UUID?,
    )

    private data class ReservationRoomRecord(
        val id: UUID,
        val roomTypeId: UUID,
        val roomId: UUID?,
        val ratePerNight: BigDecimal,
    )

    private companion object {
        const val GUESTS = "guests"
        const val RESERVATIONS = "reservations"
        const val RESERVATION_SELECT = """
            SELECT r.id, r.tenant_id, r.property_id, r.primary_guest_id, r.confirmation_number,
                   r.status, r.check_in_date, r.check_out_date, rr.room_type_id, rr.room_id,
                   rr.rate_per_night, r.total_amount, r.total_paid, rr.folio_id
            FROM reservations r
            JOIN reservation_rooms rr ON rr.tenant_id = r.tenant_id AND rr.reservation_id = r.id
            WHERE r.tenant_id = ?
              AND r.property_id = ?
              AND r.deleted_at IS NULL
        """
    }
}

private fun confirmationNumber(): String {
    return "RSV-" + UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
}

private fun guestNumber(): String {
    return "GST-" + UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
}

private fun String.normalizedRequired(field: String): String {
    return trim().takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("$field is required")
}

private fun String.trimmedOrNull(): String? {
    return trim().takeIf { it.isNotEmpty() }
}

private fun String.normalizedEmail(): String {
    val normalized = normalizedRequired("email").lowercase()
    require('@' in normalized) {
        "email must be valid"
    }
    return normalized
}

private fun BigDecimal.money(): BigDecimal {
    return setScale(2, RoundingMode.HALF_UP)
}

private fun BigDecimal.requireNonNegativeMoney(field: String): BigDecimal {
    val normalized = money()
    require(normalized >= BigDecimal.ZERO) {
        "$field must not be negative"
    }
    return normalized
}

private fun DataIntegrityViolationException.publicDatabaseMessage(): String {
    return "Reservation request conflicts with existing inventory or stay data"
}
