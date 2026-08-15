package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.OperationalSessionAuthentication
import com.mwombeki.peak.shared.context.RequestIdentity
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@ConfigurationProperties(prefix = "peak.security.operational-sessions")
data class OperationalSessionProperties(
    val challengeValidity: Duration = Duration.ofMinutes(2),
    val sessionValidity: Duration = Duration.ofHours(8),
)

/**
 * Issues a challenge-response login on a registered device and mints a device-bound
 * operational session.
 *
 * Order is load-bearing. Device trust is checked *before* the PIN: a revoked or unknown
 * terminal never reaches a PIN check, so a stolen PIN on a dead device does nothing and a
 * lockout is not incremented by someone hammering a till that is no longer trusted.
 *
 * The signature is Ed25519 over the challenge nonce, using the public key stored at pairing.
 * The public key is Base64 of the X.509 SubjectPublicKeyInfo encoding Java emits for Ed25519
 * (`KeyPairGenerator.getInstance("Ed25519")`). This is not optional on the first commit —
 * a PIN without a device signature is a PIN anyone can type from a laptop.
 */
@Service
class DeviceSessionService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val credentials: StaffCredentialService,
    private val databaseSessionContext: DatabaseSessionContext,
    private val properties: OperationalSessionProperties,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    data class Challenge(
        val challengeId: UUID,
        val nonce: String,
        val expiresAt: Instant,
    )

    data class OperationalSession(
        val token: String,
        val expiresAt: Instant,
        val deviceId: UUID,
        val propertyId: UUID,
        val tenantId: UUID,
        val userId: UUID,
        val outletId: UUID?,
    )

    fun issueChallenge(deviceCode: String): Challenge? =
        transactionTemplate.execute {
            val device = lookupDevice(deviceCode.trim()) ?: return@execute null
            if (device.status != "active") {
                return@execute null
            }

            databaseSessionContext.bind(RequestIdentity.Public(tenantId = device.tenantId))

            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val expiresAt = clock.instant().plus(properties.challengeValidity)
            val challengeId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO device_login_challenges (
                    id, tenant_id, device_id, nonce, expires_at
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                challengeId,
                device.tenantId,
                device.id,
                nonce,
                java.sql.Timestamp.from(expiresAt),
            )
            Challenge(
                challengeId = challengeId,
                nonce = Base64.getEncoder().encodeToString(nonce),
                expiresAt = expiresAt,
            )
        }

    /**
     * @return a session, or null for any failure — unknown device, revoked device, bad
     *   signature, wrong PIN, lockout. Deliberately indistinguishable over HTTP; tests
     *   assert the revoked-device path separately by observing that a valid PIN is not
     *   consumed as a mistype.
     */
    fun login(
        deviceCode: String,
        challengeId: UUID,
        signature: String,
        staffNumber: String,
        pin: String,
    ): OperationalSession? =
        transactionTemplate.execute {
            val device = lookupDevice(deviceCode.trim()) ?: return@execute null
            if (device.status != "active") {
                return@execute null
            }

            databaseSessionContext.bind(RequestIdentity.Public(tenantId = device.tenantId))

            val challenge = jdbcTemplate.query(
                """
                SELECT nonce, expires_at, consumed_at, device_id
                FROM device_login_challenges
                WHERE id = ? AND tenant_id = ?
                FOR UPDATE
                """.trimIndent(),
                { rs, _ ->
                    ChallengeRow(
                        nonce = rs.getBytes("nonce"),
                        expiresAt = rs.getTimestamp("expires_at").toInstant(),
                        consumedAt = rs.getTimestamp("consumed_at")?.toInstant(),
                        deviceId = rs.getObject("device_id", UUID::class.java),
                    )
                },
                challengeId,
                device.tenantId,
            ).firstOrNull() ?: return@execute null

            if (challenge.consumedAt != null ||
                !challenge.expiresAt.isAfter(clock.instant()) ||
                challenge.deviceId != device.id
            ) {
                return@execute null
            }
            if (!verifySignature(device.publicKey, challenge.nonce, signature)) {
                return@execute null
            }

            jdbcTemplate.update(
                "UPDATE device_login_challenges SET consumed_at = now() WHERE id = ?",
                challengeId,
            )

            val userId = credentials.verify(device.tenantId, staffNumber, pin)
                ?: return@execute null

            val token = OperationalSessionAuthentication.TOKEN_PREFIX + randomToken(32)
            val expiresAt = clock.instant().plus(properties.sessionValidity)
            jdbcTemplate.update(
                """
                INSERT INTO operational_sessions (
                    tenant_id, user_id, device_id, property_id, token_hash, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                device.tenantId,
                userId,
                device.id,
                device.propertyId,
                sha256Hex(token),
                java.sql.Timestamp.from(expiresAt),
            )

            OperationalSession(
                token = token,
                expiresAt = expiresAt,
                deviceId = device.id,
                propertyId = device.propertyId,
                tenantId = device.tenantId,
                userId = userId,
                outletId = device.outletId,
            )
        }

    private fun lookupDevice(deviceCode: String): DeviceRow? =
        jdbcTemplate.query(
            """
            SELECT id, tenant_id, property_id, outlet_id, public_key, status
            FROM lookup_active_paired_device(?)
            """.trimIndent(),
            { rs, _ ->
                DeviceRow(
                    id = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    propertyId = rs.getObject("property_id", UUID::class.java),
                    outletId = rs.getObject("outlet_id", UUID::class.java),
                    publicKey = rs.getString("public_key"),
                    status = rs.getString("status"),
                )
            },
            deviceCode,
        ).firstOrNull()

    private fun verifySignature(publicKeyB64: String, nonce: ByteArray, signatureB64: String): Boolean {
        return try {
            val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)),
            )
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey)
            verifier.update(nonce)
            verifier.verify(Base64.getDecoder().decode(signatureB64.trim()))
        } catch (_: Exception) {
            false
        }
    }

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private data class DeviceRow(
        val id: UUID,
        val tenantId: UUID,
        val propertyId: UUID,
        val outletId: UUID?,
        val publicKey: String,
        val status: String,
    )

    private data class ChallengeRow(
        val nonce: ByteArray,
        val expiresAt: Instant,
        val consumedAt: Instant?,
        val deviceId: UUID,
    )

    private companion object {
        const val NONCE_BYTES = 32

        fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return HexFormat.of().formatHex(digest)
        }
    }
}
