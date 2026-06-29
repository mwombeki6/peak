package com.mwombeki.peak.frontdesk.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.billing.api.BillingPort
import com.mwombeki.peak.frontdesk.api.CheckInRequest
import com.mwombeki.peak.frontdesk.api.CheckoutRequest
import com.mwombeki.peak.frontdesk.api.FrontDeskConflictException
import com.mwombeki.peak.frontdesk.api.FrontDeskInProgressException
import com.mwombeki.peak.frontdesk.api.FrontDeskMutationReceipt
import com.mwombeki.peak.frontdesk.api.FrontDeskNotFoundException
import com.mwombeki.peak.frontdesk.api.FrontDeskPort
import com.mwombeki.peak.frontdesk.api.StayResponse
import com.mwombeki.peak.frontdesk.api.WalkInRequest
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.reservations.api.ReservationPort
import com.mwombeki.peak.shared.context.TenantActor
import com.mwombeki.peak.shared.context.TenantRequestContext
import io.micrometer.core.instrument.MeterRegistry
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Service
class FrontDeskService(
    private val jdbcTemplate: JdbcTemplate,
    private val tenantRequestContext: TenantRequestContext,
    private val reservationPort: ReservationPort,
    private val billingPort: BillingPort,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : FrontDeskPort {

    override fun checkIn(
        propertyId: UUID,
        request: CheckInRequest,
    ): FrontDeskMutationReceipt {
        return mutate(
            propertyId = propertyId,
            operationType = "frontdesk.checkin",
            requestPayload = request,
            resourceType = STAYS,
            replayType = FrontDeskMutationReceipt::class.java,
        ) { actor, idempotencyKeyId ->
            checkInInternal(actor, propertyId, request.reservationId, request.roomId, idempotencyKeyId)
        }
    }

    override fun createWalkIn(
        propertyId: UUID,
        request: WalkInRequest,
    ): FrontDeskMutationReceipt {
        return mutate(
            propertyId = propertyId,
            operationType = "frontdesk.walkin.create",
            requestPayload = request,
            resourceType = STAYS,
            replayType = FrontDeskMutationReceipt::class.java,
        ) { actor, idempotencyKeyId ->
            val primaryGuestId = request.primaryGuestId
                ?: request.guest?.let {
                    reservationPort.createGuestInCurrentTransaction(actor.tenantId, propertyId, it).id
                }
                ?: throw IllegalArgumentException("Either primaryGuestId or guest is required")
            val reservationReceipt = reservationPort.createReservationInCurrentTransaction(
                tenantId = actor.tenantId,
                propertyId = propertyId,
                request = request.reservation.toReservationRequest(primaryGuestId),
                idempotencyKeyId = idempotencyKeyId,
            )
            checkInInternal(actor, propertyId, reservationReceipt.reservationId, request.reservation.roomId, idempotencyKeyId)
        }
    }

    override fun checkOut(
        propertyId: UUID,
        stayId: UUID,
        request: CheckoutRequest,
    ): FrontDeskMutationReceipt {
        return checkoutMutation(
            propertyId = propertyId,
            stayId = stayId,
            request = request,
            allowFiscalOverride = false,
            operationType = "frontdesk.checkout",
        )
    }

    override fun checkOutWithFiscalOverride(
        propertyId: UUID,
        stayId: UUID,
        request: CheckoutRequest,
    ): FrontDeskMutationReceipt {
        return checkoutMutation(
            propertyId = propertyId,
            stayId = stayId,
            request = request,
            allowFiscalOverride = true,
            operationType = "frontdesk.checkout.fiscal_override",
        )
    }

    override fun listStays(propertyId: UUID): List<StayResponse> {
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                STAY_SELECT + " ORDER BY s.check_in_time DESC NULLS LAST, s.created_at DESC LIMIT 500",
                ::mapStay,
                actor.tenantId,
                propertyId,
            )
        }
    }

    override fun getStay(propertyId: UUID, stayId: UUID): StayResponse? {
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                "$STAY_SELECT AND s.id = ?",
                ::mapStay,
                actor.tenantId,
                propertyId,
                stayId,
            ).singleOrNull()
        }
    }

    private fun checkInInternal(
        actor: TenantActor,
        propertyId: UUID,
        reservationId: UUID,
        requestedRoomId: UUID?,
        idempotencyKeyId: UUID,
    ): FrontDeskMutationReceipt {
        val reservation = requireReservationForCheckIn(actor.tenantId, propertyId, reservationId)
        val roomId = requestedRoomId ?: reservation.roomId
            ?: throw FrontDeskConflictException("Room assignment is required before check-in")
        requireRoomAssignable(actor.tenantId, propertyId, reservation.roomTypeId, roomId)
        val stayId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            UPDATE reservations
            SET status = 'checked_in',
                actual_check_in_at = now(),
                updated_at = now()
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
              AND status IN ('pending', 'confirmed')
            """.trimIndent(),
            actor.tenantId,
            propertyId,
            reservationId,
        )
        jdbcTemplate.update(
            """
            UPDATE reservation_rooms
            SET status = 'checked_in',
                room_id = ?,
                updated_at = now()
            WHERE tenant_id = ?
              AND reservation_id = ?
              AND id = ?
              AND status = 'reserved'
            """.trimIndent(),
            roomId,
            actor.tenantId,
            reservationId,
            reservation.reservationRoomId,
        )
        jdbcTemplate.update(
            """
            UPDATE reservation_room_nights
            SET room_id = ?, updated_at = now()
            WHERE tenant_id = ? AND reservation_room_id = ?
            """.trimIndent(),
            roomId,
            actor.tenantId,
            reservation.reservationRoomId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO stays (id, tenant_id, reservation_id, room_id, status, check_in_time)
            VALUES (?, ?, ?, ?, 'checked_in', now())
            """.trimIndent(),
            stayId,
            actor.tenantId,
            reservationId,
            roomId,
        )
        jdbcTemplate.update(
            """
            UPDATE rooms
            SET status = 'occupied',
                last_status_changed_at = now(),
                updated_at = now()
            WHERE tenant_id = ? AND property_id = ? AND id = ?
            """.trimIndent(),
            actor.tenantId,
            propertyId,
            roomId,
        )
        billingPort.postRoomChargeForReservation(actor.tenantId, propertyId, reservationId, idempotencyKeyId)
        recordSideEffects(
            tenantId = actor.tenantId,
            propertyId = propertyId,
            action = "frontdesk.checked_in",
            eventType = "frontdesk.checked_in",
            aggregateId = stayId,
            payload = mapOf(
                "propertyId" to propertyId,
                "reservationId" to reservationId,
                "stayId" to stayId,
                "roomId" to roomId,
            ),
            idempotencyKeyId = idempotencyKeyId,
        )
        return FrontDeskMutationReceipt(
            propertyId = propertyId,
            reservationId = reservationId,
            stayId = stayId,
            folioId = reservation.folioId,
            status = "checked_in",
            changed = true,
            replayed = false,
        )
    }

    private fun checkoutMutation(
        propertyId: UUID,
        stayId: UUID,
        request: CheckoutRequest,
        allowFiscalOverride: Boolean,
        operationType: String,
    ): FrontDeskMutationReceipt {
        return mutate(
            propertyId = propertyId,
            operationType = operationType,
            requestPayload = mapOf("stayId" to stayId, "request" to request),
            resourceType = STAYS,
            replayType = FrontDeskMutationReceipt::class.java,
        ) { actor, idempotencyKeyId ->
            val stay = requireStay(actor.tenantId, propertyId, stayId, lock = true)
            if (stay.status != "checked_in") {
                throw FrontDeskConflictException("Only checked-in stays can be checked out")
            }
            val financialState = billingPort.checkoutFinancialState(actor.tenantId, propertyId, stay.reservationId)
            if (financialState.balanceDue > java.math.BigDecimal.ZERO) {
                throw FrontDeskConflictException("Folio has an outstanding balance")
            }
            if (!financialState.hasIssuedInvoice) {
                throw FrontDeskConflictException("Checkout requires an issued invoice")
            }
            if (!financialState.hasAcceptedFiscalReceipt) {
                if (!allowFiscalOverride) {
                    throw FrontDeskConflictException("Checkout requires an accepted fiscal receipt")
                }
                request.reason?.normalizedRequired("reason")
                    ?: throw IllegalArgumentException("Fiscal override reason is required")
            }

            jdbcTemplate.update(
                """
                UPDATE stays
                SET status = 'checked_out',
                    check_out_time = now(),
                    updated_at = now()
                WHERE tenant_id = ?
                  AND id = ?
                  AND status = 'checked_in'
                """.trimIndent(),
                actor.tenantId,
                stayId,
            )
            jdbcTemplate.update(
                """
                UPDATE reservations
                SET status = 'checked_out',
                    actual_check_out_at = now(),
                    updated_at = now()
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND id = ?
                """.trimIndent(),
                actor.tenantId,
                propertyId,
                stay.reservationId,
            )
            jdbcTemplate.update(
                """
                UPDATE reservation_rooms
                SET status = 'checked_out', updated_at = now()
                WHERE tenant_id = ? AND reservation_id = ? AND status = 'checked_in'
                """.trimIndent(),
                actor.tenantId,
                stay.reservationId,
            )
            jdbcTemplate.update(
                """
                UPDATE rooms
                SET status = 'vacant_dirty',
                    last_status_changed_at = now(),
                    updated_at = now()
                WHERE tenant_id = ? AND property_id = ? AND id = ?
                """.trimIndent(),
                actor.tenantId,
                propertyId,
                stay.roomId,
            )
            billingPort.closeFolio(actor.tenantId, propertyId, financialState.folioId)
            val eventType = if (allowFiscalOverride && !financialState.hasAcceptedFiscalReceipt) {
                "frontdesk.checked_out_with_fiscal_override"
            } else {
                "frontdesk.checked_out"
            }
            recordSideEffects(
                tenantId = actor.tenantId,
                propertyId = propertyId,
                action = eventType,
                eventType = eventType,
                aggregateId = stayId,
                payload = mapOf(
                    "propertyId" to propertyId,
                    "reservationId" to stay.reservationId,
                    "stayId" to stayId,
                    "folioId" to financialState.folioId,
                    "fiscalOverride" to (allowFiscalOverride && !financialState.hasAcceptedFiscalReceipt),
                    "reason" to request.reason,
                ),
                idempotencyKeyId = idempotencyKeyId,
            )
            FrontDeskMutationReceipt(
                propertyId = propertyId,
                reservationId = stay.reservationId,
                stayId = stayId,
                folioId = financialState.folioId,
                status = "checked_out",
                changed = true,
                replayed = false,
            )
        }
    }

    private fun requireReservationForCheckIn(
        tenantId: UUID,
        propertyId: UUID,
        reservationId: UUID,
    ): ReservationCheckInRecord {
        return jdbcTemplate.query(
            """
            SELECT r.status, r.check_in_date, r.check_out_date, rr.id AS reservation_room_id,
                   rr.room_type_id, rr.room_id, rr.folio_id
            FROM reservations r
            JOIN reservation_rooms rr ON rr.tenant_id = r.tenant_id AND rr.reservation_id = r.id
            WHERE r.tenant_id = ?
              AND r.property_id = ?
              AND r.id = ?
              AND r.deleted_at IS NULL
            FOR UPDATE OF r, rr
            """.trimIndent(),
            { rs, _ ->
                ReservationCheckInRecord(
                    status = rs.getString("status"),
                    checkInDate = rs.getObject("check_in_date", LocalDate::class.java),
                    checkOutDate = rs.getObject("check_out_date", LocalDate::class.java),
                    reservationRoomId = rs.getObject("reservation_room_id", UUID::class.java),
                    roomTypeId = rs.getObject("room_type_id", UUID::class.java),
                    roomId = rs.getObject("room_id", UUID::class.java),
                    folioId = rs.getObject("folio_id", UUID::class.java),
                )
            },
            tenantId,
            propertyId,
            reservationId,
        ).singleOrNull()?.also {
            if (it.status !in setOf("pending", "confirmed")) {
                throw FrontDeskConflictException("Reservation is ${it.status} and cannot be checked in")
            }
            if (it.folioId == null) {
                throw FrontDeskConflictException("Reservation does not have an open folio")
            }
        } ?: throw FrontDeskNotFoundException("Reservation was not found")
    }

    private fun requireRoomAssignable(
        tenantId: UUID,
        propertyId: UUID,
        roomTypeId: UUID,
        roomId: UUID,
    ) {
        val exists = jdbcTemplate.queryForObject(
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
            Boolean::class.java,
            tenantId,
            propertyId,
            roomTypeId,
            roomId,
        ) == true
        if (!exists) {
            throw FrontDeskConflictException("Room is not assignable for check-in")
        }
    }

    private fun requireStay(
        tenantId: UUID,
        propertyId: UUID,
        stayId: UUID,
        lock: Boolean,
    ): StayRecord {
        return jdbcTemplate.query(
            """
            SELECT s.id, s.reservation_id, s.room_id, s.status, rr.folio_id
            FROM stays s
            JOIN reservations r ON r.tenant_id = s.tenant_id AND r.id = s.reservation_id
            JOIN reservation_rooms rr ON rr.tenant_id = r.tenant_id AND rr.reservation_id = r.id
            WHERE s.tenant_id = ?
              AND r.property_id = ?
              AND s.id = ?
            ${if (lock) "FOR UPDATE OF s" else ""}
            """.trimIndent(),
            { rs, _ ->
                StayRecord(
                    id = rs.getObject("id", UUID::class.java),
                    reservationId = rs.getObject("reservation_id", UUID::class.java),
                    roomId = rs.getObject("room_id", UUID::class.java),
                    status = rs.getString("status"),
                    folioId = rs.getObject("folio_id", UUID::class.java),
                )
            },
            tenantId,
            propertyId,
            stayId,
        ).singleOrNull() ?: throw FrontDeskNotFoundException("Stay was not found")
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
                            meterRegistry.counter("peak.frontdesk.command", "operation", operationType, "result", "succeeded").increment()
                            response
                        } catch (ex: DataIntegrityViolationException) {
                            throw FrontDeskConflictException(ex.publicDatabaseMessage())
                        }
                    }

                    is IdempotencyReservation.Replay -> {
                        if (reservation.responseBody.isNullOrBlank()) {
                            throw FrontDeskConflictException("Frontdesk command replay does not contain a stored response body")
                        }
                        objectMapper.readValue(reservation.responseBody, replayType).withReplayFlag()
                    }

                    is IdempotencyReservation.InProgress -> {
                        meterRegistry.counter("peak.frontdesk.command", "operation", operationType, "result", "in_progress").increment()
                        throw FrontDeskInProgressException("Frontdesk command is already being processed for this idempotency key")
                    }

                    is IdempotencyReservation.Conflict -> {
                        meterRegistry.counter("peak.frontdesk.command", "operation", operationType, "result", "conflict").increment()
                        throw FrontDeskConflictException("Idempotency key was already used for a different frontdesk request")
                    }
                }
            },
        )
    }

    private fun <T> read(propertyId: UUID, block: (TenantActor) -> T): T {
        return requireNotNull(transactionTemplate.execute { block(bindActor(propertyId)) })
    }

    private fun bindActor(propertyId: UUID): TenantActor {
        val actor = tenantRequestContext.bind()
        tenantRequestContext.requirePropertyUsable(actor.tenantId, propertyId)
        return actor
    }

    private fun recordSideEffects(
        tenantId: UUID,
        propertyId: UUID,
        action: String,
        eventType: String,
        aggregateId: UUID,
        payload: Map<String, Any?>,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = action,
                resource = AuditResource(STAYS, aggregateId),
                after = payload,
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = STAYS,
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

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> T.withReplayFlag(): T {
        return when (this) {
            is FrontDeskMutationReceipt -> copy(replayed = true) as T
            else -> this
        }
    }

    private fun resourceId(response: Any): UUID? {
        return when (response) {
            is FrontDeskMutationReceipt -> response.stayId ?: response.reservationId
            else -> null
        }
    }

    private fun mapStay(rs: ResultSet, rowNumber: Int): StayResponse {
        return StayResponse(
            id = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            reservationId = rs.getObject("reservation_id", UUID::class.java),
            roomId = rs.getObject("room_id", UUID::class.java),
            status = rs.getString("status"),
            checkInTime = rs.getTimestamp("check_in_time")?.toInstant(),
            checkOutTime = rs.getTimestamp("check_out_time")?.toInstant(),
            folioId = rs.getObject("folio_id", UUID::class.java),
        )
    }

    private data class ReservationCheckInRecord(
        val status: String,
        val checkInDate: LocalDate,
        val checkOutDate: LocalDate,
        val reservationRoomId: UUID,
        val roomTypeId: UUID,
        val roomId: UUID?,
        val folioId: UUID?,
    )

    private data class StayRecord(
        val id: UUID,
        val reservationId: UUID,
        val roomId: UUID,
        val status: String,
        val folioId: UUID?,
    )

    private companion object {
        const val STAYS = "stays"
        const val STAY_SELECT = """
            SELECT s.id, s.tenant_id, r.property_id, s.reservation_id, s.room_id, s.status,
                   s.check_in_time, s.check_out_time, rr.folio_id
            FROM stays s
            JOIN reservations r ON r.tenant_id = s.tenant_id AND r.id = s.reservation_id
            LEFT JOIN reservation_rooms rr ON rr.tenant_id = r.tenant_id AND rr.reservation_id = r.id
            WHERE s.tenant_id = ?
              AND r.property_id = ?
        """
    }
}

private fun String.normalizedRequired(field: String): String {
    return trim().takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("$field is required")
}

private fun DataIntegrityViolationException.publicDatabaseMessage(): String {
    return mostSpecificCause.message
        ?.lineSequence()
        ?.firstOrNull()
        ?.take(240)
        ?: "Frontdesk request violates a database constraint"
}
