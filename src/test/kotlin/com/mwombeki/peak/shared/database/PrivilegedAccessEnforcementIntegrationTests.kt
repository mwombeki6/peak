package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Proves that the privileged-access guarantees are enforced by decisions that
 * can fail the operation, rather than merely described by schema columns.
 *
 * These exercise the database contract directly because that is where use
 * consumption and quorum evaluation are authoritative.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class PrivilegedAccessEnforcementIntegrationTests @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
) {

    // ---------------------------------------------------------------- uses

    @Test
    fun `consumes up to the ceiling and then denies further use`() {
        val world = world(maxUses = 2)

        val first = consume(world)
        val second = consume(world)
        val third = consume(world)

        assertTrue(first.allowed, "first use should be allowed")
        assertEquals(1, first.usesRemaining)
        assertTrue(second.allowed, "final allowed use should succeed")
        assertEquals(0, second.usesRemaining)
        assertFalse(third.allowed, "use beyond the ceiling must be denied")
        // Consuming the final use exhausts the grant, so the next attempt is
        // refused on state rather than on the counter. Either way it is denied
        // and the counter never exceeds the ceiling.
        assertNotNull(third.denialReason)
        assertEquals("exhausted", status(world.accessId))
        assertEquals(2, useCount(world.accessId))
    }

    @Test
    fun `exhausting the final use marks the grant exhausted rather than expired`() {
        val world = world(maxUses = 1)

        assertTrue(consume(world).allowed)

        assertEquals("exhausted", status(world.accessId))
    }

    @Test
    fun `denied authorization records evidence without consuming a use`() {
        val world = world(maxUses = 3)

        val denied = consume(world, tenantId = UUID.randomUUID())

        assertFalse(denied.allowed)
        assertEquals(0, useCount(world.accessId), "denial must not consume")
        assertEquals(
            1,
            countUsage(world.accessId, "denied"),
            "denial must still be recorded as evidence",
        )
    }

    @Test
    fun `repeated authorization layers in one request consume a single use`() {
        val world = world(maxUses = 3)
        val execution = UUID.randomUUID()

        val guardLayer = consume(world, executionId = execution)
        val serviceLayer = consume(world, executionId = execution)

        assertTrue(guardLayer.allowed)
        assertTrue(serviceLayer.allowed)
        assertEquals(
            1,
            useCount(world.accessId),
            "one server request must consume exactly one use",
        )
    }

    @Test
    fun `wrong operator tenant or operation is denied`() {
        val world = world(maxUses = 3)

        // A real but different operator: evidence must reference a genuine
        // platform user, so the realistic case is another authenticated person.
        assertFalse(consume(world, platformUserId = world.unprivileged).allowed)
        assertFalse(consume(world, tenantId = UUID.randomUUID()).allowed)
        assertFalse(consume(world, operationCode = "platform.tenant.delete").allowed)
        assertEquals(0, useCount(world.accessId))
    }

    @Test
    fun `revoked grant cannot be used`() {
        val world = world(maxUses = 3)
        jdbcTemplate.update(
            """
            UPDATE platform_break_glass_access
            SET status = 'revoked', revoked_at = now()
            WHERE id = ?
            """.trimIndent(),
            world.accessId,
        )

        val result = consume(world)

        assertFalse(result.allowed)
        assertEquals(0, useCount(world.accessId))
    }

    @Test
    fun `stale authentication is denied for a phishing resistant operation`() {
        val world = world(maxUses = 3)

        val result = consume(
            world,
            authTime = Instant.now().minus(2, ChronoUnit.HOURS),
        )

        assertFalse(result.allowed)
        assertEquals(
            "Authentication is not fresh enough for this operation",
            result.denialReason,
        )
    }

    @Test
    fun `weaker assurance cannot satisfy a phishing resistant operation`() {
        val world = world(maxUses = 3)

        val result = consume(world, assurance = "mfa")

        assertFalse(result.allowed)
        assertEquals(
            "Operation requires phishing-resistant authentication",
            result.denialReason,
        )
    }

    @Test
    fun `usage evidence is append only`() {
        val world = world(maxUses = 2)
        consume(world)

        assertThrows<DataAccessException> {
            jdbcTemplate.update(
                "UPDATE platform_privileged_access_usage SET decision = 'denied' WHERE access_id = ?",
                world.accessId,
            )
        }
        assertThrows<DataAccessException> {
            jdbcTemplate.update(
                "DELETE FROM platform_privileged_access_usage WHERE access_id = ?",
                world.accessId,
            )
        }
    }

    // ------------------------------------------------------------ approvals

    @Test
    fun `requester cannot approve their own request`() {
        val world = world(maxUses = 2)

        assertThrows<DataAccessException> {
            approve(world, approver = world.requesterId, seat = SEAT_PRIMARY)
        }
    }

    @Test
    fun `one person cannot occupy two seats`() {
        val world = world(maxUses = 2)
        approve(world, approver = world.approverA, seat = SEAT_PRIMARY)

        assertThrows<DataAccessException> {
            approve(world, approver = world.approverA, seat = SEAT_SECONDARY)
        }
    }

    @Test
    fun `approver without the seat permission is rejected`() {
        val world = world(maxUses = 2)

        assertThrows<DataAccessException> {
            approve(world, approver = world.unprivileged, seat = SEAT_PRIMARY)
        }
    }

    @Test
    fun `two seat policy is unsatisfied until both seats are filled`() {
        val world = world(maxUses = 2)

        approve(world, approver = world.approverA, seat = SEAT_PRIMARY)
        assertFalse(quorumSatisfied(world.accessId), "one seat must not satisfy a two-seat policy")

        approve(world, approver = world.approverB, seat = SEAT_SECONDARY)
        assertTrue(quorumSatisfied(world.accessId), "both seats filled should satisfy the quorum")
    }

    @Test
    fun `changing the request invalidates existing approvals`() {
        val world = world(maxUses = 2)
        approve(world, approver = world.approverA, seat = SEAT_PRIMARY)
        approve(world, approver = world.approverB, seat = SEAT_SECONDARY)
        assertTrue(quorumSatisfied(world.accessId))

        // A material change bumps the request version and hash.
        jdbcTemplate.update(
            "UPDATE platform_break_glass_access SET max_uses = 3 WHERE id = ?",
            world.accessId,
        )

        assertFalse(
            quorumSatisfied(world.accessId),
            "approvals must not survive a material request change",
        )
        assertEquals(2, requestVersion(world.accessId))
    }

    @Test
    fun `a denial blocks the quorum outright`() {
        val world = world(maxUses = 2)
        approve(world, approver = world.approverA, seat = SEAT_PRIMARY)
        approve(
            world,
            approver = world.approverB,
            seat = SEAT_SECONDARY,
            decision = "denied",
        )

        assertFalse(quorumSatisfied(world.accessId))
    }

    @Test
    fun `deactivated approver no longer satisfies the quorum`() {
        val world = world(maxUses = 2)
        approve(world, approver = world.approverA, seat = SEAT_PRIMARY)
        approve(world, approver = world.approverB, seat = SEAT_SECONDARY)
        assertTrue(quorumSatisfied(world.accessId))

        jdbcTemplate.update(
            "UPDATE platform_users SET status = 'disabled' WHERE id = ?",
            world.approverB,
        )

        assertFalse(
            quorumSatisfied(world.accessId),
            "an approver who is no longer effective must not count",
        )
    }

    @Test
    fun `destructive operations are barred from break glass eligibility`() {
        val eligible = jdbcTemplate.queryForObject(
            """
            SELECT break_glass_eligible
            FROM privileged_operation_policies
            WHERE operation_code = 'platform.tenant.delete'
            """.trimIndent(),
            Boolean::class.java,
        )
        assertFalse(eligible == true, "destructive operations must never be eligible")

        assertThrows<DataAccessException> {
            jdbcTemplate.update(
                """
                INSERT INTO privileged_operation_policies (
                    operation_code, permission_code, access_class, risk_level,
                    break_glass_eligible, required_assurance, max_auth_age_seconds,
                    max_duration_minutes, max_uses, approval_policy_code
                ) VALUES (
                    'test.destructive.eligible', 'platform.tenants.manage', 'destructive',
                    5, true, 'phishing_resistant', 300, 15, 1, 'ineligible'
                )
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `policy ceilings cannot be exceeded by catalog rows`() {
        assertThrows<DataAccessException> {
            jdbcTemplate.update(
                """
                INSERT INTO privileged_operation_policies (
                    operation_code, permission_code, access_class, risk_level,
                    break_glass_eligible, required_assurance, max_auth_age_seconds,
                    max_duration_minutes, max_uses, approval_policy_code
                ) VALUES (
                    'test.financial.loose', 'platform.billing.manage', 'financial_mutation',
                    4, true, 'phishing_resistant', 300, 15, 5, 'financial_mutation'
                )
                """.trimIndent(),
            )
        }
    }


    // ------------------------------------------- notification delivery basis

    /**
     * A security notice about privileged staff access is an obligation Peak
     * owes, not an offer the recipient may decline. It must reach a verified
     * channel that has never granted consent.
     */
    @Test
    fun `a security notice reaches a verified channel without any consent`() {
        val channel = verifiedChannel()

        assertTrue(
            canReceive(channel, "security_notifications"),
            "security notices must not require consent",
        )
    }

    /**
     * The change is narrow: every consent-based purpose still requires an
     * active consent decision on the same channel.
     */
    @Test
    fun `consent purposes still require consent on the same channel`() {
        val channel = verifiedChannel()

        assertFalse(canReceive(channel, "marketing"), "marketing must remain opt-in")
        assertFalse(
            canReceive(channel, "operational_reports"),
            "operational reports must remain opt-in",
        )
        assertFalse(
            canReceive(channel, "critical_operational_alerts"),
            "critical alerts remain opt-in until that product decision is taken",
        )
    }

    @Test
    fun `an unverified channel receives nothing regardless of basis`() {
        val channel = verifiedChannel(verified = false)

        assertFalse(canReceive(channel, "security_notifications"))
        assertFalse(canReceive(channel, "marketing"))
    }

    /** An unknown purpose must neither widen eligibility nor silently suppress. */
    @Test
    fun `an unknown purpose is never deliverable`() {
        val channel = verifiedChannel()

        assertFalse(canReceive(channel, "security_notifcations"))
    }

    private data class ContactChannel(
        val tenantId: UUID,
        val contactId: UUID,
        val channelId: UUID,
    )

    private fun verifiedChannel(verified: Boolean = true): ContactChannel {
        val suffix = UUID.randomUUID().toString().take(8)
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val contactId = UUID.randomUUID()
        val channelId = UUID.randomUUID()

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId, "Plan $suffix", "plan-$suffix",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            tenantId, "Notify $suffix", "notify-$suffix",
            "tenant_${tenantId.toString().replace("-", "")}", planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_contacts (id, tenant_id, full_name, status)
            VALUES (?, ?, ?, 'active')
            """.trimIndent(),
            contactId, tenantId, "Security Contact $suffix",
        )
        jdbcTemplate.update(
            """
            INSERT INTO contact_channels (
                id, tenant_id, contact_id, channel_type, address,
                normalized_address, verification_status, is_active
            ) VALUES (?, ?, ?, 'email', ?, ?, ?, true)
            """.trimIndent(),
            channelId, tenantId, contactId,
            "security-$suffix@example.test", "security-$suffix@example.test",
            if (verified) "verified" else "unverified",
        )
        return ContactChannel(tenantId, contactId, channelId)
    }

    private fun canReceive(channel: ContactChannel, purpose: String): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT contact_channel_can_receive(?::uuid, ?::uuid, ?::uuid, ?::text)",
            Boolean::class.java,
            channel.tenantId,
            channel.contactId,
            channel.channelId,
            purpose,
        ) == true

    // ------------------------------------------------------------- helpers


    private data class World(
        val accessId: UUID,
        val tenantId: UUID,
        val requesterId: UUID,
        val approverA: UUID,
        val approverB: UUID,
        val unprivileged: UUID,
        val operationCode: String,
    )

    private data class Consumption(
        val allowed: Boolean,
        val usesRemaining: Int,
        val denialReason: String?,
    )

    private fun world(maxUses: Int): World {
        val suffix = UUID.randomUUID().toString().take(8)
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val requesterId = UUID.randomUUID()
        val approverA = UUID.randomUUID()
        val approverB = UUID.randomUUID()
        val unprivileged = UUID.randomUUID()
        val ticketId = UUID.randomUUID()
        val operationCode = "test.operation.$suffix"
        val policyCode = "test_policy_$suffix"

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId, "Plan $suffix", "plan-$suffix",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            tenantId, "Tenant $suffix", "tenant-$suffix",
            "tenant_${tenantId.toString().replace("-", "")}", planId,
        )
        insertPlatformUser(requesterId, "requester-$suffix")
        insertPlatformUser(approverA, "approver-a-$suffix")
        insertPlatformUser(approverB, "approver-b-$suffix")
        insertPlatformUser(unprivileged, "outsider-$suffix")
        grantPermissions(approverA, "role-a-$suffix", listOf(PRIMARY_PERMISSION))
        grantPermissions(approverB, "role-b-$suffix", listOf(SECONDARY_PERMISSION))

        jdbcTemplate.update(
            """
            INSERT INTO support_tickets (id, tenant_id, ticket_number, subject, status)
            VALUES (?, ?, ?, 'Privileged access enforcement', 'open')
            """.trimIndent(),
            ticketId, tenantId, "TCK-$suffix",
        )

        jdbcTemplate.update(
            """
            INSERT INTO approval_policies (approval_policy_code, description)
            VALUES (?, 'Two distinct seats for enforcement tests')
            """.trimIndent(),
            policyCode,
        )
        jdbcTemplate.update(
            """
            INSERT INTO approval_policy_seats (
                approval_policy_code, seat_code, required_permission, required_approvers
            ) VALUES (?, ?, ?, 1), (?, ?, ?, 1)
            """.trimIndent(),
            policyCode, SEAT_PRIMARY, PRIMARY_PERMISSION,
            policyCode, SEAT_SECONDARY, SECONDARY_PERMISSION,
        )
        jdbcTemplate.update(
            """
            INSERT INTO privileged_operation_policies (
                operation_code, permission_code, access_class, risk_level,
                break_glass_eligible, required_assurance, max_auth_age_seconds,
                max_duration_minutes, max_uses, approval_policy_code
            ) VALUES (?, ?, 'sensitive_read', 2, true, 'phishing_resistant', 600, 60, 10, ?)
            """.trimIndent(),
            operationCode, PRIMARY_PERMISSION, policyCode,
        )

        val accessId = UUID.randomUUID()
        val startsAt = Timestamp.from(Instant.now().minusSeconds(60))
        val expiresAt = Timestamp.from(Instant.now().plusSeconds(3600))
        jdbcTemplate.update(
            """
            INSERT INTO platform_break_glass_access (
                id, platform_user_id, tenant_id, support_ticket_id, action_code,
                operation_code, approval_policy_code, reason, status,
                approved_by, approved_at, activated_at, starts_at, expires_at,
                max_uses, assurance_level
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, 'Enforcement verification', 'active',
                ?, now(), now(), ?, ?, ?, 'phishing_resistant'
            )
            """.trimIndent(),
            accessId, requesterId, tenantId, ticketId, PRIMARY_PERMISSION,
            operationCode, policyCode, approverA, startsAt, expiresAt, maxUses,
        )

        return World(
            accessId = accessId,
            tenantId = tenantId,
            requesterId = requesterId,
            approverA = approverA,
            approverB = approverB,
            unprivileged = unprivileged,
            operationCode = operationCode,
        )
    }

    private fun insertPlatformUser(id: UUID, label: String) {
        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, full_name, email, status)
            VALUES (?, ?, ?, 'active')
            """.trimIndent(),
            id, "Platform $label", "$label@example.test",
        )
    }

    private fun grantPermissions(userId: UUID, roleCode: String, codes: List<String>) {
        val roleId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO platform_roles (id, name, code) VALUES (?, ?, ?)",
            roleId, "Role $roleCode", roleCode,
        )
        codes.forEach { code ->
            jdbcTemplate.update(
                """
                INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
                SELECT ?, id FROM platform_permissions WHERE code = ?
                """.trimIndent(),
                roleId, code,
            )
        }
        jdbcTemplate.update(
            "INSERT INTO platform_user_roles (platform_user_id, platform_role_id) VALUES (?, ?)",
            userId, roleId,
        )
    }

    private fun consume(
        world: World,
        platformUserId: UUID = world.requesterId,
        tenantId: UUID = world.tenantId,
        operationCode: String = world.operationCode,
        executionId: UUID = UUID.randomUUID(),
        assurance: String = "phishing_resistant",
        authTime: Instant = Instant.now(),
    ): Consumption {
        val row = jdbcTemplate.queryForMap(
            """
            SELECT allowed, uses_remaining, denial_reason
            FROM consume_privileged_access(
                ?::uuid, ?::uuid, ?::uuid, ?::text,
                ?::uuid, ?::text, ?::timestamptz, ?::text
            )
            """.trimIndent(),
            platformUserId,
            world.accessId,
            tenantId,
            operationCode,
            executionId,
            assurance,
            Timestamp.from(authTime),
            "test-correlation",
        )
        return Consumption(
            allowed = row["allowed"] as Boolean,
            usesRemaining = (row["uses_remaining"] as Number).toInt(),
            denialReason = row["denial_reason"] as String?,
        )
    }

    private fun approve(
        world: World,
        approver: UUID,
        seat: String,
        decision: String = "approved",
    ) {
        val current = jdbcTemplate.queryForMap(
            """
            SELECT request_hash, request_version
            FROM platform_break_glass_access WHERE id = ?
            """.trimIndent(),
            world.accessId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_break_glass_approvals (
                access_id, seat_code, approver_platform_user_id, decision,
                approved_request_version, approved_request_hash, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, now() + interval '1 hour')
            """.trimIndent(),
            world.accessId, seat, approver, decision,
            current["request_version"], current["request_hash"],
        )
    }

    private fun quorumSatisfied(accessId: UUID): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT break_glass_quorum_satisfied(?)",
            Boolean::class.java,
            accessId,
        ) == true

    private fun useCount(accessId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT use_count FROM platform_break_glass_access WHERE id = ?",
            Int::class.java,
            accessId,
        ) ?: -1

    private fun status(accessId: UUID): String =
        jdbcTemplate.queryForObject(
            "SELECT status FROM platform_break_glass_access WHERE id = ?",
            String::class.java,
            accessId,
        ).also { assertNotNull(it) } ?: ""

    private fun requestVersion(accessId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT request_version FROM platform_break_glass_access WHERE id = ?",
            Int::class.java,
            accessId,
        ) ?: -1

    private fun countUsage(accessId: UUID, decision: String): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM platform_privileged_access_usage
            WHERE access_id = ? AND decision = ?
            """.trimIndent(),
            Int::class.java,
            accessId,
            decision,
        ) ?: -1

    private companion object {
        const val SEAT_PRIMARY = "primary"
        const val SEAT_SECONDARY = "secondary"
        const val PRIMARY_PERMISSION = "platform.tenants.view"
        const val SECONDARY_PERMISSION = "platform.billing.manage"
    }
}
