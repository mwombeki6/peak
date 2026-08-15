package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@ConfigurationProperties(prefix = "peak.security.device-pairing")
data class DevicePairingProperties(
    val codeValidity: Duration = Duration.ofMinutes(5),
    /**
     * A six-digit code is guessable by anyone willing to try. This is what stops an attacker
     * pairing their own terminal into someone's restaurant by working through the space.
     */
    val maxAttempts: Int = 5,
)

/**
 * Turns a terminal that has generated a keypair into a terminal that belongs to a hotel.
 *
 * The manager approving is already strongly authenticated in the PMS, so the decision carries
 * their authority without asking them to perform a browser login at the physical terminal —
 * which would cost an installation visit and buy nothing.
 *
 * The six digits are a lookup, not a credential. What the device holds is an opaque device code
 * and a private key; the pairing code merely finds the request, briefly and a bounded number of
 * times. Treating it as the credential would put a hotel's till behind a million guesses.
 *
 * Pending requests have no tenant. Inserts and lookups go through SECURITY DEFINER functions
 * owned by `pms_device_pairing_owner`, so ordinary tenant RLS cannot read a waiting code.
 * This service never writes `pos_terminals`.
 */
@Service
class DevicePairingService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val properties: DevicePairingProperties,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    /** What the manager chooses when they approve. Mode is workspace routing, not authority. */
    data class Approval(
        val propertyId: UUID,
        val outletId: UUID?,
        val terminalName: String,
        val mode: String,
    )

    data class PairingRequest(
        val deviceCode: String,
        val code: String,
        val fingerprint: String,
        val expiresAt: java.time.Instant,
    )

    data class PairedDevice(val deviceId: UUID, val deviceCode: String)

    /**
     * Called by a terminal on first launch, holding a keypair it generated itself.
     *
     * Unauthenticated on purpose: at this moment nobody has decided which hotel this device
     * belongs to, and it has nothing to authenticate with. Everything it can do until a manager
     * approves is wait.
     */
    fun requestPairing(publicKey: String): PairingRequest {
        val normalizedKey = publicKey.trim()
        val fingerprint = fingerprint(normalizedKey)
        val deviceCode = "dev_" + randomToken(32)
        val code = (1..CODE_DIGITS).map { random.nextInt(10) }.joinToString("")
        val expiresAt = clock.instant().plus(properties.codeValidity)

        transactionTemplate.executeWithoutResult {
            jdbcTemplate.queryForObject(
                "SELECT abandon_colliding_device_pairings(?, ?)",
                Int::class.java,
                code,
                normalizedKey,
            )
            jdbcTemplate.queryForObject(
                "SELECT insert_pending_device_pairing(?, ?, ?, ?, ?)",
                UUID::class.java,
                deviceCode,
                normalizedKey,
                fingerprint,
                code,
                java.sql.Timestamp.from(expiresAt),
            )
        }

        return PairingRequest(deviceCode, code, fingerprint, expiresAt)
    }

    /**
     * A manager binds the waiting terminal to a property, outlet, name and mode.
     *
     * @throws IllegalStateException when the code has been guessed at too often. Distinct from
     *   a wrong code so an operator can tell "I mistyped" from "someone is trying codes".
     */
    fun approve(
        tenantId: UUID,
        pairingCode: String,
        approval: Approval,
        actorId: UUID,
    ): PairedDevice {
        val result = requireNotNull(
            transactionTemplate.execute {
                databaseSessionContext.bind(RequestIdentity.Tenant(tenantId, actorId))
                val request = jdbcTemplate.query(
                    """
                    SELECT id, device_code, public_key, key_fingerprint, attempts, expires_at
                    FROM lock_pending_device_pairing(?)
                    """.trimIndent(),
                    { rs, _ ->
                        Pending(
                            id = rs.getObject("id", UUID::class.java),
                            deviceCode = rs.getString("device_code"),
                            publicKey = rs.getString("public_key"),
                            fingerprint = rs.getString("key_fingerprint"),
                            attempts = rs.getInt("attempts"),
                            expiresAt = rs.getTimestamp("expires_at").toInstant(),
                        )
                    },
                    pairingCode.trim(),
                ).firstOrNull()

                if (request == null) {
                    jdbcTemplate.queryForObject(
                        "SELECT record_device_pairing_miss()",
                        Int::class.java,
                    )
                    return@execute ApprovalResult.Failure(
                        IllegalArgumentException("That pairing code is not waiting for approval"),
                    )
                }

                if (request.attempts >= properties.maxAttempts) {
                    return@execute ApprovalResult.Failure(
                        IllegalStateException(
                            "This pairing has seen too many wrong codes. Restart pairing on the terminal.",
                        ),
                    )
                }
                if (!request.expiresAt.isAfter(clock.instant())) {
                    jdbcTemplate.queryForObject(
                        "SELECT mark_device_pairing_expired(?)",
                        Int::class.java,
                        request.id,
                    )
                    return@execute ApprovalResult.Failure(
                        IllegalArgumentException("That pairing code has expired"),
                    )
                }

                require(approval.mode in ALLOWED_MODES) {
                    "Unsupported device mode: ${approval.mode}"
                }
                require(approval.terminalName.isNotBlank()) {
                    "A terminal name is required"
                }

                // The property must belong to the approving manager's tenant. Without this a
                // pairing code seen across a lobby could bind a terminal into another hotel.
                val propertyTenant = jdbcTemplate.query(
                    "SELECT tenant_id FROM properties WHERE id = ?",
                    { rs, _ -> rs.getObject("tenant_id", UUID::class.java) },
                    approval.propertyId,
                ).firstOrNull()
                require(propertyTenant == tenantId) {
                    "A terminal can only be paired into a property of your own tenant"
                }

                val deviceId = UUID.randomUUID()
                jdbcTemplate.update(
                    """
                    INSERT INTO paired_devices (
                        id, tenant_id, property_id, outlet_id, device_code, public_key,
                        key_fingerprint, key_version, terminal_name, mode, status,
                        paired_at, paired_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 'active', now(), ?)
                    """.trimIndent(),
                    deviceId,
                    tenantId,
                    approval.propertyId,
                    approval.outletId,
                    request.deviceCode,
                    request.publicKey,
                    request.fingerprint,
                    approval.terminalName.trim(),
                    approval.mode,
                    actorId,
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO device_key_history (
                        tenant_id, device_id, public_key, key_fingerprint, key_version
                    ) VALUES (?, ?, ?, ?, 1)
                    """.trimIndent(),
                    tenantId,
                    deviceId,
                    request.publicKey,
                    request.fingerprint,
                )
                jdbcTemplate.queryForObject(
                    "SELECT mark_device_pairing_approved(?, ?, ?, ?)",
                    Int::class.java,
                    request.id,
                    tenantId,
                    actorId,
                    deviceId,
                )

                ApprovalResult.Ok(PairedDevice(deviceId, request.deviceCode))
            },
        )
        return result.getOrThrow()
    }

    fun revoke(tenantId: UUID, deviceId: UUID, actorId: UUID) {
        transactionTemplate.executeWithoutResult {
            databaseSessionContext.bind(RequestIdentity.Tenant(tenantId, actorId))
            val updated = jdbcTemplate.update(
                """
                UPDATE paired_devices
                SET status = 'revoked', revoked_at = now(), revoked_by = ?
                WHERE id = ? AND tenant_id = ? AND status = 'active'
                """.trimIndent(),
                actorId,
                deviceId,
                tenantId,
            )
            require(updated == 1) { "That device is not registered in this tenant" }
            jdbcTemplate.update(
                """
                UPDATE operational_sessions
                SET revoked_at = now()
                WHERE device_id = ? AND tenant_id = ? AND revoked_at IS NULL
                """.trimIndent(),
                deviceId,
                tenantId,
            )
        }
    }

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private fun fingerprint(publicKey: String): String {
        val decoded = try {
            Base64.getDecoder().decode(publicKey)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Public key must be Base64")
        }
        require(decoded.isNotEmpty()) { "Public key must be Base64" }
        val digest = MessageDigest.getInstance("SHA-256").digest(decoded)
        return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest.copyOf(FINGERPRINT_BYTES))
    }

    private sealed class ApprovalResult {
        data class Ok(val device: PairedDevice) : ApprovalResult()
        data class Failure(val error: RuntimeException) : ApprovalResult()

        fun getOrThrow(): PairedDevice = when (this) {
            is Ok -> device
            is Failure -> throw error
        }
    }

    private data class Pending(
        val id: UUID,
        val deviceCode: String,
        val publicKey: String,
        val fingerprint: String,
        val attempts: Int,
        val expiresAt: java.time.Instant,
    )

    private companion object {
        const val CODE_DIGITS = 6
        const val FINGERPRINT_BYTES = 8
        val ALLOWED_MODES = setOf("POS", "KITCHEN_DISPLAY", "BAR_DISPLAY", "CASHIER")
    }
}
