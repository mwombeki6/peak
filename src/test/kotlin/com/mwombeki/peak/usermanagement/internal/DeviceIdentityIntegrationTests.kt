package com.mwombeki.peak.usermanagement.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.context.SessionClass
import com.mwombeki.peak.usermanagement.internal.application.DevicePairingService
import com.mwombeki.peak.usermanagement.internal.application.DeviceSessionService
import com.mwombeki.peak.usermanagement.internal.application.StaffCredentialService
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Pairing, PIN login and device revocation — the identity slice a till needs
 * before it may open a socket.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DeviceIdentityIntegrationTests {

    @Autowired private lateinit var pairing: DevicePairingService
    @Autowired private lateinit var sessions: DeviceSessionService
    @Autowired private lateinit var credentials: StaffCredentialService
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var requestContextHolder: RequestContextHolder

    @AfterTest
    fun resetSession() {
        requestContextHolder.clear()
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun aManagerApprovesAWaitingTerminalIntoTheirProperty() {
        val hotel = seedHotel()
        val keys = ed25519()
        val requested = pairing.requestPairing(publicKey(keys))

        val paired = pairing.approve(
            tenantId = hotel.tenantId,
            pairingCode = requested.code,
            approval = DevicePairingService.Approval(
                propertyId = hotel.propertyId,
                outletId = null,
                terminalName = "Till 1",
                mode = "POS",
            ),
            actorId = hotel.managerId,
        )

        assertEquals(requested.deviceCode, paired.deviceCode)
        val storedTenant = jdbcTemplate.queryForObject(
            "SELECT tenant_id FROM paired_devices WHERE id = ?",
            UUID::class.java,
            paired.deviceId,
        )
        assertEquals(hotel.tenantId, storedTenant)
    }

    @Test
    fun aMistypedPairingCodeIsRecoverableButLockoutIsNot() {
        val hotel = seedHotel()
        val requested = pairing.requestPairing(publicKey(ed25519()))

        val mistype = assertFailsWith<IllegalArgumentException> {
            pairing.approve(
                tenantId = hotel.tenantId,
                pairingCode = "000000",
                approval = approval(hotel),
                actorId = hotel.managerId,
            )
        }
        assertTrue(mistype.message!!.contains("not waiting"))

        pairing.approve(
            tenantId = hotel.tenantId,
            pairingCode = requested.code,
            approval = approval(hotel),
            actorId = hotel.managerId,
        )
    }

    @Test
    fun tooManyWrongPairingCodesLockTheWaitingTerminal() {
        val hotel = seedHotel()
        val requested = pairing.requestPairing(publicKey(ed25519()))

        repeat(5) {
            assertFailsWith<IllegalArgumentException> {
                pairing.approve(
                    tenantId = hotel.tenantId,
                    pairingCode = "000000",
                    approval = approval(hotel),
                    actorId = hotel.managerId,
                )
            }
        }

        val locked = assertFailsWith<IllegalStateException> {
            pairing.approve(
                tenantId = hotel.tenantId,
                pairingCode = requested.code,
                approval = approval(hotel),
                actorId = hotel.managerId,
            )
        }
        assertTrue(locked.message!!.contains("too many wrong codes"))
    }

    @Test
    fun aManagerCannotPairATerminalIntoAnotherTenantsProperty() {
        val ours = seedHotel()
        val theirs = seedHotel()
        val requested = pairing.requestPairing(publicKey(ed25519()))

        val refused = assertFailsWith<IllegalArgumentException> {
            pairing.approve(
                tenantId = ours.tenantId,
                pairingCode = requested.code,
                approval = DevicePairingService.Approval(
                    propertyId = theirs.propertyId,
                    outletId = null,
                    terminalName = "Stolen till",
                    mode = "POS",
                ),
                actorId = ours.managerId,
            )
        }
        assertTrue(refused.message!!.contains("own tenant"))
    }

    @Test
    fun pendingPairingIsInvisibleUnderOrdinaryTenantRls() {
        val hotel = seedHotel()
        pairing.requestPairing(publicKey(ed25519()))

        val visible = transactionTemplate.execute {
            jdbcTemplate.execute("SET LOCAL ROLE pms_app")
            jdbcTemplate.queryForObject(
                "SELECT set_config('app.current_tenant_id', ?, true)",
                String::class.java,
                hotel.tenantId.toString(),
            )
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM device_pairing_requests",
                Int::class.java,
            )
        }

        assertEquals(0, visible, "a bound tenant must not read pending pairing codes")
    }

    @Test
    fun pinLoginOnAPairedDeviceIssuesAnOperationalSession() {
        val hotel = seedHotel()
        val keys = ed25519()
        val pin = "418205"
        activateStaff(hotel, pin)
        val paired = pair(hotel, keys)

        val challenge = requireNotNull(sessions.issueChallenge(paired.deviceCode))
        val session = requireNotNull(
            sessions.login(
                deviceCode = paired.deviceCode,
                challengeId = challenge.challengeId,
                signature = sign(keys, challenge.nonce),
                staffNumber = hotel.staffNumber,
                pin = pin,
            ),
        )

        assertTrue(session.token.startsWith("ops_"))
        assertEquals(paired.deviceId, session.deviceId)
        assertEquals(hotel.propertyId, session.propertyId)
        assertEquals(hotel.tenantId, session.tenantId)
        assertEquals(hotel.staffUserId, session.userId)
        assertNull(session.outletId)
        assertEquals("POS", session.mode)
        assertEquals("Till 1", session.terminalName)
        val stored = jdbcTemplate.queryForObject(
            "SELECT token_hash FROM operational_sessions WHERE device_id = ? AND revoked_at IS NULL",
            String::class.java,
            paired.deviceId,
        )
        assertNotNull(stored)
        assertFalse(stored.contains(session.token))
        assertEquals(64, stored.length)
    }

    @Test
    fun pinLoginOnAnOutletBoundDeviceReturnsThatOutlet() {
        val hotel = seedHotel()
        val keys = ed25519()
        val pin = "418205"
        activateStaff(hotel, pin)
        val outletId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO outlets (id, tenant_id, property_id, name, type, is_active)
            VALUES (?, ?, ?, 'Restaurant', 'RESTAURANT', true)
            """.trimIndent(),
            outletId,
            hotel.tenantId,
            hotel.propertyId,
        )
        val paired = pair(
            hotel,
            keys,
            DevicePairingService.Approval(
                propertyId = hotel.propertyId,
                outletId = outletId,
                terminalName = "Till 1",
                mode = "POS",
            ),
        )

        val challenge = requireNotNull(sessions.issueChallenge(paired.deviceCode))
        val session = requireNotNull(
            sessions.login(
                deviceCode = paired.deviceCode,
                challengeId = challenge.challengeId,
                signature = sign(keys, challenge.nonce),
                staffNumber = hotel.staffNumber,
                pin = pin,
            ),
        )

        assertEquals(hotel.tenantId, session.tenantId)
        assertEquals(hotel.staffUserId, session.userId)
        assertEquals(outletId, session.outletId)
    }

    @Test
    fun aRevokedDeviceDeniesAValidPinWithoutCountingAMistype() {
        val hotel = seedHotel()
        val keys = ed25519()
        val pin = "418205"
        activateStaff(hotel, pin)
        val paired = pair(hotel, keys)
        pairing.revoke(hotel.tenantId, paired.deviceId, hotel.managerId)

        val challenge = sessions.issueChallenge(paired.deviceCode)
        assertNull(challenge, "a revoked device must not receive a login challenge")

        val attemptsBefore = jdbcTemplate.queryForObject(
            "SELECT failed_attempts FROM staff_credentials WHERE user_id = ?",
            Int::class.java,
            hotel.staffUserId,
        )

        // Even if a stale challenge existed, login must refuse before the PIN is checked.
        assertNull(
            sessions.login(
                deviceCode = paired.deviceCode,
                challengeId = UUID.randomUUID(),
                signature = "not-a-signature",
                staffNumber = hotel.staffNumber,
                pin = pin,
            ),
        )

        val attemptsAfter = jdbcTemplate.queryForObject(
            "SELECT failed_attempts FROM staff_credentials WHERE user_id = ?",
            Int::class.java,
            hotel.staffUserId,
        )
        assertEquals(attemptsBefore, attemptsAfter)
    }

    @Test
    fun aSecondPinLoginOnTheSameTillRevokesTheFirstSession() {
        val hotel = seedHotel()
        val keys = ed25519()
        val pin = "418205"
        activateStaff(hotel, pin)
        val paired = pair(hotel, keys)

        val first = pinLogin(paired.deviceCode, keys, hotel.staffNumber, pin)
        val second = pinLogin(paired.deviceCode, keys, hotel.staffNumber, pin)

        assertNotEquals(first.token, second.token)
        assertEquals(1, liveSessionCount(paired.deviceId))
        assertEquals(2, sessionCount(paired.deviceId))
        assertEquals(0, liveSessionCountForToken(first.token, paired.deviceId))
        assertEquals(1, liveSessionCountForToken(second.token, paired.deviceId))
    }

    @Test
    fun issuingANewActivationRevokesLiveOperationalSessions() {
        val hotel = seedHotel()
        val keys = ed25519()
        val pin = "418205"
        activateStaff(hotel, pin)
        val paired = pair(hotel, keys)
        pinLogin(paired.deviceCode, keys, hotel.staffNumber, pin)

        credentials.issueActivation(hotel.tenantId, hotel.staffUserId, hotel.managerId)

        assertEquals(0, liveSessionCount(paired.deviceId))
        assertEquals(1, sessionCount(paired.deviceId))
    }

    @Test
    fun lockingTheCurrentSessionRevokesItWithoutClosingTheTill() {
        val hotel = seedHotel()
        val keys = ed25519()
        val pin = "418205"
        activateStaff(hotel, pin)
        val paired = pair(hotel, keys)
        val session = pinLogin(paired.deviceCode, keys, hotel.staffNumber, pin)
        val sessionId = jdbcTemplate.queryForObject(
            """
            SELECT id FROM operational_sessions
            WHERE device_id = ? AND revoked_at IS NULL
            """.trimIndent(),
            UUID::class.java,
            paired.deviceId,
        )

        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Tenant(hotel.tenantId, hotel.staffUserId),
                correlationId = "corr-lock-${hotel.tenantId}",
                idempotencyKey = null,
                httpMethod = "DELETE",
                requestPath = "/api/v1/staff/sessions/current",
                sessionClass = SessionClass.OPERATIONAL,
                boundPropertyId = hotel.propertyId,
                boundSessionId = sessionId,
            ),
        )
        sessions.revokeCurrent()

        assertEquals(0, liveSessionCount(paired.deviceId))
        assertEquals(0, liveSessionCountForToken(session.token, paired.deviceId))
    }

    private fun pinLogin(
        deviceCode: String,
        keys: KeyPair,
        staffNumber: String,
        pin: String,
    ): DeviceSessionService.OperationalSession {
        val challenge = requireNotNull(sessions.issueChallenge(deviceCode))
        return requireNotNull(
            sessions.login(
                deviceCode = deviceCode,
                challengeId = challenge.challengeId,
                signature = sign(keys, challenge.nonce),
                staffNumber = staffNumber,
                pin = pin,
            ),
        )
    }

    private fun liveSessionCount(deviceId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM operational_sessions WHERE device_id = ? AND revoked_at IS NULL",
            Int::class.java,
            deviceId,
        ) ?: 0

    private fun sessionCount(deviceId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM operational_sessions WHERE device_id = ?",
            Int::class.java,
            deviceId,
        ) ?: 0

    private fun liveSessionCountForToken(token: String, deviceId: UUID): Int {
        val hash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        )
        return jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM operational_sessions
            WHERE device_id = ?
              AND revoked_at IS NULL
              AND token_hash = ?
            """.trimIndent(),
            Int::class.java,
            deviceId,
            hash,
        ) ?: 0
    }

    private fun pair(
        hotel: Hotel,
        keys: KeyPair,
        approval: DevicePairingService.Approval = approval(hotel),
    ): DevicePairingService.PairedDevice {
        val requested = pairing.requestPairing(publicKey(keys))
        return pairing.approve(
            tenantId = hotel.tenantId,
            pairingCode = requested.code,
            approval = approval,
            actorId = hotel.managerId,
        )
    }

    private fun approval(hotel: Hotel) = DevicePairingService.Approval(
        propertyId = hotel.propertyId,
        outletId = null,
        terminalName = "Till 1",
        mode = "POS",
    )

    private fun activateStaff(hotel: Hotel, pin: String) {
        val secret = credentials.issueActivation(hotel.tenantId, hotel.staffUserId, hotel.managerId)
        credentials.activate(hotel.tenantId, hotel.staffNumber, secret.plaintext, pin)
    }

    private fun seedHotel(): Hotel {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val propertyId = UUID.randomUUID()
        val managerId = UUID.randomUUID()
        val staffUserId = UUID.randomUUID()

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId, "Plan $planId", "plan-$planId",
        )
        jdbcTemplate.update(
            "INSERT INTO tenants (id, name, slug, schema_name, plan_id) VALUES (?, ?, ?, ?, ?)",
            tenantId, "Tenant $tenantId", "tenant-$tenantId",
            "tenant_$tenantId".replace("-", "_"), planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Manager', ?, 'active', true)
            """.trimIndent(),
            managerId, tenantId, "mgr-$managerId@example.com",
        )
        val staffNumber = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT allocate_staff_number(?)", String::class.java, tenantId,
            ),
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, staff_number, status, is_active)
            VALUES (?, ?, 'Amina Hassan', ?, 'active', true)
            """.trimIndent(),
            staffUserId, tenantId, staffNumber,
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, code, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            propertyId, tenantId, "Property $propertyId", "P${propertyId.toString().take(8)}",
        )
        return Hotel(tenantId, propertyId, managerId, staffUserId, staffNumber)
    }

    private fun ed25519(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun publicKey(keys: KeyPair): String =
        Base64.getEncoder().encodeToString(keys.public.encoded)

    private fun sign(keys: KeyPair, nonceB64: String): String {
        val verifier = Signature.getInstance("Ed25519")
        verifier.initSign(keys.private)
        verifier.update(Base64.getDecoder().decode(nonceB64))
        return Base64.getEncoder().encodeToString(verifier.sign())
    }

    private data class Hotel(
        val tenantId: UUID,
        val propertyId: UUID,
        val managerId: UUID,
        val staffUserId: UUID,
        val staffNumber: String,
    )
}
