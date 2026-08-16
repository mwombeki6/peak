package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    /**
     * How long a rejected approval counts against the tenant that submitted it. A wrong
     * code cannot be attributed to the pairing it was aimed at, so the budget is charged
     * to the approving tenant rather than to every waiting terminal on the platform.
     */
    val approvalMissWindow: Duration = Duration.ofMinutes(5),
    /** Caps unauthenticated pairing creates per public key. No Redis; process-local. */
    val maxCreatesPerKey: Int = 5,
    val createWindow: Duration = Duration.ofMinutes(5),
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
 *
 * An expired (or still-pending) wait may POST the same public key again. That abandons the
 * previous pending row and issues a new pairing code. The pairing code is not a JWT.
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
    private val createLock = Any()
    private val recentCreates = ConcurrentHashMap<String, MutableList<Instant>>()

    /** What the manager chooses when they approve. Mode is workspace routing, not authority. */
    data class Approval(
        val propertyId: UUID,
        val outletId: UUID?,
        val terminalName: String,
        val mode: String,
    )

    data class PairingRequest(
        val id: UUID,
        val deviceCode: String,
        val code: String,
        val fingerprint: String,
        val expiresAt: Instant,
    )

    /**
     * What an unpaired till may learn while it waits. Pending, expired, and denied
     * expose only [status]. Approved adds the device code it already holds, the code
     * expiry, and workspace routing — never tenant, property, or guest data.
     */
    data class PairingStatus(
        val status: String,
        val deviceCode: String? = null,
        val expiresAt: Instant? = null,
        val terminalName: String? = null,
        val mode: String? = null,
    )

    data class PairedDevice(val deviceId: UUID, val deviceCode: String)

    /**
     * Called by a terminal on first launch, holding a keypair it generated itself.
     *
     * Unauthenticated on purpose: at this moment nobody has decided which hotel this device
     * belongs to, and it has nothing to authenticate with. Everything it can do until a manager
     * approves is wait. The same public key may be posted again after expiry — that replaces
     * the pending row rather than requiring a new keypair.
     */
    fun requestPairing(publicKey: String): PairingRequest {
        val normalizedKey = publicKey.trim()
        val fingerprint = fingerprint(normalizedKey)
        throttleCreate(fingerprint)
        val deviceCode = "dev_" + randomToken(32)
        val code = (1..CODE_DIGITS).map { random.nextInt(10) }.joinToString("")
        val expiresAt = clock.instant().plus(properties.codeValidity)

        val id = requireNotNull(
            transactionTemplate.execute {
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
            },
        )

        return PairingRequest(id, deviceCode, code, fingerprint, expiresAt)
    }

    fun status(pairingRequestId: UUID): PairingStatus? {
        val row = transactionTemplate.execute {
            jdbcTemplate.query(
                """
                SELECT status, device_code, expires_at, terminal_name, mode
                FROM lookup_device_pairing_status(?)
                """.trimIndent(),
                { rs, _ ->
                    StatusRow(
                        status = rs.getString("status"),
                        deviceCode = rs.getString("device_code"),
                        expiresAt = rs.getTimestamp("expires_at").toInstant(),
                        terminalName = rs.getString("terminal_name"),
                        mode = rs.getString("mode"),
                    )
                },
                pairingRequestId,
            ).firstOrNull()
        } ?: return null

        val now = clock.instant()
        if (row.status == "pending" && !row.expiresAt.isAfter(now)) {
            transactionTemplate.executeWithoutResult {
                jdbcTemplate.queryForObject(
                    "SELECT mark_device_pairing_expired(?)",
                    Int::class.java,
                    pairingRequestId,
                )
            }
            return PairingStatus(status = "expired")
        }

        // Nothing another tenant does can move a waiting request off "pending" now that
        // wrong codes are charged to the tenant that submitted them (V137). Only this
        // terminal's own expiry, or its own manager, changes what it is told here.
        val apiStatus = when (row.status) {
            "approved" -> "approved"
            "expired" -> "expired"
            "pending" -> "pending"
            else -> "denied"
        }

        if (apiStatus != "approved") {
            return PairingStatus(status = apiStatus)
        }

        return PairingStatus(
            status = "approved",
            deviceCode = row.deviceCode,
            expiresAt = row.expiresAt,
            terminalName = row.terminalName,
            mode = row.mode,
        )
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

                // Checked before the lookup so a tenant that has burned its budget cannot
                // keep sampling the code space, and so the check costs nothing on the
                // ordinary path where the manager types the code correctly.
                if (recentMisses(tenantId) >= properties.maxAttempts) {
                    return@execute ApprovalResult.Failure(
                        IllegalStateException(
                            "Too many wrong pairing codes from this hotel. Wait and try again.",
                        ),
                    )
                }

                val request = jdbcTemplate.query(
                    """
                    SELECT id, device_code, public_key, key_fingerprint, expires_at
                    FROM lock_pending_device_pairing(?)
                    """.trimIndent(),
                    { rs, _ ->
                        Pending(
                            id = rs.getObject("id", UUID::class.java),
                            deviceCode = rs.getString("device_code"),
                            publicKey = rs.getString("public_key"),
                            fingerprint = rs.getString("key_fingerprint"),
                            expiresAt = rs.getTimestamp("expires_at").toInstant(),
                        )
                    },
                    pairingCode.trim(),
                ).firstOrNull()

                if (request == null) {
                    jdbcTemplate.queryForObject(
                        "SELECT record_device_pairing_miss(?, ?, make_interval(secs => ?))",
                        Int::class.java,
                        tenantId,
                        actorId,
                        properties.approvalMissWindow.toSeconds().toDouble(),
                    )
                    return@execute ApprovalResult.Failure(
                        IllegalArgumentException("That pairing code is not waiting for approval"),
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

    /** Rejected approvals charged to [tenantId] inside the throttling window. */
    private fun recentMisses(tenantId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count_recent_device_pairing_misses(?, make_interval(secs => ?))",
            Int::class.java,
            tenantId,
            properties.approvalMissWindow.toSeconds().toDouble(),
        ) ?: 0

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

    private fun throttleCreate(fingerprint: String) {
        val now = clock.instant()
        val windowStart = now.minus(properties.createWindow)
        synchronized(createLock) {
            val times = recentCreates.getOrPut(fingerprint) { mutableListOf() }
            times.removeAll { !it.isAfter(windowStart) }
            if (times.size >= properties.maxCreatesPerKey) {
                throw PairingCreateThrottledException()
            }
            times.add(now)
        }
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
        val expiresAt: Instant,
    )

    private data class StatusRow(
        val status: String,
        val deviceCode: String,
        val expiresAt: Instant,
        val terminalName: String?,
        val mode: String?,
    )

    private companion object {
        const val CODE_DIGITS = 6
        const val FINGERPRINT_BYTES = 8
        val ALLOWED_MODES = setOf("POS", "KITCHEN_DISPLAY", "BAR_DISPLAY", "CASHIER")
    }
}

class PairingCreateThrottledException : RuntimeException(
    "Too many pairing requests from this terminal. Wait and try again.",
)
