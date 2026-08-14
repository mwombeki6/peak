package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.shared.secrets.SecretReferenceResolver
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * How a staff PIN is protected, and how forgiving the lockout is.
 *
 * The pepper is the load-bearing one. A six-digit PIN has a million possibilities, so a stolen
 * database with plain bcrypt hashes gives up every PIN in the hotel given time. Peppering with
 * a secret held in configuration rather than data means the dump alone is worthless.
 *
 * It also cannot be added later: re-peppering needs plaintext nobody keeps, so retrofitting
 * would mean resetting every staff member's PIN. That is what made it a decision to take now.
 */
@ConfigurationProperties(prefix = "peak.security.staff-credentials")
data class StaffCredentialProperties(
    /** Resolved through [SecretReferenceResolver]; production must not use the default. */
    val pepperSecretRef: String = "literal:peak-local-development-pepper-not-for-production",
    val maxFailedAttempts: Int = 5,
    val lockoutDuration: Duration = Duration.ofMinutes(15),
    val activationValidity: Duration = Duration.ofHours(72),
)

/** Returned once, at issue. Peak never stores or shows the plaintext again. */
data class ActivationSecret(val plaintext: String, val expiresAt: java.time.Instant)

/**
 * Issues activation secrets and verifies staff PINs.
 *
 * The shape is deliberately asymmetric: a manager may **reset** a credential and may never
 * **read** one. A manager who knows everyone's PIN can act as anyone, and from that point the
 * audit trail names the wrong person for every action — worse than no audit trail, because it
 * reads as evidence.
 *
 * Device trust is not checked here. A registered-device gate runs *before* this one, so an
 * unregistered terminal never reaches a PIN check at all; keeping the two separate means
 * neither can be mistaken for the other, and the device half can be revoked without touching
 * anybody's PIN.
 */
@Service
class StaffCredentialService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val secretReferenceResolver: SecretReferenceResolver,
    private val properties: StaffCredentialProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val encoder = BCryptPasswordEncoder(BCRYPT_COST)
    private val random = SecureRandom()

    /**
     * Starts or resets a staff member's access.
     *
     * Invalidates any existing PIN immediately, because this is also the reset path: a manager
     * whose employee has forgotten their PIN issues a new secret, and the old credential must
     * stop working at that moment rather than when the new one is set.
     */
    fun issueActivation(tenantId: UUID, userId: UUID, actorId: UUID): ActivationSecret {
        val plaintext = (1..ACTIVATION_DIGITS)
            .map { random.nextInt(10) }
            .joinToString("")
        val expiresAt = clock.instant().plus(properties.activationValidity)

        transactionTemplate.executeWithoutResult {
            // The old PIN dies now, not when the new one is chosen. Otherwise a forgotten PIN
            // stays live for as long as the staff member takes to come back to a terminal.
            jdbcTemplate.update(
                "DELETE FROM staff_credentials WHERE tenant_id = ? AND user_id = ?",
                tenantId, userId,
            )
            // Only one secret may be live, so a previous unconsumed one is retired rather than
            // left to race with this one.
            jdbcTemplate.update(
                """
                UPDATE staff_activation_secrets
                SET consumed_at = now()
                WHERE tenant_id = ? AND user_id = ? AND consumed_at IS NULL
                """.trimIndent(),
                tenantId, userId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO staff_activation_secrets (
                    tenant_id, user_id, secret_hash, issued_by, expires_at
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                tenantId, userId, encoder.encode(pepper(plaintext)), actorId,
                java.sql.Timestamp.from(expiresAt),
            )
        }

        return ActivationSecret(plaintext, expiresAt)
    }

    /** Redeems a one-time secret and sets the PIN the staff member chose. */
    fun activate(tenantId: UUID, staffNumber: String, secret: String, pin: String) {
        requireUsablePin(pin)

        transactionTemplate.executeWithoutResult {
            val userId = resolveStaff(tenantId, staffNumber)
                ?: throw IllegalArgumentException("Staff number was not found")

            val live = jdbcTemplate.query(
                """
                SELECT id, secret_hash
                FROM staff_activation_secrets
                WHERE tenant_id = ? AND user_id = ?
                  AND consumed_at IS NULL
                  AND expires_at > now()
                FOR UPDATE
                """.trimIndent(),
                { rs, _ ->
                    rs.getObject("id", UUID::class.java) to rs.getString("secret_hash")
                },
                tenantId, userId,
            ).firstOrNull()
                ?: throw IllegalArgumentException(
                    "No activation is waiting for this staff number. Ask a manager to issue one.",
                )

            require(encoder.matches(pepper(secret.trim()), live.second)) {
                "Activation code does not match"
            }

            jdbcTemplate.update(
                "UPDATE staff_activation_secrets SET consumed_at = now() WHERE id = ?",
                live.first,
            )
            jdbcTemplate.update(
                """
                INSERT INTO staff_credentials (user_id, tenant_id, pin_hash)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                SET pin_hash = EXCLUDED.pin_hash,
                    pin_set_at = now(),
                    failed_attempts = 0,
                    locked_until = NULL,
                    updated_at = now()
                """.trimIndent(),
                userId, tenantId, encoder.encode(pepper(pin)),
            )
        }
    }

    /**
     * @return the staff member's user id, or null for any failure — wrong number, wrong PIN,
     *   locked, or never activated. Deliberately indistinguishable: telling a caller which one
     *   it was tells an attacker which staff numbers exist.
     */
    fun verify(tenantId: UUID, staffNumber: String, pin: String): UUID? =
        transactionTemplate.execute {
            val userId = resolveStaff(tenantId, staffNumber) ?: return@execute null

            val credential = jdbcTemplate.query(
                """
                SELECT pin_hash, failed_attempts, locked_until
                FROM staff_credentials
                WHERE tenant_id = ? AND user_id = ?
                FOR UPDATE
                """.trimIndent(),
                { rs, _ ->
                    Credential(
                        pinHash = rs.getString("pin_hash"),
                        failedAttempts = rs.getInt("failed_attempts"),
                        lockedUntil = rs.getTimestamp("locked_until")?.toInstant(),
                    )
                },
                tenantId, userId,
            ).firstOrNull() ?: return@execute null

            // A locked account is refused without checking the PIN, so a lockout cannot be
            // probed for correctness.
            if (credential.lockedUntil?.isAfter(clock.instant()) == true) {
                return@execute null
            }

            if (!encoder.matches(pepper(pin), credential.pinHash)) {
                recordFailure(tenantId, userId, credential.failedAttempts + 1)
                return@execute null
            }

            // Success forgives what came before, so ordinary mistyping across a shift never
            // accumulates into a lockout that strands someone mid-service.
            jdbcTemplate.update(
                """
                UPDATE staff_credentials
                SET failed_attempts = 0, locked_until = NULL,
                    last_verified_at = now(), updated_at = now()
                WHERE tenant_id = ? AND user_id = ?
                """.trimIndent(),
                tenantId, userId,
            )
            userId
        }

    private fun recordFailure(tenantId: UUID, userId: UUID, attempts: Int) {
        val lockedUntil = if (attempts >= properties.maxFailedAttempts) {
            java.sql.Timestamp.from(clock.instant().plus(properties.lockoutDuration))
        } else {
            null
        }
        jdbcTemplate.update(
            """
            UPDATE staff_credentials
            SET failed_attempts = ?, locked_until = ?, updated_at = now()
            WHERE tenant_id = ? AND user_id = ?
            """.trimIndent(),
            attempts, lockedUntil, tenantId, userId,
        )
    }

    private fun resolveStaff(tenantId: UUID, staffNumber: String): UUID? =
        jdbcTemplate.query(
            """
            SELECT id FROM users
            WHERE tenant_id = ?
              AND staff_number = ?
              AND status = 'active'
              AND is_active
              AND deleted_at IS NULL
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            tenantId, staffNumber.trim(),
        ).firstOrNull()

    /**
     * Rejects the PINs an attacker tries first. With a million possibilities the guessable
     * fraction matters: a hotel where several staff pick 123456 is a hotel where one guess
     * works somewhere.
     */
    private fun requireUsablePin(pin: String) {
        require(PIN.matches(pin)) { "A PIN must be exactly six digits" }
        require(pin.toSet().size > 1) { "A PIN must not be the same digit repeated" }
        require(!isRun(pin)) { "A PIN must not be a run of consecutive digits" }
    }

    private fun isRun(pin: String): Boolean {
        val ascending = pin.zipWithNext().all { (a, b) -> b - a == 1 }
        val descending = pin.zipWithNext().all { (a, b) -> a - b == 1 }
        return ascending || descending
    }

    /**
     * Binds the secret to a value held outside the database.
     *
     * HMAC rather than concatenation so the pepper cannot be recovered by length-extension or
     * by comparing hashes, and so the bcrypt input stays a fixed size well inside its 72-byte
     * limit whatever the caller passed.
     */
    private fun pepper(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(
            SecretKeySpec(
                secretReferenceResolver.resolve(properties.pepperSecretRef)
                    .toByteArray(StandardCharsets.UTF_8),
                "HmacSHA256",
            ),
        )
        return Base64.getEncoder()
            .encodeToString(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    private data class Credential(
        val pinHash: String,
        val failedAttempts: Int,
        val lockedUntil: java.time.Instant?,
    )

    private companion object {
        /**
         * Cost 12. Roughly a quarter-second per attempt, which is unnoticeable behind one
         * shift-start login and expensive across a million-entry search space.
         */
        const val BCRYPT_COST = 12
        const val ACTIVATION_DIGITS = 9
        val PIN = Regex("^[0-9]{6}$")
    }
}
