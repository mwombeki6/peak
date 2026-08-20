package com.mwombeki.peak.verification.internal

import com.mwombeki.peak.shared.ephemeral.RateLimitScope
import com.mwombeki.peak.shared.ephemeral.RateLimitStore
import com.mwombeki.peak.shared.secrets.SecretReferenceResolver
import com.mwombeki.peak.verification.api.ConfirmVerificationCommand
import com.mwombeki.peak.verification.api.RequestVerificationCommand
import com.mwombeki.peak.verification.api.VerificationChallengeReceipt
import com.mwombeki.peak.verification.api.VerificationOutcome
import com.mwombeki.peak.verification.api.VerificationPort
import com.mwombeki.peak.verification.api.VerificationThrottledException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * How long a code lives, how many digits, and the throttling bounds every purpose shares.
 *
 * `hmacKeySecretRef` is the same [SecretReferenceResolver] pattern this codebase already uses
 * for the staff PIN pepper — held outside the database, so a stolen table alone yields nothing.
 */
@ConfigurationProperties(prefix = "peak.security.verification")
data class VerificationProperties(
    val hmacKeySecretRef: String = "literal:peak-local-development-verification-key-not-for-production",
    val codeDigits: Int = 6,
    val ttl: Duration = Duration.ofMinutes(10),
    val maxAttempts: Int = 5,
    val sendCooldown: Duration = Duration.ofSeconds(60),
    val maxSendsPerWindow: Int = 5,
    val sendWindow: Duration = Duration.ofHours(1),
    val maxSendsPerIp: Int = 20,
)

/**
 * Purpose-bound OTP challenges: request a code, confirm it. PostgreSQL is authoritative for
 * challenge state, the attempt budget and expiry — see V144's `request_verification_challenge`
 * and `confirm_verification_challenge`. [RateLimitStore] only gates how often a code may be
 * *sent*, and its loss (a Valkey outage falling back to the PostgreSQL adapter) costs a window
 * of throttling, never correctness of what's already issued.
 *
 * Existing `contact_channels` channel-ownership verification (a 256-bit one-time link token) is
 * untouched — a different mechanism for a different job, not something this module replaces.
 */
@Service
class VerificationService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val secretReferenceResolver: SecretReferenceResolver,
    private val rateLimitStore: RateLimitStore,
    private val properties: VerificationProperties,
) : VerificationPort {
    private val random = SecureRandom()

    override fun request(command: RequestVerificationCommand): VerificationChallengeReceipt {
        throttle(RateLimitScope.OTP_SEND_COOLDOWN, command.destination, limit = 1, window = properties.sendCooldown)
        throttle(
            RateLimitScope.REQUESTS_PER_PHONE,
            command.destination,
            limit = properties.maxSendsPerWindow,
            window = properties.sendWindow,
        )
        command.sourceIp?.let {
            throttle(RateLimitScope.REQUESTS_PER_IP, it, limit = properties.maxSendsPerIp, window = properties.sendWindow)
        }

        val code = generateCode()
        val hash = hash(code)
        val row = requireNotNull(
            transactionTemplate.execute {
                jdbcTemplate.query(
                    "SELECT * FROM request_verification_challenge(?, ?, ?, ?, ?, ?, ?)",
                    { rs, _ ->
                        VerificationChallengeReceipt(
                            id = rs.getObject("id", UUID::class.java),
                            code = code,
                            expiresAt = rs.getTimestamp("expires_at").toInstant(),
                        )
                    },
                    command.tenantId,
                    command.purpose.code,
                    command.destination,
                    command.subjectRef,
                    hash,
                    properties.ttl.toMillis() / 1000.0,
                    properties.maxAttempts,
                ).firstOrNull()
            },
        )
        return row
    }

    override fun confirm(command: ConfirmVerificationCommand): VerificationOutcome {
        val hash = hash(command.code.trim())
        return requireNotNull(
            transactionTemplate.execute {
                jdbcTemplate.query(
                    "SELECT * FROM confirm_verification_challenge(?, ?, ?)",
                    { rs, _ ->
                        VerificationOutcome(
                            verified = rs.getBoolean("verified"),
                            subjectRef = rs.getString("subject_ref"),
                        )
                    },
                    command.purpose.code,
                    command.destination,
                    hash,
                ).firstOrNull()
            },
        )
    }

    private fun throttle(scope: RateLimitScope, subject: String, limit: Int, window: Duration) {
        val decision = rateLimitStore.consume(scope, subject, limit, window)
        if (!decision.allowed) {
            throw VerificationThrottledException(
                "Too many verification requests for scope ${scope.keyspace}; retry after ${decision.retryAfter}",
            )
        }
    }

    /** Digits, not a formatted number — leading zeros are valid and must stay. */
    private fun generateCode(): String =
        (1..properties.codeDigits).map { random.nextInt(10) }.joinToString("")

    /**
     * HMAC rather than a bare hash — a bare SHA-256 of a 6-digit value is brute-forceable
     * offline in a way the pepper-keyed version is not, the same reasoning as the staff PIN.
     */
    private fun hash(code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(
            SecretKeySpec(
                secretReferenceResolver.resolve(properties.hmacKeySecretRef).toByteArray(StandardCharsets.UTF_8),
                "HmacSHA256",
            ),
        )
        return Base64.getEncoder().encodeToString(mac.doFinal(code.toByteArray(StandardCharsets.UTF_8)))
    }
}
