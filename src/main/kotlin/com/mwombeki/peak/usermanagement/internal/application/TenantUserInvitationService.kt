package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.InviteTenantUserCommand
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationConflictException
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationInProgressException
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationPort
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationReceipt
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class TenantUserInvitationService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) : TenantUserInvitationPort {
    override fun inviteTenantUser(command: InviteTenantUserCommand): TenantUserInvitationReceipt {
        return requireNotNull(
            transactionTemplate.execute {
                inviteInsideTransaction(command.normalized())
            },
        )
    }

    private fun inviteInsideTransaction(command: NormalizedInviteCommand): TenantUserInvitationReceipt {
        val identity = requestContextHolder.current().identity
        val actor = actorFor(identity, command.tenantId)
        databaseSessionContext.bind(identity)

        val reservation = idempotencyPort.reserve(
            IdempotencyCommand(
                operationType = "tenant.user.invite",
                requestPayload = mapOf(
                    "tenantId" to command.tenantId,
                    "email" to command.email,
                    "tenantRoleId" to command.tenantRoleId,
                    "fullName" to command.fullName,
                    "expiresInSeconds" to command.expiresInSeconds,
                ),
                resourceType = "tenant_user_invitations",
            ),
        )

        return when (reservation) {
            is IdempotencyReservation.Started -> createInvitation(command, actor, reservation.recordId)
            is IdempotencyReservation.Replay -> replayInvitation(reservation)
            is IdempotencyReservation.InProgress -> throw TenantUserInvitationInProgressException(
                "Tenant user invitation is already being processed for this idempotency key",
            )

            is IdempotencyReservation.Conflict -> throw TenantUserInvitationConflictException(
                "Idempotency key was already used for a different tenant user invitation request",
            )
        }
    }

    private fun createInvitation(
        command: NormalizedInviteCommand,
        actor: InvitationActor,
        idempotencyKeyId: UUID,
    ): TenantUserInvitationReceipt {
        requireActiveTenantRole(command.tenantId, command.tenantRoleId)
        requireNoActiveUserWithEmail(command.tenantId, command.email)

        val invitationId = UUID.randomUUID()
        val token = invitationToken()
        val tokenHash = token.sha256Url()
        val expiresAt = clock.instant().plusSeconds(command.expiresInSeconds)

        try {
            jdbcTemplate.update(
                """
                INSERT INTO tenant_user_invitations (
                    id,
                    tenant_id,
                    email,
                    full_name,
                    tenant_role_id,
                    token_hash,
                    invited_by_user_id,
                    invited_by_platform_user_id,
                    expires_at,
                    metadata
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """.trimIndent(),
                invitationId,
                command.tenantId,
                command.email,
                command.fullName,
                command.tenantRoleId,
                tokenHash,
                actor.tenantUserId,
                actor.platformUserId,
                Timestamp.from(expiresAt),
                objectMapper.writeValueAsString(command.metadata),
            )
        } catch (ex: DuplicateKeyException) {
            throw TenantUserInvitationConflictException(
                "A pending invitation already exists for this tenant user email",
            )
        }

        val snapshot = InvitationSnapshot(
            invitationId = invitationId,
            tenantId = command.tenantId,
            email = command.email,
            tenantRoleId = command.tenantRoleId,
            expiresAt = expiresAt,
        )

        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = command.tenantId,
                action = "tenant.users.invite",
                resource = AuditResource("tenant_user_invitations", invitationId),
                after = mapOf(
                    "email" to command.email,
                    "tenantRoleId" to command.tenantRoleId,
                    "expiresAt" to expiresAt.toString(),
                ),
            ),
        )

        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "tenant_user_invitations",
                aggregateId = invitationId,
                tenantId = command.tenantId,
                eventType = "tenant.user.invited",
                destination = OutboxDestination.EMAIL,
                payload = mapOf(
                    "invitationId" to invitationId,
                    "tenantId" to command.tenantId,
                    "email" to command.email,
                    "fullName" to command.fullName,
                    "expiresAt" to expiresAt.toString(),
                ),
                idempotencyKeyId = idempotencyKeyId,
                priority = 4,
            ),
        )

        idempotencyPort.markSucceeded(
            recordId = idempotencyKeyId,
            responseCode = 201,
            responseBody = snapshot,
            resourceId = invitationId,
        )

        return snapshot.toReceipt(invitationToken = token, replayed = false)
    }

    private fun replayInvitation(
        reservation: IdempotencyReservation.Replay,
    ): TenantUserInvitationReceipt {
        if (reservation.responseBody.isNullOrBlank()) {
            throw TenantUserInvitationConflictException(
                "Invitation replay does not contain a stored response body",
            )
        }

        val snapshot = objectMapper.readValue(
            reservation.responseBody,
            InvitationSnapshot::class.java,
        )
        return snapshot.toReceipt(invitationToken = null, replayed = true)
    }

    private fun actorFor(
        identity: RequestIdentity,
        tenantId: UUID,
    ): InvitationActor {
        return when (identity) {
            is RequestIdentity.Tenant -> {
                require(identity.tenantId == tenantId) {
                    "Invitation tenant does not match request identity"
                }
                InvitationActor(tenantUserId = identity.tenantUserId, platformUserId = null)
            }

            is RequestIdentity.Support -> {
                require(identity.tenantId == tenantId) {
                    "Invitation tenant does not match support identity"
                }
                InvitationActor(tenantUserId = null, platformUserId = identity.platformUserId)
            }

            is RequestIdentity.Platform -> {
                InvitationActor(tenantUserId = null, platformUserId = identity.platformUserId)
            }

            is RequestIdentity.Public -> {
                throw IllegalArgumentException("Public identity cannot invite tenant users")
            }
        }
    }

    private fun requireActiveTenantRole(
        tenantId: UUID,
        tenantRoleId: UUID,
    ) {
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM tenant_roles
                WHERE tenant_id = ?
                  AND id = ?
                  AND is_active = true
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            tenantRoleId,
        ) == true

        require(exists) {
            "Active tenant role is required for invitation"
        }
    }

    private fun requireNoActiveUserWithEmail(
        tenantId: UUID,
        email: String,
    ) {
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM users
                WHERE tenant_id = ?
                  AND lower(email) = ?
                  AND deleted_at IS NULL
                  AND status IN ('active', 'invited')
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            email,
        ) == true

        require(!exists) {
            "A tenant user already exists for this email"
        }
    }

    private fun InviteTenantUserCommand.normalized(): NormalizedInviteCommand {
        val email = email.trim().lowercase(Locale.ROOT)
        require(EMAIL_PATTERN.matches(email)) {
            "Invitation email is invalid"
        }

        return NormalizedInviteCommand(
            tenantId = tenantId,
            email = email,
            tenantRoleId = tenantRoleId,
            fullName = fullName?.trim()?.takeIf { it.isNotBlank() },
            expiresInSeconds = expiresIn.toSeconds(),
            metadata = metadata,
        )
    }

    private fun InvitationSnapshot.toReceipt(
        invitationToken: String?,
        replayed: Boolean,
    ): TenantUserInvitationReceipt {
        return TenantUserInvitationReceipt(
            invitationId = invitationId,
            tenantId = tenantId,
            email = email,
            tenantRoleId = tenantRoleId,
            expiresAt = expiresAt,
            invitationToken = invitationToken,
            replayed = replayed,
        )
    }

    private fun invitationToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return base64Url.encodeToString(bytes)
    }

    private fun String.sha256Url(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return base64Url.encodeToString(digest)
    }

    private data class NormalizedInviteCommand(
        val tenantId: UUID,
        val email: String,
        val tenantRoleId: UUID,
        val fullName: String?,
        val expiresInSeconds: Long,
        val metadata: Map<String, Any?>,
    )

    private data class InvitationActor(
        val tenantUserId: UUID?,
        val platformUserId: UUID?,
    )

    private data class InvitationSnapshot(
        val invitationId: UUID,
        val tenantId: UUID,
        val email: String,
        val tenantRoleId: UUID,
        val expiresAt: Instant,
    )

    private companion object {
        const val TOKEN_BYTES = 32
        val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        val secureRandom = SecureRandom()
        val base64Url = Base64.getUrlEncoder().withoutPadding()
    }
}
