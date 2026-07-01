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
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.secrets.SecretEnvelopeService
import com.mwombeki.peak.usermanagement.api.AcceptTenantUserInvitationCommand
import com.mwombeki.peak.usermanagement.api.InviteTenantUserCommand
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationAcceptanceReceipt
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationAcceptanceRejectedException
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationConflictException
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationInProgressException
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationPort
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationReceipt
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import org.springframework.dao.DataAccessException
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
    private val secretEnvelopeService: SecretEnvelopeService,
    private val invitationSecurityProperties: TenantInvitationSecurityProperties,
    private val clock: Clock = Clock.systemUTC(),
) : TenantUserInvitationPort {
    override fun inviteTenantUser(command: InviteTenantUserCommand): TenantUserInvitationReceipt {
        return requireNotNull(
            transactionTemplate.execute {
                inviteInsideTransaction(command.normalized())
            },
        )
    }

    override fun acceptTenantUserInvitation(
        command: AcceptTenantUserInvitationCommand,
    ): TenantUserInvitationAcceptanceReceipt {
        return requireNotNull(
            transactionTemplate.execute {
                acceptInsideTransaction(command.normalized())
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
        val token = InvitationTokens.newToken()
        val tokenHash = InvitationTokens.hash(token)
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
                    "tokenEnvelope" to secretEnvelopeService.encrypt(
                        plaintext = token,
                        associatedData = invitationId.toString(),
                    ),
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

        return snapshot.toReceipt(
            invitationToken = token.takeIf {
                invitationSecurityProperties.exposeTokenInResponse
            },
            replayed = false,
        )
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

    private fun acceptInsideTransaction(
        command: NormalizedAcceptCommand,
    ): TenantUserInvitationAcceptanceReceipt {
        val originalContext = requestContextHolder.current()
        databaseSessionContext.bind(originalContext.identity)

        val reservation = idempotencyPort.reserve(
            IdempotencyCommand(
                operationType = "tenant.user.invitation.accept",
                requestPayload = mapOf(
                    "tokenHash" to command.tokenHash,
                    "issuer" to command.issuer,
                    "subject" to command.subject,
                    "email" to command.email,
                    "fullName" to command.fullName,
                ),
                resourceType = "tenant_user_invitations",
            ),
        )

        return when (reservation) {
            is IdempotencyReservation.Started -> acceptStarted(
                command = command,
                originalContext = originalContext,
                idempotencyKeyId = reservation.recordId,
            )

            is IdempotencyReservation.Replay -> replayAcceptance(reservation)
            is IdempotencyReservation.InProgress -> throw TenantUserInvitationInProgressException(
                "Tenant user invitation acceptance is already being processed for this idempotency key",
            )

            is IdempotencyReservation.Conflict -> throw TenantUserInvitationConflictException(
                "Idempotency key was already used for a different invitation acceptance request",
            )
        }
    }

    private fun acceptStarted(
        command: NormalizedAcceptCommand,
        originalContext: RequestContext,
        idempotencyKeyId: UUID,
    ): TenantUserInvitationAcceptanceReceipt {
        val snapshot = try {
            jdbcTemplate.queryForObject(
                """
                SELECT *
                FROM accept_tenant_user_invitation(?, ?, ?, ?, ?)
                """.trimIndent(),
                ::mapAcceptanceSnapshot,
                command.tokenHash,
                command.issuer,
                command.subject,
                command.email,
                command.fullName,
            )
        } catch (ex: DataAccessException) {
            throw TenantUserInvitationAcceptanceRejectedException(
                "Invitation acceptance was rejected",
            )
        }

        idempotencyPort.markSucceeded(
            recordId = idempotencyKeyId,
            responseCode = 200,
            responseBody = snapshot,
            resourceId = snapshot.invitationId,
        )

        recordAcceptanceSideEffects(snapshot, originalContext)

        return snapshot.toAcceptanceReceipt(replayed = false)
    }

    private fun recordAcceptanceSideEffects(
        snapshot: AcceptanceSnapshot,
        originalContext: RequestContext,
    ) {
        val acceptedIdentity = RequestIdentity.Tenant(
            tenantId = snapshot.tenantId,
            tenantUserId = snapshot.userId,
            correlationId = originalContext.correlationId,
        )
        val acceptedContext = originalContext.copy(identity = acceptedIdentity)

        try {
            requestContextHolder.set(acceptedContext)
            databaseSessionContext.bind(acceptedIdentity)

            auditPort.recordTenantEvent(
                TenantAuditEvent(
                    tenantId = snapshot.tenantId,
                    action = "tenant.users.invitation.accept",
                    resource = AuditResource("tenant_user_invitations", snapshot.invitationId),
                    after = mapOf(
                        "userId" to snapshot.userId,
                        "tenantRoleId" to snapshot.tenantRoleId,
                        "identityLinkId" to snapshot.identityLinkId,
                        "email" to snapshot.email,
                    ),
                ),
            )

            outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = "tenant_user_invitations",
                    aggregateId = snapshot.invitationId,
                    tenantId = snapshot.tenantId,
                    eventType = "tenant.user.invitation.accepted",
                    destination = OutboxDestination.PLATFORM,
                    payload = mapOf(
                        "invitationId" to snapshot.invitationId,
                        "tenantId" to snapshot.tenantId,
                        "userId" to snapshot.userId,
                        "tenantRoleId" to snapshot.tenantRoleId,
                        "identityLinkId" to snapshot.identityLinkId,
                        "email" to snapshot.email,
                    ),
                    priority = 5,
                ),
            )
        } finally {
            requestContextHolder.set(originalContext)
        }
    }

    private fun replayAcceptance(
        reservation: IdempotencyReservation.Replay,
    ): TenantUserInvitationAcceptanceReceipt {
        if (reservation.responseBody.isNullOrBlank()) {
            throw TenantUserInvitationConflictException(
                "Invitation acceptance replay does not contain a stored response body",
            )
        }

        val snapshot = objectMapper.readValue(
            reservation.responseBody,
            AcceptanceSnapshot::class.java,
        )
        return snapshot.toAcceptanceReceipt(replayed = true)
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
        val role = jdbcTemplate.query(
            """
            SELECT is_system
            FROM tenant_roles
            WHERE tenant_id = ?
              AND id = ?
              AND is_active = true
            """.trimIndent(),
            { rs, _ -> rs.getBoolean("is_system") },
            tenantId,
            tenantRoleId,
        ).singleOrNull()

        require(role != null) {
            "Active tenant role is required for invitation"
        }
        require(!role) {
            "System tenant roles cannot be assigned through invitations"
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

    private fun AcceptTenantUserInvitationCommand.normalized(): NormalizedAcceptCommand {
        val email = email?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
        if (email != null) {
            require(EMAIL_PATTERN.matches(email)) {
                "Invitation acceptance email is invalid"
            }
        }

        return NormalizedAcceptCommand(
            tokenHash = InvitationTokens.hash(invitationToken.trim()),
            issuer = issuer.trim(),
            subject = subject.trim(),
            email = email,
            fullName = fullName?.trim()?.takeIf { it.isNotBlank() },
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

    @Suppress("UNUSED_PARAMETER")
    private fun mapAcceptanceSnapshot(
        rs: ResultSet,
        rowNumber: Int,
    ): AcceptanceSnapshot {
        return AcceptanceSnapshot(
            invitationId = rs.getObject("invitation_id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            userId = rs.getObject("user_id", UUID::class.java),
            tenantRoleId = rs.getObject("tenant_role_id", UUID::class.java),
            email = rs.getString("email"),
            identityLinkId = rs.getObject("identity_link_id", UUID::class.java),
        )
    }

    private fun AcceptanceSnapshot.toAcceptanceReceipt(
        replayed: Boolean,
    ): TenantUserInvitationAcceptanceReceipt {
        return TenantUserInvitationAcceptanceReceipt(
            invitationId = invitationId,
            tenantId = tenantId,
            userId = userId,
            tenantRoleId = tenantRoleId,
            email = email,
            identityLinkId = identityLinkId,
            replayed = replayed,
        )
    }

    private data class NormalizedInviteCommand(
        val tenantId: UUID,
        val email: String,
        val tenantRoleId: UUID,
        val fullName: String?,
        val expiresInSeconds: Long,
        val metadata: Map<String, Any?>,
    )

    private data class NormalizedAcceptCommand(
        val tokenHash: String,
        val issuer: String,
        val subject: String,
        val email: String?,
        val fullName: String?,
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

    private data class AcceptanceSnapshot(
        val invitationId: UUID,
        val tenantId: UUID,
        val userId: UUID,
        val tenantRoleId: UUID,
        val email: String,
        val identityLinkId: UUID,
    )

    private companion object {
        val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
