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
        val actor = actorFor(context.identity)
        val scope = scopeFor(context.identity)

        val existing = findExisting(scope.tenantId, key, lock = true)
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
                actor.type,
                actor.id,
                command.operationType,
                command.resourceType,
                Timestamp.from(clock.instant().plus(command.ttl)),
            )
        } catch (ex: DuplicateKeyException) {
            return requireNotNull(findExisting(scope.tenantId, key, lock = true)) {
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

    private fun findExisting(
        tenantId: UUID?,
        key: String,
        lock: Boolean,
    ): ExistingIdempotencyRecord? {
        val lockClause = if (lock) " FOR UPDATE" else ""
        val rows = if (tenantId == null) {
            jdbcTemplate.query(
                """
                SELECT id, request_hash, status, response_code, response_body::text AS response_body
                FROM idempotency_keys
                WHERE tenant_id IS NULL
                  AND idempotency_key = ?
                $lockClause
                """.trimIndent(),
                ::mapExisting,
                key,
            )
        } else {
            jdbcTemplate.query(
                """
                SELECT id, request_hash, status, response_code, response_body::text AS response_body
                FROM idempotency_keys
                WHERE tenant_id = ?
                  AND idempotency_key = ?
                $lockClause
                """.trimIndent(),
                ::mapExisting,
                tenantId,
                key,
            )
        }

        return rows.singleOrNull()
    }

    private fun mapExisting(rs: ResultSet, rowNumber: Int): ExistingIdempotencyRecord {
        return ExistingIdempotencyRecord(
            id = rs.getObject("id", UUID::class.java),
            requestHash = rs.getString("request_hash"),
            status = IdempotencyStatus.entries.first {
                it.databaseValue == rs.getString("status")
            },
            responseCode = rs.getObject("response_code") as Int?,
            responseBody = rs.getString("response_body"),
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

    private fun actorFor(identity: RequestIdentity): ActorScope {
        return when (identity) {
            is RequestIdentity.Tenant -> ActorScope("tenant_user", identity.tenantUserId)
            is RequestIdentity.Platform -> ActorScope("platform_user", identity.platformUserId)
            is RequestIdentity.Support -> ActorScope("platform_user", identity.platformUserId)
            is RequestIdentity.Public -> ActorScope("guest", null)
        }
    }

    private fun scopeFor(identity: RequestIdentity): TenantScope {
        return when (identity) {
            is RequestIdentity.Tenant -> TenantScope(identity.tenantId, null)
            is RequestIdentity.Support -> TenantScope(identity.tenantId, null)
            is RequestIdentity.Public -> TenantScope(identity.tenantId, identity.propertyId)
            is RequestIdentity.Platform -> TenantScope(null, null)
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
    )

    private data class ActorScope(
        val type: String,
        val id: UUID?,
    )

    private data class TenantScope(
        val tenantId: UUID?,
        val propertyId: UUID?,
    )
}
