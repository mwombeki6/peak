package com.mwombeki.peak.reservations.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.reservations.api.AddReservationGuestRequest
import com.mwombeki.peak.reservations.api.GuestIdentityDocumentResponse
import com.mwombeki.peak.reservations.api.GuestIdentityDocumentType
import com.mwombeki.peak.reservations.api.GuestIdentityIncompleteException
import com.mwombeki.peak.reservations.api.GuestIdentityPort
import com.mwombeki.peak.reservations.api.GuestIdentityProviderCommand
import com.mwombeki.peak.reservations.api.GuestIdentityProviderResult
import com.mwombeki.peak.reservations.api.GuestIdentityReadinessPort
import com.mwombeki.peak.reservations.api.GuestIdentityVerificationProvider
import com.mwombeki.peak.reservations.api.GuestIdentityVerificationReceipt
import com.mwombeki.peak.reservations.api.GuestIdentityVerificationStatus
import com.mwombeki.peak.reservations.api.GuestResponse
import com.mwombeki.peak.reservations.api.ManualGuestIdentityVerificationRequest
import com.mwombeki.peak.reservations.api.ReservationConflictException
import com.mwombeki.peak.reservations.api.ReservationGuestRelationship
import com.mwombeki.peak.reservations.api.ReservationGuestResponse
import com.mwombeki.peak.reservations.api.ReservationIdentityReadinessResponse
import com.mwombeki.peak.reservations.api.ReservationInProgressException
import com.mwombeki.peak.reservations.api.ReservationNotFoundException
import com.mwombeki.peak.reservations.api.RevokeGuestIdentityRequest
import com.mwombeki.peak.reservations.api.UpdateGuestRequest
import com.mwombeki.peak.reservations.api.VerifyGuestIdentityRequest
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.TenantActor
import com.mwombeki.peak.shared.context.TenantRequestContext
import io.micrometer.core.instrument.MeterRegistry
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Service
class GuestIdentityService(
    private val jdbcTemplate: JdbcTemplate,
    private val tenantRequestContext: TenantRequestContext,
    private val requestContextHolder: RequestContextHolder,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val numberHasher: GuestIdentityNumberHasher,
    private val properties: GuestIdentityProperties,
    private val policy: GuestIdentityPolicy,
    private val verificationProvider: GuestIdentityVerificationProvider,
    private val meterRegistry: MeterRegistry,
) : GuestIdentityPort, GuestIdentityReadinessPort {

    override fun updateGuest(
        propertyId: UUID,
        guestId: UUID,
        request: UpdateGuestRequest,
    ): GuestResponse {
        request.dateOfBirth?.let {
            require(!it.isAfter(LocalDate.now())) { "dateOfBirth cannot be in the future" }
        }
        return mutate(
            propertyId = propertyId,
            operationType = "reservations.guest.update",
            requestPayload = mapOf("guestId" to guestId, "request" to request),
            resourceType = GUESTS,
            replayType = GuestResponse::class.java,
        ) { actor, idempotencyKeyId ->
            requireGuest(actor.tenantId, propertyId, guestId, lock = true)
            jdbcTemplate.update(
                """
                UPDATE guests
                SET full_name = COALESCE(NULLIF(btrim(?), ''), full_name),
                    first_name = COALESCE(NULLIF(btrim(?), ''), first_name),
                    last_name = COALESCE(NULLIF(btrim(?), ''), last_name),
                    date_of_birth = COALESCE(?, date_of_birth),
                    nationality = COALESCE(NULLIF(upper(btrim(?)), ''), nationality),
                    email = COALESCE(NULLIF(lower(btrim(?)), ''), email),
                    phone_primary = COALESCE(NULLIF(btrim(?), ''), phone_primary),
                    updated_at = now()
                WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                """.trimIndent(),
                request.fullName,
                request.firstName,
                request.lastName,
                request.dateOfBirth,
                request.nationality,
                request.email,
                request.phonePrimary,
                actor.tenantId,
                guestId,
            )
            if (request.changesIdentityAttributes()) {
                jdbcTemplate.update(
                    """
                    UPDATE guest_documents
                    SET verified = false,
                        verified_by = NULL,
                        verified_at = NULL,
                        verification_status = 'unverified',
                        provider_reference = NULL,
                        verification_expires_at = NULL,
                        updated_at = now()
                    WHERE tenant_id = ? AND guest_id = ? AND verification_status = 'verified'
                    """.trimIndent(),
                    actor.tenantId,
                    guestId,
                )
            }
            val response = requireGuest(actor.tenantId, propertyId, guestId)
            recordSideEffects(
                actor = actor,
                propertyId = propertyId,
                action = "reservations.guest.updated",
                eventType = "reservations.guest.updated",
                aggregateType = GUESTS,
                aggregateId = guestId,
                payload = mapOf("propertyId" to propertyId, "guestId" to guestId),
                idempotencyKeyId = idempotencyKeyId,
            )
            response
        }
    }

    override fun listDocuments(propertyId: UUID, guestId: UUID): List<GuestIdentityDocumentResponse> {
        return read(propertyId) { actor ->
            requireGuest(actor.tenantId, propertyId, guestId)
            jdbcTemplate.query(
                "$DOCUMENT_SELECT WHERE gd.tenant_id = ? AND gd.guest_id = ? ORDER BY gd.created_at DESC",
                ::mapDocument,
                actor.tenantId,
                guestId,
            )
        }
    }

    override fun verifyIdentity(
        propertyId: UUID,
        guestId: UUID,
        request: VerifyGuestIdentityRequest,
    ): GuestIdentityVerificationReceipt {
        require(request.documentType == GuestIdentityDocumentType.NIDA) {
            "Online verification currently supports NIDA documents only"
        }
        validateDocumentInput(request.documentNumber, request.issuingCountry, request.expiresAt)
        val prepared = prepareOnlineVerification(propertyId, guestId, request)
        if (prepared is VerificationPreparation.Replay) {
            return prepared.receipt.copy(replayed = true)
        }
        prepared as VerificationPreparation.Started
        val providerStartedAt = System.nanoTime()
        val providerResult = try {
            verificationProvider.verify(
                GuestIdentityProviderCommand(
                    documentType = request.documentType,
                    documentNumber = request.documentNumber,
                    fullName = prepared.guest.fullName,
                    dateOfBirth = requireNotNull(prepared.guest.dateOfBirth),
                    nationality = requireNotNull(prepared.guest.nationality),
                    correlationId = requestContextHolder.current().correlationId,
                ),
            )
        } catch (_: Exception) {
            GuestIdentityProviderResult.Unavailable("NIDA_PROVIDER_ERROR")
        }
        meterRegistry.timer(
            "peak.guest.identity.provider.latency",
            "provider",
            verificationProvider.providerId,
        ).record(Duration.ofNanos(System.nanoTime() - providerStartedAt))
        return completeOnlineVerification(prepared, providerResult)
    }

    override fun manuallyVerifyIdentity(
        propertyId: UUID,
        guestId: UUID,
        request: ManualGuestIdentityVerificationRequest,
    ): GuestIdentityVerificationReceipt {
        validateDocumentInput(request.documentNumber, request.issuingCountry, request.expiresAt)
        require(request.attestationReason.trim().length in 10..500) {
            "attestationReason must contain between 10 and 500 characters"
        }
        return mutate(
            propertyId = propertyId,
            operationType = "reservations.guest_identity.manual_verify",
            requestPayload = request.identitySafePayload(guestId),
            resourceType = GUEST_DOCUMENTS,
            replayType = GuestIdentityVerificationReceipt::class.java,
            markReplay = { it.copy(replayed = true) },
        ) { actor, idempotencyKeyId ->
            requireGuestProfileForVerification(actor.tenantId, propertyId, guestId)
            val documentId = upsertDocument(
                actor = actor,
                guestId = guestId,
                documentType = request.documentType,
                documentNumber = request.documentNumber,
                issuingCountry = request.issuingCountry,
                issuingAuthority = request.issuingAuthority,
                issuedAt = request.issuedAt,
                expiresAt = request.expiresAt,
                status = GuestIdentityVerificationStatus.VERIFIED,
                method = PHYSICAL_DOCUMENT,
                provider = PHYSICAL_DOCUMENT,
            )
            val attemptId = insertAttempt(
                actor = actor,
                propertyId = propertyId,
                guestId = guestId,
                documentId = documentId,
                method = PHYSICAL_DOCUMENT,
                provider = PHYSICAL_DOCUMENT,
                status = "verified",
                failureCode = null,
                attestationReason = request.attestationReason.trim(),
                idempotencyKeyId = idempotencyKeyId,
                complete = true,
            )
            val document = requireDocument(actor.tenantId, guestId, documentId)
            val receipt = GuestIdentityVerificationReceipt(
                attemptId = attemptId,
                document = document,
                failureCode = null,
                changed = true,
                replayed = false,
            )
            recordIdentityResult(actor, propertyId, documentId, attemptId, "verified", PHYSICAL_DOCUMENT, idempotencyKeyId)
            meterRegistry.counter(VERIFICATION_METRIC, "provider", PHYSICAL_DOCUMENT, "result", "verified").increment()
            receipt
        }
    }

    override fun revokeIdentity(
        propertyId: UUID,
        guestId: UUID,
        documentId: UUID,
        request: RevokeGuestIdentityRequest,
    ): GuestIdentityDocumentResponse {
        require(request.reason.trim().length in 5..500) {
            "Revocation reason must contain between 5 and 500 characters"
        }
        return mutate(
            propertyId = propertyId,
            operationType = "reservations.guest_identity.revoke",
            requestPayload = mapOf("guestId" to guestId, "documentId" to documentId, "reason" to request.reason),
            resourceType = GUEST_DOCUMENTS,
            replayType = GuestIdentityDocumentResponse::class.java,
        ) { actor, idempotencyKeyId ->
            requireGuest(actor.tenantId, propertyId, guestId)
            requireDocument(actor.tenantId, guestId, documentId)
            jdbcTemplate.update(
                """
                UPDATE guest_documents
                SET verified = false,
                    verification_status = 'revoked',
                    revoked_at = now(),
                    revoked_by = ?,
                    revocation_reason = ?,
                    updated_at = now()
                WHERE tenant_id = ? AND guest_id = ? AND id = ?
                """.trimIndent(),
                actor.tenantUserId,
                request.reason.trim(),
                actor.tenantId,
                guestId,
                documentId,
            )
            val document = requireDocument(actor.tenantId, guestId, documentId)
            recordSideEffects(
                actor = actor,
                propertyId = propertyId,
                action = "reservations.guest_identity.revoked",
                eventType = "reservations.guest_identity.revoked",
                aggregateType = GUEST_DOCUMENTS,
                aggregateId = documentId,
                payload = mapOf("propertyId" to propertyId, "guestId" to guestId, "documentId" to documentId),
                idempotencyKeyId = idempotencyKeyId,
            )
            document
        }
    }

    override fun addReservationGuest(
        propertyId: UUID,
        reservationId: UUID,
        request: AddReservationGuestRequest,
    ): ReservationGuestResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "reservations.occupant.add",
            requestPayload = mapOf("reservationId" to reservationId, "request" to request),
            resourceType = RESERVATION_GUESTS,
            replayType = ReservationGuestResponse::class.java,
        ) { actor, idempotencyKeyId ->
            val reservation = requireEditableReservation(actor.tenantId, propertyId, reservationId)
            val guest = requireGuest(actor.tenantId, propertyId, request.guestId)
            val guardianRequired = request.relationship != ReservationGuestRelationship.ADULT
            require(guardianRequired == (request.guardianGuestId != null && request.guardianAttestation)) {
                "Child and dependent occupants require an attested guardian; adults cannot have one"
            }
            request.guardianGuestId?.let { guardianId ->
                require(guardianId != request.guestId) { "An occupant cannot be their own guardian" }
                val guardianExists = jdbcTemplate.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM reservation_guests
                        WHERE tenant_id = ? AND reservation_id = ? AND guest_id = ?
                    )
                    """.trimIndent(),
                    Boolean::class.java,
                    actor.tenantId,
                    reservationId,
                    guardianId,
                ) == true
                require(guardianExists) { "Guardian must already be attached to the reservation" }
            }
            val currentCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservation_guests WHERE tenant_id = ? AND reservation_id = ?",
                Int::class.java,
                actor.tenantId,
                reservationId,
            ) ?: 0
            if (currentCount >= reservation.adults + reservation.children) {
                throw ReservationConflictException("Reservation occupant capacity has been reached")
            }
            jdbcTemplate.update(
                """
                INSERT INTO reservation_guests (
                    tenant_id, reservation_id, guest_id, is_primary, relationship_type,
                    guardian_guest_id, guardian_attested_at, guardian_attested_by
                )
                VALUES (?, ?, ?, false, ?, ?, ?, ?)
                """.trimIndent(),
                actor.tenantId,
                reservationId,
                request.guestId,
                request.relationship.databaseValue,
                request.guardianGuestId,
                request.guardianGuestId?.let { Timestamp.from(java.time.Instant.now()) },
                request.guardianGuestId?.let { actor.tenantUserId },
            )
            val response = ReservationGuestResponse(
                guestId = guest.id,
                fullName = guest.fullName,
                primary = false,
                relationship = request.relationship,
                guardianGuestId = request.guardianGuestId,
            )
            recordSideEffects(
                actor = actor,
                propertyId = propertyId,
                action = "reservations.occupant.added",
                eventType = "reservations.occupant.added",
                aggregateType = RESERVATIONS,
                aggregateId = reservationId,
                payload = mapOf("propertyId" to propertyId, "reservationId" to reservationId, "guestId" to request.guestId),
                idempotencyKeyId = idempotencyKeyId,
            )
            response
        }
    }

    override fun removeReservationGuest(propertyId: UUID, reservationId: UUID, guestId: UUID) {
        mutate(
            propertyId = propertyId,
            operationType = "reservations.occupant.remove",
            requestPayload = mapOf("reservationId" to reservationId, "guestId" to guestId),
            resourceType = RESERVATION_GUESTS,
            replayType = MutationAcknowledgement::class.java,
        ) { actor, idempotencyKeyId ->
            requireEditableReservation(actor.tenantId, propertyId, reservationId)
            val removed = jdbcTemplate.update(
                """
                DELETE FROM reservation_guests
                WHERE tenant_id = ? AND reservation_id = ? AND guest_id = ? AND is_primary = false
                """.trimIndent(),
                actor.tenantId,
                reservationId,
                guestId,
            )
            if (removed == 0) {
                val primary = jdbcTemplate.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM reservation_guests
                        WHERE tenant_id = ? AND reservation_id = ? AND guest_id = ? AND is_primary = true
                    )
                    """.trimIndent(),
                    Boolean::class.java,
                    actor.tenantId,
                    reservationId,
                    guestId,
                ) == true
                if (primary) {
                    throw ReservationConflictException("Primary guest cannot be removed")
                }
                throw ReservationNotFoundException("Reservation occupant was not found")
            }
            recordSideEffects(
                actor = actor,
                propertyId = propertyId,
                action = "reservations.occupant.removed",
                eventType = "reservations.occupant.removed",
                aggregateType = RESERVATIONS,
                aggregateId = reservationId,
                payload = mapOf("propertyId" to propertyId, "reservationId" to reservationId, "guestId" to guestId),
                idempotencyKeyId = idempotencyKeyId,
            )
            MutationAcknowledgement(true)
        }
    }

    override fun listReservationGuests(
        propertyId: UUID,
        reservationId: UUID,
    ): List<ReservationGuestResponse> {
        return read(propertyId) { actor ->
            val exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM reservations
                    WHERE tenant_id = ? AND property_id = ? AND id = ? AND deleted_at IS NULL
                )
                """.trimIndent(),
                Boolean::class.java,
                actor.tenantId,
                propertyId,
                reservationId,
            ) == true
            if (!exists) {
                throw ReservationNotFoundException("Reservation was not found")
            }
            jdbcTemplate.query(
                """
                SELECT rg.guest_id, g.full_name, rg.is_primary, rg.relationship_type,
                       rg.guardian_guest_id
                FROM reservation_guests rg
                JOIN guests g ON g.tenant_id = rg.tenant_id AND g.id = rg.guest_id
                WHERE rg.tenant_id = ? AND rg.reservation_id = ? AND g.deleted_at IS NULL
                ORDER BY rg.is_primary DESC, rg.created_at
                """.trimIndent(),
                { rs, _ ->
                    ReservationGuestResponse(
                        guestId = rs.getObject("guest_id", UUID::class.java),
                        fullName = rs.getString("full_name"),
                        primary = rs.getBoolean("is_primary"),
                        relationship = ReservationGuestRelationship.valueOf(
                            rs.getString("relationship_type").uppercase(),
                        ),
                        guardianGuestId = rs.getObject("guardian_guest_id", UUID::class.java),
                    )
                },
                actor.tenantId,
                reservationId,
            )
        }
    }

    override fun identityReadiness(
        propertyId: UUID,
        reservationId: UUID,
    ): ReservationIdentityReadinessResponse {
        return read(propertyId) { actor ->
            evaluateReadiness(actor.tenantId, propertyId, reservationId, lock = false)
        }
    }

    override fun requireReadyInCurrentTransaction(
        tenantId: UUID,
        propertyId: UUID,
        reservationId: UUID,
    ) {
        val readiness = evaluateReadiness(tenantId, propertyId, reservationId, lock = true)
        if (!readiness.ready) {
            meterRegistry.counter("peak.frontdesk.checkin.identity", "result", "blocked").increment()
            val reasonCodes = (readiness.reasons + readiness.occupants.flatMap { it.reasons }).distinct()
            throw GuestIdentityIncompleteException(
                "Guest identity is incomplete: ${reasonCodes.joinToString(",")}",
            )
        }
        meterRegistry.counter("peak.frontdesk.checkin.identity", "result", "ready").increment()
    }

    private fun prepareOnlineVerification(
        propertyId: UUID,
        guestId: UUID,
        request: VerifyGuestIdentityRequest,
    ): VerificationPreparation {
        return requireNotNull(transactionTemplate.execute {
            val actor = bindActor(propertyId)
            val reservation = idempotencyPort.reserve(
                IdempotencyCommand(
                    operationType = "reservations.guest_identity.verify",
                    requestPayload = request.identitySafePayload(guestId),
                    resourceType = GUEST_DOCUMENTS,
                ),
            )
            when (reservation) {
                is IdempotencyReservation.Started -> {
                    val guest = requireGuestProfileForVerification(actor.tenantId, propertyId, guestId)
                    val documentId = upsertDocument(
                        actor = actor,
                        guestId = guestId,
                        documentType = request.documentType,
                        documentNumber = request.documentNumber,
                        issuingCountry = request.issuingCountry,
                        issuingAuthority = request.issuingAuthority,
                        issuedAt = request.issuedAt,
                        expiresAt = request.expiresAt,
                        status = GuestIdentityVerificationStatus.PENDING,
                        method = NIDA_CIG,
                        provider = verificationProvider.providerId,
                    )
                    val attemptId = insertAttempt(
                        actor = actor,
                        propertyId = propertyId,
                        guestId = guestId,
                        documentId = documentId,
                        method = NIDA_CIG,
                        provider = verificationProvider.providerId,
                        status = "pending",
                        failureCode = null,
                        attestationReason = null,
                        idempotencyKeyId = reservation.recordId,
                        complete = false,
                    )
                    VerificationPreparation.Started(
                        actor = actor,
                        propertyId = propertyId,
                        guest = guest,
                        documentId = documentId,
                        attemptId = attemptId,
                        idempotencyKeyId = reservation.recordId,
                    )
                }

                is IdempotencyReservation.Replay -> {
                    if (reservation.responseBody.isNullOrBlank()) {
                        throw ReservationConflictException("Identity verification replay has no response")
                    }
                    VerificationPreparation.Replay(
                        objectMapper.readValue(
                            reservation.responseBody,
                            GuestIdentityVerificationReceipt::class.java,
                        ),
                    )
                }

                is IdempotencyReservation.InProgress -> {
                    throw ReservationInProgressException("Identity verification is already in progress")
                }

                is IdempotencyReservation.Conflict -> {
                    throw ReservationConflictException("Idempotency key was used for a different identity request")
                }
            }
        })
    }

    private fun completeOnlineVerification(
        prepared: VerificationPreparation.Started,
        result: GuestIdentityProviderResult,
    ): GuestIdentityVerificationReceipt {
        return requireNotNull(transactionTemplate.execute {
            val actor = bindActor(prepared.propertyId)
            require(actor == prepared.actor) { "Identity verification actor changed before completion" }
            val outcome = when (result) {
                is GuestIdentityProviderResult.Verified -> VerificationOutcome(
                    documentStatus = GuestIdentityVerificationStatus.VERIFIED,
                    attemptStatus = "verified",
                    providerReference = result.providerReference,
                    failureCode = null,
                    verificationExpiresAt = result.expiresAt,
                )

                is GuestIdentityProviderResult.Rejected -> VerificationOutcome(
                    documentStatus = GuestIdentityVerificationStatus.FAILED,
                    attemptStatus = "rejected",
                    providerReference = result.providerReference,
                    failureCode = result.failureCode,
                    verificationExpiresAt = null,
                )

                is GuestIdentityProviderResult.Unavailable -> VerificationOutcome(
                    documentStatus = GuestIdentityVerificationStatus.FAILED,
                    attemptStatus = "unavailable",
                    providerReference = null,
                    failureCode = result.failureCode,
                    verificationExpiresAt = null,
                )
            }
            jdbcTemplate.update(
                """
                UPDATE guest_documents
                SET verified = ?,
                    verified_by = CASE WHEN ? THEN ? ELSE NULL END,
                    verified_at = CASE WHEN ? THEN now() ELSE NULL END,
                    verification_status = ?,
                    provider_reference = ?,
                    verification_expires_at = ?,
                    updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """.trimIndent(),
                outcome.documentStatus == GuestIdentityVerificationStatus.VERIFIED,
                outcome.documentStatus == GuestIdentityVerificationStatus.VERIFIED,
                actor.tenantUserId,
                outcome.documentStatus == GuestIdentityVerificationStatus.VERIFIED,
                outcome.documentStatus.databaseValue,
                outcome.providerReference,
                outcome.verificationExpiresAt?.let(Timestamp::from),
                actor.tenantId,
                prepared.documentId,
            )
            jdbcTemplate.update(
                """
                UPDATE guest_identity_verification_attempts
                SET status = ?, provider_reference = ?, failure_code = ?,
                    completed_by = ?, completed_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'pending'
                """.trimIndent(),
                outcome.attemptStatus,
                outcome.providerReference,
                outcome.failureCode,
                actor.tenantUserId,
                actor.tenantId,
                prepared.attemptId,
            )
            val receipt = GuestIdentityVerificationReceipt(
                attemptId = prepared.attemptId,
                document = requireDocument(actor.tenantId, prepared.guest.id, prepared.documentId),
                failureCode = outcome.failureCode,
                changed = true,
                replayed = false,
            )
            recordIdentityResult(
                actor,
                prepared.propertyId,
                prepared.documentId,
                prepared.attemptId,
                outcome.attemptStatus,
                verificationProvider.providerId,
                prepared.idempotencyKeyId,
            )
            idempotencyPort.markSucceeded(
                prepared.idempotencyKeyId,
                200,
                receipt,
                prepared.documentId,
            )
            meterRegistry.counter(
                VERIFICATION_METRIC,
                "provider",
                verificationProvider.providerId,
                "result",
                outcome.attemptStatus,
            ).increment()
            receipt
        })
    }

    private fun upsertDocument(
        actor: TenantActor,
        guestId: UUID,
        documentType: GuestIdentityDocumentType,
        documentNumber: String,
        issuingCountry: String?,
        issuingAuthority: String?,
        issuedAt: LocalDate?,
        expiresAt: LocalDate?,
        status: GuestIdentityVerificationStatus,
        method: String,
        provider: String,
    ): UUID {
        val fingerprint = numberHasher.fingerprint(documentType.databaseValue, documentNumber)
        val candidateFingerprints = numberHasher.candidateFingerprints(
            documentType.databaseValue,
            documentNumber,
        )
        val fingerprintPlaceholders = candidateFingerprints.joinToString(",") { "?" }
        val fingerprintArguments = arrayOf<Any>(
            actor.tenantId,
            documentType.databaseValue,
            *candidateFingerprints.toTypedArray(),
        )
        val existing = jdbcTemplate.query(
            """
            SELECT id, guest_id
            FROM guest_documents
            WHERE tenant_id = ? AND document_type = ?
              AND document_number_hmac IN ($fingerprintPlaceholders)
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                rs.getObject("id", UUID::class.java) to rs.getObject("guest_id", UUID::class.java)
            },
            *fingerprintArguments,
        ).singleOrNull()
        if (existing != null && existing.second != guestId) {
            throw ReservationConflictException("Identity document is already assigned to another guest")
        }
        val documentId = existing?.first ?: UUID.randomUUID()
        if (existing == null) {
            jdbcTemplate.update(
                """
                INSERT INTO guest_documents (
                    id, tenant_id, guest_id, document_type, document_number,
                    document_number_hmac, document_number_hmac_key_version, document_number_last4,
                    issuing_country, issuing_authority, issued_at, expires_at,
                    verified, verified_by, verified_at, verification_status,
                    verification_method, verification_provider
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                documentId,
                actor.tenantId,
                guestId,
                documentType.databaseValue,
                numberHasher.masked(documentNumber),
                fingerprint,
                properties.hashKeyVersion,
                numberHasher.lastFour(documentNumber),
                issuingCountry?.uppercase(),
                issuingAuthority?.trim(),
                issuedAt,
                expiresAt,
                status == GuestIdentityVerificationStatus.VERIFIED,
                actor.tenantUserId.takeIf { status == GuestIdentityVerificationStatus.VERIFIED },
                Timestamp.from(java.time.Instant.now()).takeIf { status == GuestIdentityVerificationStatus.VERIFIED },
                status.databaseValue,
                method,
                provider,
            )
        } else {
            jdbcTemplate.update(
                """
                UPDATE guest_documents
                SET document_number = ?,
                    document_number_hmac = ?,
                    document_number_hmac_key_version = ?,
                    document_number_last4 = ?,
                    issuing_country = ?,
                    issuing_authority = ?,
                    issued_at = ?,
                    expires_at = ?,
                    verified = ?,
                    verified_by = ?,
                    verified_at = ?,
                    verification_status = ?,
                    verification_method = ?,
                    verification_provider = ?,
                    provider_reference = NULL,
                    verification_expires_at = NULL,
                    revoked_at = NULL,
                    revoked_by = NULL,
                    revocation_reason = NULL,
                    updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """.trimIndent(),
                numberHasher.masked(documentNumber),
                fingerprint,
                properties.hashKeyVersion,
                numberHasher.lastFour(documentNumber),
                issuingCountry?.uppercase(),
                issuingAuthority?.trim(),
                issuedAt,
                expiresAt,
                status == GuestIdentityVerificationStatus.VERIFIED,
                actor.tenantUserId.takeIf { status == GuestIdentityVerificationStatus.VERIFIED },
                Timestamp.from(java.time.Instant.now()).takeIf { status == GuestIdentityVerificationStatus.VERIFIED },
                status.databaseValue,
                method,
                provider,
                actor.tenantId,
                documentId,
            )
        }
        return documentId
    }

    private fun insertAttempt(
        actor: TenantActor,
        propertyId: UUID,
        guestId: UUID,
        documentId: UUID,
        method: String,
        provider: String,
        status: String,
        failureCode: String?,
        attestationReason: String?,
        idempotencyKeyId: UUID,
        complete: Boolean,
    ): UUID {
        val attemptId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO guest_identity_verification_attempts (
                id, tenant_id, property_id, guest_id, guest_document_id,
                verification_method, provider, status, failure_code, attestation_reason,
                requested_by, completed_by, idempotency_key_id, completed_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            attemptId,
            actor.tenantId,
            propertyId,
            guestId,
            documentId,
            method,
            provider,
            status,
            failureCode,
            attestationReason,
            actor.tenantUserId,
            actor.tenantUserId.takeIf { complete },
            idempotencyKeyId,
            Timestamp.from(java.time.Instant.now()).takeIf { complete },
        )
        return attemptId
    }

    private fun evaluateReadiness(
        tenantId: UUID,
        propertyId: UUID,
        reservationId: UUID,
        lock: Boolean,
    ): ReservationIdentityReadinessResponse {
        val reservation = jdbcTemplate.query(
            """
            SELECT check_in_date, adults, children
            FROM reservations
            WHERE tenant_id = ? AND property_id = ? AND id = ? AND deleted_at IS NULL
            ${if (lock) "FOR UPDATE" else ""}
            """.trimIndent(),
            { rs, _ ->
                ReadinessReservation(
                    checkInDate = rs.getObject("check_in_date", LocalDate::class.java),
                    adults = rs.getInt("adults"),
                    children = rs.getInt("children"),
                )
            },
            tenantId,
            propertyId,
            reservationId,
        ).singleOrNull() ?: throw ReservationNotFoundException("Reservation was not found")
        val occupants = jdbcTemplate.query(
            """
            SELECT rg.guest_id, rg.relationship_type, rg.guardian_guest_id, rg.guardian_attested_at,
                   g.date_of_birth, g.nationality
            FROM reservation_guests rg
            JOIN guests g ON g.tenant_id = rg.tenant_id AND g.id = rg.guest_id
            WHERE rg.tenant_id = ? AND rg.reservation_id = ? AND g.deleted_at IS NULL
            ORDER BY rg.is_primary DESC, rg.created_at
            ${if (lock) "FOR SHARE OF rg, g" else ""}
            """.trimIndent(),
            { rs, _ ->
                IdentityOccupant(
                    guestId = rs.getObject("guest_id", UUID::class.java),
                    dateOfBirth = rs.getObject("date_of_birth", LocalDate::class.java),
                    nationality = rs.getString("nationality"),
                    relationship = ReservationGuestRelationship.valueOf(rs.getString("relationship_type").uppercase()),
                    guardianGuestId = rs.getObject("guardian_guest_id", UUID::class.java),
                    guardianAttestedAt = rs.getTimestamp("guardian_attested_at")?.toInstant(),
                )
            },
            tenantId,
            reservationId,
        )
        val documents = if (occupants.isEmpty()) {
            emptyMap()
        } else {
            val guestIds = occupants.joinToString(prefix = "{", postfix = "}") { it.guestId.toString() }
            jdbcTemplate.query(
                """
                SELECT guest_id, document_type, expires_at, verification_expires_at
                FROM guest_documents
                WHERE tenant_id = ?
                  AND guest_id = ANY (?::uuid[])
                  AND verification_status = 'verified'
                  AND verified = true
                  AND revoked_at IS NULL
                ${if (lock) "FOR SHARE" else ""}
                """.trimIndent(),
                { rs, _ ->
                    val documentExpiry = rs.getObject("expires_at", LocalDate::class.java)
                    val verificationExpiry = rs.getTimestamp("verification_expires_at")
                        ?.toInstant()
                        ?.atZone(ZoneOffset.UTC)
                        ?.toLocalDate()
                    rs.getObject("guest_id", UUID::class.java) to IdentityDocument(
                        documentType = documentType(rs.getString("document_type")),
                        verified = true,
                        expiresAt = listOfNotNull(documentExpiry, verificationExpiry).minOrNull(),
                    )
                },
                tenantId,
                guestIds,
            ).groupBy({ it.first }, { it.second })
        }
        return policy.evaluate(
            reservationId = reservationId,
            checkInDate = reservation.checkInDate,
            expectedAdults = reservation.adults,
            expectedChildren = reservation.children,
            occupants = occupants,
            documents = documents,
        )
    }

    private fun requireGuestProfileForVerification(
        tenantId: UUID,
        propertyId: UUID,
        guestId: UUID,
    ): GuestResponse {
        val guest = requireGuest(tenantId, propertyId, guestId, lock = true)
        require(guest.dateOfBirth != null) { "Guest dateOfBirth is required for verification" }
        require(!guest.nationality.isNullOrBlank()) { "Guest nationality is required for verification" }
        return guest
    }

    private fun requireGuest(
        tenantId: UUID,
        propertyId: UUID,
        guestId: UUID,
        lock: Boolean = false,
    ): GuestResponse {
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, full_name, first_name, last_name, email, phone_primary,
                   date_of_birth, nationality, vip_level, blacklisted
            FROM guests g
            WHERE g.tenant_id = ? AND g.id = ? AND g.deleted_at IS NULL
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
            ${if (lock) "FOR UPDATE" else ""}
            """.trimIndent(),
            ::mapGuest,
            tenantId,
            guestId,
            propertyId,
            propertyId,
        ).singleOrNull() ?: throw ReservationNotFoundException("Guest was not found")
    }

    private fun requireDocument(tenantId: UUID, guestId: UUID, documentId: UUID): GuestIdentityDocumentResponse {
        return jdbcTemplate.query(
            "$DOCUMENT_SELECT WHERE gd.tenant_id = ? AND gd.guest_id = ? AND gd.id = ?",
            ::mapDocument,
            tenantId,
            guestId,
            documentId,
        ).singleOrNull() ?: throw ReservationNotFoundException("Guest identity document was not found")
    }

    private fun requireEditableReservation(
        tenantId: UUID,
        propertyId: UUID,
        reservationId: UUID,
    ): EditableReservation {
        return jdbcTemplate.query(
            """
            SELECT status, adults, children
            FROM reservations
            WHERE tenant_id = ? AND property_id = ? AND id = ? AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                EditableReservation(
                    status = rs.getString("status"),
                    adults = rs.getInt("adults"),
                    children = rs.getInt("children"),
                )
            },
            tenantId,
            propertyId,
            reservationId,
        ).singleOrNull()?.also {
            if (it.status !in setOf("pending", "confirmed")) {
                throw ReservationConflictException("Reservation occupants cannot change after check-in")
            }
        } ?: throw ReservationNotFoundException("Reservation was not found")
    }

    private fun validateDocumentInput(documentNumber: String, issuingCountry: String?, expiresAt: LocalDate?) {
        numberHasher.validate(documentNumber)
        issuingCountry?.let {
            require(it.trim().length == 2) { "issuingCountry must be an ISO 3166-1 alpha-2 code" }
        }
        expiresAt?.let {
            require(!it.isBefore(LocalDate.now())) { "Expired identity documents cannot be verified" }
        }
    }

    private fun recordIdentityResult(
        actor: TenantActor,
        propertyId: UUID,
        documentId: UUID,
        attemptId: UUID,
        result: String,
        provider: String,
        idempotencyKeyId: UUID,
    ) {
        recordSideEffects(
            actor = actor,
            propertyId = propertyId,
            action = "reservations.guest_identity.$result",
            eventType = "reservations.guest_identity.$result",
            aggregateType = GUEST_DOCUMENTS,
            aggregateId = documentId,
            payload = mapOf(
                "propertyId" to propertyId,
                "documentId" to documentId,
                "attemptId" to attemptId,
                "result" to result,
                "provider" to provider,
            ),
            idempotencyKeyId = idempotencyKeyId,
        )
    }

    private fun recordSideEffects(
        actor: TenantActor,
        propertyId: UUID,
        action: String,
        eventType: String,
        aggregateType: String,
        aggregateId: UUID,
        payload: Map<String, Any?>,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = actor.tenantId,
                action = action,
                resource = AuditResource(aggregateType, aggregateId),
                after = payload,
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = aggregateType,
                aggregateId = aggregateId,
                tenantId = actor.tenantId,
                propertyId = propertyId,
                eventType = eventType,
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                idempotencyKeyId = idempotencyKeyId,
                priority = 3,
            ),
        )
    }

    private fun <T : Any> mutate(
        propertyId: UUID,
        operationType: String,
        requestPayload: Any,
        resourceType: String,
        replayType: Class<T>,
        markReplay: (T) -> T = { it },
        block: (TenantActor, UUID) -> T,
    ): T {
        return try {
            requireNotNull(transactionTemplate.execute {
                val actor = bindActor(propertyId)
                when (val reservation = idempotencyPort.reserve(
                    IdempotencyCommand(operationType, requestPayload, resourceType),
                )) {
                    is IdempotencyReservation.Started -> {
                        val response = block(actor, reservation.recordId)
                        idempotencyPort.markSucceeded(
                            reservation.recordId,
                            200,
                            response,
                            resourceId(response),
                        )
                        response
                    }

                    is IdempotencyReservation.Replay -> {
                        if (reservation.responseBody.isNullOrBlank()) {
                            throw ReservationConflictException("Command replay has no stored response")
                        }
                        markReplay(objectMapper.readValue(reservation.responseBody, replayType))
                    }

                    is IdempotencyReservation.InProgress -> {
                        throw ReservationInProgressException("Command is already in progress")
                    }

                    is IdempotencyReservation.Conflict -> {
                        throw ReservationConflictException("Idempotency key was used for a different request")
                    }
                }
            })
        } catch (ex: DataIntegrityViolationException) {
            throw ReservationConflictException("Identity command conflicts with existing guest data")
        }
    }

    private fun <T> read(propertyId: UUID, block: (TenantActor) -> T): T {
        return requireNotNull(transactionTemplate.execute { block(bindActor(propertyId)) })
    }

    private fun bindActor(propertyId: UUID): TenantActor {
        val actor = tenantRequestContext.bind()
        tenantRequestContext.requirePropertyUsable(actor.tenantId, propertyId)
        return actor
    }

    private fun mapGuest(rs: ResultSet, rowNumber: Int): GuestResponse {
        return GuestResponse(
            id = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            fullName = rs.getString("full_name"),
            firstName = rs.getString("first_name"),
            lastName = rs.getString("last_name"),
            email = rs.getString("email"),
            phonePrimary = rs.getString("phone_primary"),
            dateOfBirth = rs.getObject("date_of_birth", LocalDate::class.java),
            nationality = rs.getString("nationality"),
            vipLevel = rs.getString("vip_level"),
            blacklisted = rs.getBoolean("blacklisted"),
        )
    }

    private fun mapDocument(rs: ResultSet, rowNumber: Int): GuestIdentityDocumentResponse {
        val storedStatus = GuestIdentityVerificationStatus.valueOf(
            rs.getString("verification_status").uppercase(),
        )
        val documentExpiry = rs.getObject("expires_at", LocalDate::class.java)
        val verificationExpiry = rs.getTimestamp("verification_expires_at")?.toInstant()
        val effectiveStatus = if (
            storedStatus == GuestIdentityVerificationStatus.VERIFIED &&
            (
                documentExpiry?.isBefore(LocalDate.now()) == true ||
                    verificationExpiry?.isBefore(java.time.Instant.now()) == true
                )
        ) {
            GuestIdentityVerificationStatus.EXPIRED
        } else {
            storedStatus
        }
        return GuestIdentityDocumentResponse(
            id = rs.getObject("id", UUID::class.java),
            guestId = rs.getObject("guest_id", UUID::class.java),
            documentType = documentType(rs.getString("document_type")),
            maskedDocumentNumber = rs.getString("document_number"),
            issuingCountry = rs.getString("issuing_country"),
            issuingAuthority = rs.getString("issuing_authority"),
            issuedAt = rs.getObject("issued_at", LocalDate::class.java),
            expiresAt = documentExpiry,
            verificationStatus = effectiveStatus,
            verificationMethod = rs.getString("verification_method"),
            verificationProvider = rs.getString("verification_provider"),
            verifiedAt = rs.getTimestamp("verified_at")?.toInstant(),
            verificationExpiresAt = rs.getTimestamp("verification_expires_at")?.toInstant(),
            revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
        )
    }

    private fun documentType(databaseValue: String): GuestIdentityDocumentType {
        return GuestIdentityDocumentType.entries.singleOrNull { it.databaseValue == databaseValue }
            ?: GuestIdentityDocumentType.OTHER_RECOGNISED
    }

    private fun VerifyGuestIdentityRequest.identitySafePayload(guestId: UUID): Map<String, Any?> {
        return mapOf(
            "guestId" to guestId,
            "documentType" to documentType,
            "documentFingerprint" to numberHasher.fingerprint(documentType.databaseValue, documentNumber),
            "issuingCountry" to issuingCountry,
            "expiresAt" to expiresAt,
        )
    }

    private fun ManualGuestIdentityVerificationRequest.identitySafePayload(guestId: UUID): Map<String, Any?> {
        return mapOf(
            "guestId" to guestId,
            "documentType" to documentType,
            "documentFingerprint" to numberHasher.fingerprint(documentType.databaseValue, documentNumber),
            "issuingCountry" to issuingCountry,
            "expiresAt" to expiresAt,
            "attestationReason" to attestationReason,
        )
    }

    private fun UpdateGuestRequest.changesIdentityAttributes(): Boolean {
        return fullName != null ||
                firstName != null ||
                lastName != null ||
                dateOfBirth != null ||
                nationality != null
    }

    private fun resourceId(response: Any): UUID? {
        return when (response) {
            is GuestResponse -> response.id
            is GuestIdentityVerificationReceipt -> response.document.id
            is GuestIdentityDocumentResponse -> response.id
            is ReservationGuestResponse -> response.guestId
            else -> null
        }
    }

    private sealed interface VerificationPreparation {
        data class Started(
            val actor: TenantActor,
            val propertyId: UUID,
            val guest: GuestResponse,
            val documentId: UUID,
            val attemptId: UUID,
            val idempotencyKeyId: UUID,
        ) : VerificationPreparation

        data class Replay(
            val receipt: GuestIdentityVerificationReceipt,
        ) : VerificationPreparation
    }

    private data class VerificationOutcome(
        val documentStatus: GuestIdentityVerificationStatus,
        val attemptStatus: String,
        val providerReference: String?,
        val failureCode: String?,
        val verificationExpiresAt: java.time.Instant?,
    )

    private data class EditableReservation(
        val status: String,
        val adults: Int,
        val children: Int,
    )

    private data class ReadinessReservation(
        val checkInDate: LocalDate,
        val adults: Int,
        val children: Int,
    )

    private data class MutationAcknowledgement(
        val changed: Boolean,
    )

    private companion object {
        const val NIDA_CIG = "nida_cig"
        const val PHYSICAL_DOCUMENT = "physical_document"
        const val GUESTS = "guests"
        const val GUEST_DOCUMENTS = "guest_documents"
        const val RESERVATIONS = "reservations"
        const val RESERVATION_GUESTS = "reservation_guests"
        const val VERIFICATION_METRIC = "peak.guest.identity.verification"

        val DOCUMENT_SELECT = """
            SELECT gd.id, gd.guest_id, gd.document_type, gd.document_number,
                   gd.issuing_country, gd.issuing_authority, gd.issued_at, gd.expires_at,
                   gd.verification_status, gd.verification_method, gd.verification_provider,
                   gd.verified_at, gd.verification_expires_at, gd.revoked_at
            FROM guest_documents gd
        """.trimIndent()
    }
}
