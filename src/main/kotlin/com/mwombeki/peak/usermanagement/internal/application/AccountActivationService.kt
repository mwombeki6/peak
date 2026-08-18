package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.outbound.EstablishPassword
import com.mwombeki.peak.shared.outbound.IdentityProvisionerPort
import com.mwombeki.peak.shared.outbound.IdentityProvisioningException
import com.mwombeki.peak.shared.outbound.MarkEmailVerified
import com.mwombeki.peak.shared.outbound.ProvisionIdentity
import com.mwombeki.peak.usermanagement.api.AccountActivationException
import com.mwombeki.peak.verification.api.ConfirmVerificationCommand
import com.mwombeki.peak.verification.api.RequestVerificationCommand
import com.mwombeki.peak.verification.api.VerificationPort
import com.mwombeki.peak.verification.api.VerificationPurpose
import com.mwombeki.peak.verification.api.VerificationThrottledException
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import org.springframework.beans.factory.ObjectProvider
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Service
class AccountActivationService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val verification: VerificationPort,
    private val outbox: OutboxPort,
    private val identities: ObjectProvider<IdentityProvisionerPort>,
    private val activationProperties: AccountActivationProperties,
    private val mapper: ObjectMapper,
) {
    fun lookupInvitation(token: String): InvitationDetails {
        val row = loadInvitation(token) ?: throw notFound()
        return toDetails(row)
    }

    fun sendInvitationCode(token: String): CodeDispatch {
        val row = requirePending(token)
        return dispatchCode(
            purpose = VerificationPurpose.ACCOUNT_ACTIVATION,
            email = row.email,
            subjectRef = row.invitationId.toString(),
            tenantId = row.tenantId,
            fullName = row.fullName,
        )
    }

    fun verifyInvitationCode(token: String, code: String): SetupGrant {
        val row = requirePending(token)
        confirmCode(VerificationPurpose.ACCOUNT_ACTIVATION, row.email, code)
        val grant = InvitationTokens.newToken()
        insertGrant(
            grantHash = InvitationTokens.hash(grant),
            invitationId = row.invitationId,
            tenantId = row.tenantId,
            email = row.email,
            realm = row.realm,
        )
        return SetupGrant(setupGrant = grant, expiresInSeconds = GRANT_TTL_SECONDS)
    }

    fun setInvitationCredential(
        token: String,
        setupGrant: String,
        password: String?,
    ): CredentialAccepted {
        val row = requirePending(token)
        val grant = consumeGrant(setupGrant)
        if (grant.email != row.email) throw notFound()
        val secret = requirePassword(password)
        val names = splitName(row.fullName, row.email)
        val userId = UUID.randomUUID()
        val provisioner = requireProvisioner()
        val provisioned = try {
            provisioner.provision(
                ProvisionIdentity(
                    username = row.email,
                    email = row.email,
                    firstName = names.first,
                    lastName = names.second,
                    tenantId = row.tenantId.toString(),
                    peakUserId = userId.toString(),
                    realm = row.realm,
                    emailVerified = true,
                ),
            )
        } catch (ex: IdentityProvisioningException) {
            throw unreachable("Identity could not be created")
        }
        try {
            provisioner.establishPassword(
                EstablishPassword(provisioned.subjectId, secret, row.realm),
            )
            provisioner.markEmailVerified(MarkEmailVerified(provisioned.subjectId, row.realm))
            provisioner.clearRequiredActions(provisioned.subjectId, row.realm)
        } catch (ex: IdentityProvisioningException) {
            unwind(provisioner, provisioned.subjectId, row.realm, provisioned.alreadyExisted)
            throw unreachable("Identity credential could not be stored")
        }
        try {
            acceptInvitation(
                token = token,
                issuer = issuerFor(row.realm),
                subject = provisioned.subjectId,
                email = row.email,
                fullName = row.fullName,
            )
        } catch (ex: RuntimeException) {
            unwind(provisioner, provisioned.subjectId, row.realm, provisioned.alreadyExisted)
            throw ex
        }
        // Peak does not mint a session cookie yet. Pretending signedIn=true would
        // send the browser into a logged-in shell that has no credential.
        return CredentialAccepted(signedIn = false, redirectTo = null)
    }

    fun confirmRecoveryCode(
        token: String,
        setupGrant: String,
        code: String,
    ): CredentialAccepted {
        // Platform backup enrolment is not wired to an authenticator yet. Returning
        // signed-in would pretend the code was checked. Refuse until that adapter exists.
        throw AccountActivationException(
            "unknown",
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Recovery enrolment is not available yet",
        )
    }

    fun startRecovery(email: String): CodeDispatch {
        val normalized = email.trim().lowercase(Locale.ROOT)
        val existing = findRecoverableAccount(normalized)
        if (existing != null) {
            return dispatchCode(
                purpose = VerificationPurpose.ACCOUNT_RECOVERY,
                email = normalized,
                subjectRef = existing.subjectRef,
                tenantId = existing.tenantId,
                fullName = existing.fullName,
            )
        }
        // Same shape whether or not the address exists.
        return CodeDispatch(
            maskedEmail = maskEmail(normalized),
            resendAvailableInSeconds = 60,
            expiresInSeconds = 600,
        )
    }

    fun verifyRecoveryCode(email: String, code: String): SetupGrant {
        val normalized = email.trim().lowercase(Locale.ROOT)
        confirmCode(VerificationPurpose.ACCOUNT_RECOVERY, normalized, code)
        val account = findRecoverableAccount(normalized)
            ?: throw AccountActivationException(
                "code_incorrect",
                HttpStatus.UNPROCESSABLE_CONTENT,
                "That code isn't right",
            )
        val grant = InvitationTokens.newToken()
        insertGrant(
            grantHash = InvitationTokens.hash(grant),
            invitationId = null,
            tenantId = account.tenantId,
            email = normalized,
            realm = account.realm,
        )
        return SetupGrant(setupGrant = grant, expiresInSeconds = GRANT_TTL_SECONDS)
    }

    fun setRecoveryCredential(
        email: String,
        setupGrant: String,
        password: String?,
    ): CredentialAccepted {
        val normalized = email.trim().lowercase(Locale.ROOT)
        val grant = consumeGrant(setupGrant)
        if (grant.email != normalized) {
            throw AccountActivationException(
                "code_incorrect",
                HttpStatus.UNPROCESSABLE_CONTENT,
                "That code isn't right",
            )
        }
        val secret = requirePassword(password)
        val provisioner = requireProvisioner()
        val account = findRecoverableAccount(normalized)
            ?: throw AccountActivationException(
                "account_not_active",
                HttpStatus.FORBIDDEN,
                "That account is not active",
            )
        try {
            val provisioned = provisioner.provision(
                ProvisionIdentity(
                    username = normalized,
                    email = normalized,
                    firstName = splitName(account.fullName, normalized).first,
                    lastName = splitName(account.fullName, normalized).second,
                    tenantId = (account.tenantId ?: UUID(0, 0)).toString(),
                    peakUserId = account.subjectRef,
                    realm = account.realm,
                    emailVerified = true,
                ),
            )
            provisioner.establishPassword(
                EstablishPassword(provisioned.subjectId, secret, account.realm),
            )
            provisioner.clearRequiredActions(provisioned.subjectId, account.realm)
        } catch (ex: IdentityProvisioningException) {
            throw unreachable("Identity credential could not be stored")
        }
        return CredentialAccepted(signedIn = false, redirectTo = null)
    }

    private fun dispatchCode(
        purpose: VerificationPurpose,
        email: String,
        subjectRef: String?,
        tenantId: UUID?,
        fullName: String?,
    ): CodeDispatch {
        val receipt = try {
            verification.request(
                RequestVerificationCommand(
                    purpose = purpose,
                    destination = email,
                    subjectRef = subjectRef,
                    tenantId = tenantId,
                ),
            )
        } catch (ex: VerificationThrottledException) {
            throw AccountActivationException(
                "too_many_attempts",
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many tries",
            )
        }
        if (tenantId != null) {
            transactionTemplate.execute {
                outbox.enqueue(
                    OutboxEventCommand(
                        aggregateType = "account_setup_grants",
                        aggregateId = receipt.id,
                        tenantId = tenantId,
                        eventType = CODE_EVENT,
                        destination = OutboxDestination.EMAIL,
                        payload = mapOf(
                            "email" to email,
                            "fullName" to (fullName ?: "Peak user"),
                            "code" to receipt.code,
                            "expiresAt" to receipt.expiresAt.toString(),
                        ),
                        priority = 4,
                    ),
                )
            }
        }
        val ttl = Duration.between(Instant.now(), receipt.expiresAt).seconds.coerceAtLeast(1).toInt()
        return CodeDispatch(
            maskedEmail = maskEmail(email),
            resendAvailableInSeconds = 60,
            expiresInSeconds = ttl,
            debugCode = receipt.code.takeIf { activationProperties.exposeCodeInResponse },
        )
    }

    private fun confirmCode(purpose: VerificationPurpose, email: String, code: String) {
        val outcome = verification.confirm(
            ConfirmVerificationCommand(purpose = purpose, destination = email, code = code),
        )
        if (!outcome.verified) {
            throw AccountActivationException(
                "code_incorrect",
                HttpStatus.UNPROCESSABLE_CONTENT,
                "That code isn't right",
            )
        }
    }

    private fun requirePending(token: String): InvitationRow {
        val row = loadInvitation(token) ?: throw notFound()
        when (row.status) {
            "pending" -> Unit
            "expired" -> throw AccountActivationException(
                "invitation_expired",
                HttpStatus.GONE,
                "This invitation has expired",
            )
            "accepted" -> throw AccountActivationException(
                "invitation_used",
                HttpStatus.CONFLICT,
                "This invitation was already used",
            )
            else -> throw AccountActivationException(
                "invitation_used",
                HttpStatus.CONFLICT,
                "This invitation is no longer pending",
            )
        }
        if (row.expiresAt.isBefore(Instant.now())) {
            throw AccountActivationException(
                "invitation_expired",
                HttpStatus.GONE,
                "This invitation has expired",
            )
        }
        return row
    }

    private fun loadInvitation(token: String): InvitationRow? {
        val hash = InvitationTokens.hash(token)
        return jdbcTemplate.query(
            "SELECT * FROM lookup_invitation_by_token_hash(?)",
            { rs, _ ->
                val metadata = rs.getString("metadata")?.let { mapper.readTree(it) }
                InvitationRow(
                    invitationId = rs.getObject("invitation_id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    email = rs.getString("email"),
                    fullName = rs.getString("full_name"),
                    status = rs.getString("status"),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                    organisationName = rs.getString("organisation_name"),
                    propertyName = metadata?.path("propertyName")?.asString()?.takeIf { it.isNotBlank() },
                    realm = realmOf(metadata),
                )
            },
            hash,
        ).singleOrNull()
    }

    private fun insertGrant(
        grantHash: String,
        invitationId: UUID?,
        tenantId: UUID?,
        email: String,
        realm: String,
    ) {
        transactionTemplate.execute {
            jdbcTemplate.query(
                { connection ->
                    val statement = connection.prepareStatement(
                        "SELECT insert_account_setup_grant(?, ?, ?, ?, ?, ?)",
                    )
                    statement.setString(1, grantHash)
                    statement.setObject(2, invitationId)
                    statement.setObject(3, tenantId)
                    statement.setString(4, email)
                    statement.setString(5, realm)
                    statement.setInt(6, GRANT_TTL_SECONDS)
                    statement
                },
                { rs, _ -> rs.getObject(1, UUID::class.java) },
            )
        }
    }

    private fun consumeGrant(setupGrant: String): GrantRow {
        val rows = jdbcTemplate.query(
            "SELECT * FROM consume_account_setup_grant(?)",
            { rs, _ ->
                GrantRow(
                    invitationId = rs.getObject("invitation_id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    email = rs.getString("email"),
                    realm = rs.getString("realm"),
                )
            },
            InvitationTokens.hash(setupGrant),
        )
        return rows.singleOrNull() ?: throw AccountActivationException(
            "code_expired",
            HttpStatus.UNPROCESSABLE_CONTENT,
            "That code has expired",
        )
    }

    private fun acceptInvitation(
        token: String,
        issuer: String,
        subject: String,
        email: String,
        fullName: String?,
    ) {
        try {
            transactionTemplate.execute {
                jdbcTemplate.query(
                    "SELECT * FROM accept_tenant_user_invitation(?, ?, ?, ?, ?)",
                    { rs, _ -> rs.getObject("invitation_id", UUID::class.java) },
                    InvitationTokens.hash(token),
                    issuer,
                    subject,
                    email,
                    fullName,
                )
            }
        } catch (ex: DataAccessException) {
            throw AccountActivationException(
                "invitation_used",
                HttpStatus.CONFLICT,
                "This invitation could not be accepted",
            )
        }
    }

    private fun findRecoverableAccount(email: String): RecoverableAccount? {
        val tenant = jdbcTemplate.query(
            """
            SELECT id::text AS subject, tenant_id, full_name
            FROM users
            WHERE lower(email) = ? AND deleted_at IS NULL AND is_active = true
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                RecoverableAccount(
                    subjectRef = rs.getString("subject"),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    fullName = rs.getString("full_name"),
                    realm = HOSPITALITY_REALM,
                )
            },
            email,
        ).singleOrNull()
        if (tenant != null) return tenant
        return jdbcTemplate.query(
            """
            SELECT id::text AS subject, full_name
            FROM platform_users
            WHERE lower(email) = ? AND deleted_at IS NULL
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                RecoverableAccount(
                    subjectRef = rs.getString("subject"),
                    tenantId = null,
                    fullName = rs.getString("full_name"),
                    realm = PLATFORM_REALM,
                )
            },
            email,
        ).singleOrNull()
    }

    private fun toDetails(row: InvitationRow) = InvitationDetails(
        inviteeName = row.fullName?.trim()?.takeIf { it.isNotEmpty() } ?: row.email.substringBefore("@"),
        maskedEmail = maskEmail(row.email),
        organisationName = row.organisationName,
        propertyName = row.propertyName,
        expiresAt = row.expiresAt,
        status = row.status.uppercase(Locale.ROOT),
        allowedCredentials = if (row.realm == PLATFORM_REALM) listOf("passkey", "totp") else listOf("password"),
    )

    private fun requirePassword(password: String?): String {
        val secret = password?.trim().orEmpty()
        if (secret.length < 10) {
            throw AccountActivationException(
                "unknown",
                HttpStatus.UNPROCESSABLE_CONTENT,
                "A password is required",
            )
        }
        return secret
    }

    private fun requireProvisioner(): IdentityProvisionerPort =
        identities.getIfAvailable()
            ?: throw unreachable("Identity provider is not configured")

    private fun unwind(
        provisioner: IdentityProvisionerPort,
        subjectId: String,
        realm: String,
        alreadyExisted: Boolean,
    ) {
        if (alreadyExisted) return
        runCatching { provisioner.delete(subjectId, realm) }
    }

    private fun realmOf(metadata: JsonNode?): String {
        val source = metadata?.path("source")?.asString()
        return if (source == "platform_onboarding") PLATFORM_REALM else HOSPITALITY_REALM
    }

    private fun issuerFor(realm: String): String =
        if (realm == PLATFORM_REALM) {
            activationProperties.platformIssuer
        } else {
            activationProperties.hospitalityIssuer
        }

    private fun notFound() = AccountActivationException(
        "invitation_not_found",
        HttpStatus.NOT_FOUND,
        "We couldn't find that invitation",
    )

    private fun unreachable(detail: String) = AccountActivationException(
        "service_unreachable",
        HttpStatus.SERVICE_UNAVAILABLE,
        detail,
    )

    private data class InvitationRow(
        val invitationId: UUID,
        val tenantId: UUID,
        val email: String,
        val fullName: String?,
        val status: String,
        val expiresAt: Instant,
        val organisationName: String,
        val propertyName: String?,
        val realm: String,
    )

    private data class GrantRow(
        val invitationId: UUID?,
        val tenantId: UUID?,
        val email: String,
        val realm: String,
    )

    private data class RecoverableAccount(
        val subjectRef: String,
        val tenantId: UUID?,
        val fullName: String?,
        val realm: String,
    )

    private companion object {
        const val GRANT_TTL_SECONDS = 300
        const val HOSPITALITY_REALM = "peak-hospitality"
        const val PLATFORM_REALM = "peak-platform"
        const val CODE_EVENT = "account.activation.code.issued"

        fun maskEmail(email: String): String {
            val at = email.indexOf('@')
            if (at <= 0) return "****"
            return email.first() + "****" + email.substring(at)
        }

        fun splitName(fullName: String?, email: String): Pair<String, String> {
            val parts = fullName?.trim()?.split(Regex("\\s+")).orEmpty().filter { it.isNotBlank() }
            val first = parts.firstOrNull() ?: email.substringBefore("@")
            val last = parts.drop(1).joinToString(" ").ifBlank { "User" }
            return first to last
        }
    }
}
