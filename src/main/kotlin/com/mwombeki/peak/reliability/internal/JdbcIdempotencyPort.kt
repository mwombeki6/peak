package com.mwombeki.peak.reliability.internal

import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.IdempotencyStatus
import com.mwombeki.peak.shared.context.RequestContextException
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper

@Component
class JdbcIdempotencyPort(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val requestHasher: RequestHasher,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) : IdempotencyPort {
    override fun reserve(command: IdempotencyCommand): IdempotencyReservation {
        requireActiveTransaction()

        val context = requestContextHolder.current()
        val key = context.idempotencyKey
            ?: throw RequestContextException("Idempotency-Key header is required")
        val requestHash = requestHasher.hash(context, command.operationType, command.requestPayload)
        val scope = scopeFor(context.identity)

        val existing = findExisting(scope, key)
        if (existing != null) {
            return existing.toReservation(requestHash)
        }

        val id = UUID.randomUUID()
        try {
            jdbcTemplate.update(
                """
                INSERT INTO idempotency_keys (
                    id,
                    tenant_id,
                    property_id,
                    idempotency_key,
                    request_method,
                    request_path,
                    request_hash,
                    actor_type,
                    actor_id,
                    operation_type,
                    resource_type,
                    status,
                    locked_at,
                    expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'processing', now(), ?)
                """.trimIndent(),
                id,
                scope.tenantId,
                scope.propertyId,
                key,
                context.httpMethod,
                context.requestPath,
                requestHash,
                scope.actorType,
                scope.actorId,
                command.operationType,
                command.resourceType,
                Timestamp.from(clock.instant().plus(command.ttl)),
            )
        } catch (ex: DuplicateKeyException) {
            return requireNotNull(findExisting(scope, key)) {
                "Duplicate idempotency key was not readable after conflict"
            }.toReservation(requestHash)
        }

        return IdempotencyReservation.Started(id)
    }

    override fun markSucceeded(
        recordId: UUID,
        responseCode: Int,
        responseBody: Any?,
        resourceId: UUID?,
    ) {
        complete(recordId, IdempotencyStatus.SUCCEEDED, responseCode, responseBody, resourceId)
    }

    override fun markFailed(
        recordId: UUID,
        responseCode: Int,
        responseBody: Any?,
        resourceId: UUID?,
    ) {
        complete(recordId, IdempotencyStatus.FAILED, responseCode, responseBody, resourceId)
    }

    private fun complete(
        recordId: UUID,
        status: IdempotencyStatus,
        responseCode: Int,
        responseBody: Any?,
        resourceId: UUID?,
    ) {
        requireActiveTransaction()
        require(responseCode in 100..599) {
            "Idempotency response code must be an HTTP status"
        }

        jdbcTemplate.update(
            """
            UPDATE idempotency_keys
            SET status = ?,
                response_code = ?,
                response_body = ?::jsonb,
                resource_id = COALESCE(?, resource_id),
                locked_at = NULL
            WHERE id = ?
            """.trimIndent(),
            status.databaseValue,
            responseCode,
            json(responseBody),
            resourceId,
            recordId,
        )
    }

    /**
     * Every predicate here must be null-safe (`IS NOT DISTINCT FROM`, not `=`) to match
     * idx_idempotency_keys_actor_scope's `NULLS NOT DISTINCT` semantics — tenant_id,
     * property_id and actor_id are all nullable depending on identity type, and plain `=`
     * never matches NULL to NULL, which would silently widen this lookup back into a shared
     * namespace for exactly the actors that need isolating (platform, onboarding applicant,
     * guest).
     */
    private fun findExisting(
        scope: IdempotencyScope,
        key: String,
    ): ExistingIdempotencyRecord? {
        val rows = jdbcTemplate.query(
            """
            SELECT id, request_hash, status, response_code,
                   response_body::text AS response_body, expires_at
            FROM idempotency_keys
            WHERE tenant_id IS NOT DISTINCT FROM ?
              AND property_id IS NOT DISTINCT FROM ?
              AND actor_type = ?
              AND actor_id IS NOT DISTINCT FROM ?
              AND idempotency_key = ?
              AND status <> 'expired'
            FOR UPDATE
            """.trimIndent(),
            ::mapExisting,
            scope.tenantId,
            scope.propertyId,
            scope.actorType,
            scope.actorId,
            key,
        )

        val existing = rows.singleOrNull() ?: return null
        if (!existing.expiresAt.isAfter(clock.instant())) {
            jdbcTemplate.update(
                """
                UPDATE idempotency_keys
                SET status = 'expired',
                    locked_at = NULL,
                    updated_at = now()
                WHERE id = ?
                  AND status <> 'expired'
                """.trimIndent(),
                existing.id,
            )
            return null
        }
        return existing
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapExisting(rs: ResultSet, rowNumber: Int): ExistingIdempotencyRecord {
        return ExistingIdempotencyRecord(
            id = rs.getObject("id", UUID::class.java),
            requestHash = rs.getString("request_hash"),
            status = IdempotencyStatus.entries.first {
                it.databaseValue == rs.getString("status")
            },
            responseCode = rs.getObject("response_code") as Int?,
            responseBody = rs.getString("response_body"),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
        )
    }

    private fun ExistingIdempotencyRecord.toReservation(
        currentRequestHash: String,
    ): IdempotencyReservation {
        if (requestHash != currentRequestHash) {
            return IdempotencyReservation.Conflict(id)
        }

        return when (status) {
            IdempotencyStatus.PROCESSING -> IdempotencyReservation.InProgress(id)
            IdempotencyStatus.SUCCEEDED,
            IdempotencyStatus.FAILED,
            -> IdempotencyReservation.Replay(
                recordId = id,
                responseCode = responseCode,
                responseBody = responseBody,
                status = status,
            )

            IdempotencyStatus.EXPIRED -> IdempotencyReservation.Conflict(id)
        }
    }

    /**
     * The full identity of an idempotency slot: which tenant (if any), which property (if the
     * identity carries one), which kind of actor, and which actor. Request idempotency belongs
     * to the authenticated calling actor, not just their tenant — two different tenant users,
     * two different platform operators, or two different onboarding applicants must never
     * share a slot merely because they picked the same key value. `Public`/guest sessions are
     * the one identity with no stable actor id at all (no login, no applicant token), so guests
     * within the same tenant+property necessarily still share one slot — a limit of that
     * identity, not something scoping alone can close.
     */
    private fun scopeFor(identity: RequestIdentity): IdempotencyScope {
        return when (identity) {
            is RequestIdentity.Tenant -> IdempotencyScope(
                tenantId = identity.tenantId,
                propertyId = null,
                actorType = "tenant_user",
                actorId = identity.tenantUserId,
            )

            is RequestIdentity.Support -> IdempotencyScope(
                tenantId = identity.tenantId,
                propertyId = null,
                actorType = "platform_user",
                actorId = identity.platformUserId,
            )

            is RequestIdentity.Public -> IdempotencyScope(
                tenantId = identity.tenantId,
                propertyId = identity.propertyId,
                actorType = "guest",
                actorId = null,
            )

            is RequestIdentity.Platform -> IdempotencyScope(
                tenantId = null,
                propertyId = null,
                actorType = "platform_user",
                actorId = identity.platformUserId,
            )

            is RequestIdentity.OnboardingApplicant -> IdempotencyScope(
                tenantId = null,
                propertyId = null,
                actorType = "onboarding_applicant",
                actorId = identity.applicationId,
            )
        }
    }

    private fun json(payload: Any?): String? {
        return payload?.let(objectMapper::writeValueAsString)
    }

    private fun requireActiveTransaction() {
        require(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Idempotency operations must run inside an active transaction"
        }
    }

    private data class ExistingIdempotencyRecord(
        val id: UUID,
        val requestHash: String,
        val status: IdempotencyStatus,
        val responseCode: Int?,
        val responseBody: String?,
        val expiresAt: Instant,
    )

    private data class IdempotencyScope(
        val tenantId: UUID?,
        val propertyId: UUID?,
        val actorType: String,
        val actorId: UUID?,
    )
}
