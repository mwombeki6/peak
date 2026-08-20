package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.outbound.EstablishPassword
import com.mwombeki.peak.shared.outbound.IdentityCredentialRejectedException
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
    private val databaseSessionContext: DatabaseSessionContext,
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
            destination = row.contact(),
            subjectRef = row.invitationId.toString(),
            tenantId = row.tenantId,
            fullName = row.fullName,
        )
    }

    fun verifyInvitationCode(token: String, code: String): SetupGrant {
        val row = requirePending(token)
        confirmCode(VerificationPurpose.ACCOUNT_ACTIVATION, row.contact(), code)
        val grant = InvitationTokens.newToken()
        insertGrant(
            grantHash = InvitationTokens.hash(grant),
            invitationId = row.invitationId,
            tenantId = row.tenantId,
            email = row.email,
            phoneNumber = row.phoneNumber,
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
        // Checked before the grant is spent: a password the policy would refuse must not cost
        // the person their verified code and send them back to the start.
        val username = row.username()
        val secret = requireCredential(password, username)
        val grant = consumeGrant(setupGrant)
        if (grant.email != row.email || grant.phoneNumber != row.phoneNumber) throw notFound()
        val names = splitName(row.fullName, username)
        val userId = UUID.randomUUID()
        val provisioner = requireProvisioner()
        val provisioned = try {
            provisioner.provision(
                ProvisionIdentity(
                    username = username,
                    email = row.email,
                    phoneNumber = row.phoneNumber,
                    firstName = names.first,
                    lastName = names.second,
                    tenantId = row.tenantId.toString(),
                    peakUserId = userId.toString(),
                    realm = row.realm,
                    emailVerified = row.email != null,
                ),
            )
        } catch (ex: IdentityProvisioningException) {
            throw unreachable("Identity could not be created")
        }
        try {
            provisioner.establishPassword(
                EstablishPassword(provisioned.subjectId, secret, row.realm),
            )
            if (row.email != null) {
                provisioner.markEmailVerified(MarkEmailVerified(provisioned.subjectId, row.realm))
            }
            provisioner.clearRequiredActions(provisioned.subjectId, row.realm)
        } catch (ex: IdentityCredentialRejectedException) {
            unwind(provisioner, provisioned.subjectId, row.realm, provisioned.alreadyExisted)
            throw weakPassword()
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
                phoneNumber = row.phoneNumber,
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

    fun startRecovery(identifier: String): CodeDispatch {
        val normalized = normalize(identifier)
        val existing = findRecoverableAccount(normalized)
        if (existing != null) {
            return dispatchCode(
                purpose = VerificationPurpose.ACCOUNT_RECOVERY,
                destination = existing.contact,
                subjectRef = existing.subjectRef,
                tenantId = existing.tenantId,
                fullName = existing.fullName,
            )
        }
        // Same shape whether or not the identifier exists.
        return CodeDispatch(
            maskedEmail = maskIdentifier(normalized),
            resendAvailableInSeconds = 60,
            expiresInSeconds = 600,
        )
    }

    fun verifyRecoveryCode(identifier: String, code: String): SetupGrant {
        val normalized = normalize(identifier)
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
            email = if (account.contact.contains('@')) account.contact else null,
            phoneNumber = if (account.contact.contains('@')) null else account.contact,
            realm = account.realm,
        )
        return SetupGrant(setupGrant = grant, expiresInSeconds = GRANT_TTL_SECONDS)
    }

    fun setRecoveryCredential(
        identifier: String,
        setupGrant: String,
        password: String?,
    ): CredentialAccepted {
        val normalized = normalize(identifier)
        val grant = consumeGrant(setupGrant)
        val grantContact = grant.phoneNumber ?: grant.email
        if (grantContact != normalized) {
            throw AccountActivationException(
                "code_incorrect",
                HttpStatus.UNPROCESSABLE_CONTENT,
                "That code isn't right",
            )
        }
        val secret = requireCredential(password, normalized)
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
                    email = if (normalized.contains('@')) normalized else account.contact.takeIf { it.contains('@') },
                    phoneNumber = if (normalized.contains('@')) null else normalized,
                    firstName = splitName(account.fullName, normalized).first,
                    lastName = splitName(account.fullName, normalized).second,
                    tenantId = (account.tenantId ?: UUID(0, 0)).toString(),
                    peakUserId = account.subjectRef,
                    realm = account.realm,
                    emailVerified = normalized.contains('@'),
                ),
            )
            provisioner.establishPassword(
                EstablishPassword(provisioned.subjectId, secret, account.realm),
            )
            provisioner.clearRequiredActions(provisioned.subjectId, account.realm)
        } catch (ex: IdentityCredentialRejectedException) {
            throw weakPassword()
        } catch (ex: IdentityProvisioningException) {
            throw unreachable("Identity credential could not be stored")
        }
        return CredentialAccepted(signedIn = false, redirectTo = null)
    }

    private fun dispatchCode(
        purpose: VerificationPurpose,
        destination: String,
        subjectRef: String?,
        tenantId: UUID?,
        fullName: String?,
    ): CodeDispatch {
        val receipt = try {
            verification.request(
                RequestVerificationCommand(
                    purpose = purpose,
                    destination = destination,
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
            val viaSms = !destination.contains('@')
            transactionTemplate.execute {
                databaseSessionContext.bind(RequestIdentity.Public(tenantId = tenantId))
                outbox.enqueue(
                    OutboxEventCommand(
                        aggregateType = "account_setup_grants",
                        aggregateId = receipt.id,
                        tenantId = tenantId,
                        eventType = CODE_EVENT,
                        destination = if (viaSms) OutboxDestination.SMS else OutboxDestination.EMAIL,
                        payload = if (viaSms) {
                            mapOf(
                                "phoneNumber" to destination,
                                "fullName" to (fullName ?: "Peak user"),
                                "code" to receipt.code,
                                "expiresAt" to receipt.expiresAt.toString(),
                            )
                        } else {
                            mapOf(
                                "email" to destination,
                                "fullName" to (fullName ?: "Peak user"),
                                "code" to receipt.code,
                                "expiresAt" to receipt.expiresAt.toString(),
                            )
                        },
                        priority = 4,
                    ),
                )
            }
        }
        val ttl = Duration.between(Instant.now(), receipt.expiresAt).seconds.coerceAtLeast(1).toInt()
        return CodeDispatch(
            maskedEmail = maskIdentifier(destination),
            resendAvailableInSeconds = 60,
            expiresInSeconds = ttl,
            debugCode = receipt.code.takeIf { activationProperties.exposeCodeInResponse },
        )
    }

    private fun confirmCode(purpose: VerificationPurpose, destination: String, code: String) {
        val outcome = verification.confirm(
            ConfirmVerificationCommand(purpose = purpose, destination = destination, code = code),
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
                    phoneNumber = rs.getString("phone_number"),
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
        email: String?,
        phoneNumber: String?,
        realm: String,
    ) {
        transactionTemplate.execute {
            jdbcTemplate.query(
                { connection ->
                    val statement = connection.prepareStatement(
                        "SELECT insert_account_setup_grant(?, ?, ?, ?, ?, ?, ?)",
                    )
                    statement.setString(1, grantHash)
                    statement.setObject(2, invitationId)
                    statement.setObject(3, tenantId)
                    statement.setString(4, email)
                    statement.setString(5, phoneNumber)
                    statement.setString(6, realm)
                    statement.setInt(7, GRANT_TTL_SECONDS)
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
                    phoneNumber = rs.getString("phone_number"),
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
        email: String?,
        phoneNumber: String?,
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

    private fun findRecoverableAccount(identifier: String): RecoverableAccount? {
        val phone = identifier.takeIf { !it.contains('@') }
        val email = identifier.takeIf { it.contains('@') }
        if (phone != null) {
            val platformUser = jdbcTemplate.query(
                """
                SELECT id::text AS subject, full_name, phone_number
                FROM platform_users
                WHERE phone_number = ? AND deleted_at IS NULL
                LIMIT 1
                """.trimIndent(),
                { rs, _ ->
                    RecoverableAccount(
                        subjectRef = rs.getString("subject"),
                        tenantId = null,
                        fullName = rs.getString("full_name"),
                        realm = PLATFORM_REALM,
                        contact = rs.getString("phone_number"),
                    )
                },
                phone,
            ).singleOrNull()
            if (platformUser != null) return platformUser
            val tenantUser = jdbcTemplate.query(
                """
                SELECT id::text AS subject, tenant_id, full_name, phone_number
                FROM users
                WHERE phone_number = ? AND deleted_at IS NULL AND is_active = true
                LIMIT 1
                """.trimIndent(),
                { rs, _ ->
                    RecoverableAccount(
                        subjectRef = rs.getString("subject"),
                        tenantId = rs.getObject("tenant_id", UUID::class.java),
                        fullName = rs.getString("full_name"),
                        realm = HOSPITALITY_REALM,
                        contact = rs.getString("phone_number"),
                    )
                },
                phone,
            ).singleOrNull()
            return tenantUser
        }
        val tenant = jdbcTemplate.query(
            """
            SELECT id::text AS subject, tenant_id, full_name, email
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
                    contact = rs.getString("email"),
                )
            },
            email,
        ).singleOrNull()
        if (tenant != null) return tenant
        return jdbcTemplate.query(
            """
            SELECT id::text AS subject, full_name, email
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
                    contact = rs.getString("email"),
                )
            },
            email,
        ).singleOrNull()
    }

    private fun toDetails(row: InvitationRow) = InvitationDetails(
        inviteeName = row.fullName?.trim()?.takeIf { it.isNotEmpty() }
            ?: row.phoneNumber ?: row.email?.substringBefore("@") ?: "Operator",
        maskedEmail = row.email?.let { maskIdentifier(it) },
        maskedPhone = row.phoneNumber?.let { maskIdentifier(it) },
        organisationName = row.organisationName,
        propertyName = row.propertyName,
        expiresAt = row.expiresAt,
        status = row.status.uppercase(Locale.ROOT),
        allowedCredentials = listOf("password"),
    )

    /**
     * Peak's copy of the realm's password rules.
     *
     * Keycloak enforces the real policy and gets the final say; this exists so the common
     * refusal costs a round trip instead of a spent setup grant. It must stay in step with
     * `passwordPolicy` in ops/keycloak/peak-hospitality-realm.json.
     */
    private fun requireCredential(password: String?, username: String): String {
        val secret = password?.trim().orEmpty()
        val strong = secret.length >= MIN_PASSWORD_LENGTH &&
            secret.any { it.isDigit() } &&
            secret.any { it.isLowerCase() } &&
            !secret.equals(username, ignoreCase = true) &&
            !secret.equals(username.substringBefore('@'), ignoreCase = true)
        if (!strong) throw weakPassword()
        return secret
    }

    private fun weakPassword() = AccountActivationException(
        "password_too_weak",
        HttpStatus.UNPROCESSABLE_CONTENT,
        "That password does not meet the policy",
    )

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
        val email: String?,
        val phoneNumber: String?,
        val fullName: String?,
        val status: String,
        val expiresAt: Instant,
        val organisationName: String,
        val propertyName: String?,
        val realm: String,
    ) {
        /** The contact this invitation was issued to: phone first, email fallback. */
        fun contact(): String = phoneNumber ?: requireNotNull(email) {
            "Invitation has neither phone nor email"
        }

        /** The identity-provider username: phone when present, else the email. */
        fun username(): String = phoneNumber ?: requireNotNull(email) {
            "Invitation has neither phone nor email"
        }
    }

    private data class GrantRow(
        val invitationId: UUID?,
        val tenantId: UUID?,
        val email: String?,
        val phoneNumber: String?,
        val realm: String,
    )

    private data class RecoverableAccount(
        val subjectRef: String,
        val tenantId: UUID?,
        val fullName: String?,
        val realm: String,
        val contact: String,
    )

    private companion object {
        const val GRANT_TTL_SECONDS = 300
        const val MIN_PASSWORD_LENGTH = 10
        const val HOSPITALITY_REALM = "peak-hospitality"
        const val PLATFORM_REALM = "peak-platform"
        const val CODE_EVENT = "account.activation.code.issued"

        fun maskIdentifier(identifier: String): String {
            if (identifier.contains('@')) {
                val at = identifier.indexOf('@')
                return identifier.first() + "****" + identifier.substring(at)
            }
            if (identifier.length >= 8) {
                return identifier.take(4) + "****" + identifier.takeLast(4)
            }
            return "****"
        }

        fun normalize(identifier: String): String =
            identifier.trim().let {
                if (it.contains('@')) it.lowercase(Locale.ROOT) else it
            }

        fun splitName(fullName: String?, identifier: String): Pair<String, String> {
            val parts = fullName?.trim()?.split(Regex("\\s+")).orEmpty().filter { it.isNotBlank() }
            val first = parts.firstOrNull()
                ?: identifier.substringBefore('@').takeIf { it.isNotBlank() } ?: "Operator"
            val last = parts.drop(1).joinToString(" ").ifBlank { "User" }
            return first to last
        }
    }
}
