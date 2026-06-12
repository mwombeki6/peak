package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.InviteTenantUserCommand
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationConflictException
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationPort
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TenantUserInvitationServiceIntegrationTests {

    @Autowired
    private lateinit var invitationPort: TenantUserInvitationPort

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
    }

    @Test
    fun createsInvitationWithAuditOutboxAndIdempotency() {
        val fixture = tenantFixture()
        insertTenantFixture(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-invite-create"))

        val receipt = invitationPort.inviteTenantUser(
            InviteTenantUserCommand(
                tenantId = fixture.tenantId,
                email = "INVITED-${fixture.tenantId}@Example.COM",
                fullName = "Invited User",
                tenantRoleId = fixture.roleId,
                expiresIn = Duration.ofHours(24),
                metadata = mapOf("source" to "integration-test"),
            ),
        )

        assertFalse(receipt.replayed)
        assertNotNull(receipt.invitationToken)
        assertEquals("invited-${fixture.tenantId}@example.com", receipt.email)
        assertEquals(fixture.tenantId, receipt.tenantId)
        assertEquals(fixture.roleId, receipt.tenantRoleId)

        val row = jdbcTemplate.queryForMap(
            """
            SELECT tenant_id, email, status, token_hash, invited_by_user_id,
                   invited_by_platform_user_id, metadata::text AS metadata
            FROM tenant_user_invitations
            WHERE id = ?
            """.trimIndent(),
            receipt.invitationId,
        )

        assertEquals(fixture.tenantId, row["tenant_id"])
        assertEquals(receipt.email, row["email"])
        assertEquals("pending", row["status"])
        assertNotEquals(receipt.invitationToken, row["token_hash"])
        assertEquals(fixture.inviterUserId, row["invited_by_user_id"])
        assertEquals(null, row["invited_by_platform_user_id"])
        assertTrue(row["metadata"].toString().contains("integration-test"))

        val idempotency = jdbcTemplate.queryForMap(
            """
            SELECT id, status, resource_id, response_body::text AS response_body
            FROM idempotency_keys
            WHERE tenant_id = ?
              AND idempotency_key = ?
            """.trimIndent(),
            fixture.tenantId,
            "idem-invite-create",
        )

        assertEquals("succeeded", idempotency["status"])
        assertEquals(receipt.invitationId, idempotency["resource_id"])
        assertFalse(idempotency["response_body"].toString().contains(receipt.invitationToken))

        val auditCount = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM audit_logs
            WHERE tenant_id = ?
              AND entity_id = ?
              AND action = 'tenant.users.invite'
            """.trimIndent(),
            Int::class.java,
            fixture.tenantId,
            receipt.invitationId,
        )

        assertEquals(1, auditCount)

        val outbox = jdbcTemplate.queryForMap(
            """
            SELECT tenant_id, aggregate_type, aggregate_id, event_type,
                   destination, payload::text AS payload, idempotency_key_id
            FROM outbox_events
            WHERE aggregate_id = ?
            """.trimIndent(),
            receipt.invitationId,
        )

        assertEquals(fixture.tenantId, outbox["tenant_id"])
        assertEquals("tenant_user_invitations", outbox["aggregate_type"])
        assertEquals(receipt.invitationId, outbox["aggregate_id"])
        assertEquals("tenant.user.invited", outbox["event_type"])
        assertEquals("email", outbox["destination"])
        assertEquals(idempotency["id"], outbox["idempotency_key_id"])
        assertFalse(outbox["payload"].toString().contains(receipt.invitationToken))
    }

    @Test
    fun replaysInvitationWithoutReturningRawToken() {
        val fixture = tenantFixture()
        insertTenantFixture(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-invite-replay"))
        val command = InviteTenantUserCommand(
            tenantId = fixture.tenantId,
            email = "replay-${fixture.tenantId}@example.com",
            tenantRoleId = fixture.roleId,
        )

        val first = invitationPort.inviteTenantUser(command)
        val second = invitationPort.inviteTenantUser(command)

        assertFalse(first.replayed)
        assertNotNull(first.invitationToken)
        assertTrue(second.replayed)
        assertEquals(null, second.invitationToken)
        assertEquals(first.invitationId, second.invitationId)

        val count = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM tenant_user_invitations
            WHERE tenant_id = ?
              AND email = ?
            """.trimIndent(),
            Int::class.java,
            fixture.tenantId,
            command.email,
        )

        assertEquals(1, count)
    }

    @Test
    fun rejectsSameIdempotencyKeyWithDifferentPayload() {
        val fixture = tenantFixture()
        insertTenantFixture(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-invite-conflict"))

        invitationPort.inviteTenantUser(
            InviteTenantUserCommand(
                tenantId = fixture.tenantId,
                email = "first-${fixture.tenantId}@example.com",
                tenantRoleId = fixture.roleId,
            ),
        )

        val error = assertFailsWith<TenantUserInvitationConflictException> {
            invitationPort.inviteTenantUser(
                InviteTenantUserCommand(
                    tenantId = fixture.tenantId,
                    email = "second-${fixture.tenantId}@example.com",
                    tenantRoleId = fixture.roleId,
                ),
            )
        }

        assertEquals(
            "Idempotency key was already used for a different tenant user invitation request",
            error.message,
        )
    }

    @Test
    fun rejectsDuplicatePendingInvitationForEmail() {
        val fixture = tenantFixture()
        insertTenantFixture(fixture)
        val email = "duplicate-${fixture.tenantId}@example.com"

        requestContextHolder.set(tenantContext(fixture, "idem-invite-duplicate-a"))
        invitationPort.inviteTenantUser(
            InviteTenantUserCommand(
                tenantId = fixture.tenantId,
                email = email,
                tenantRoleId = fixture.roleId,
            ),
        )

        requestContextHolder.set(tenantContext(fixture, "idem-invite-duplicate-b"))
        val error = assertFailsWith<TenantUserInvitationConflictException> {
            invitationPort.inviteTenantUser(
                InviteTenantUserCommand(
                    tenantId = fixture.tenantId,
                    email = email.uppercase(),
                    tenantRoleId = fixture.roleId,
                ),
            )
        }

        assertEquals(
            "A pending invitation already exists for this tenant user email",
            error.message,
        )
    }

    private fun tenantFixture(): TenantFixture {
        return TenantFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            inviterUserId = UUID.randomUUID(),
            roleId = UUID.randomUUID(),
        )
    }

    private fun tenantContext(
        fixture: TenantFixture,
        idempotencyKey: String,
    ): RequestContext {
        return RequestContext(
            identity = RequestIdentity.Tenant(
                tenantId = fixture.tenantId,
                tenantUserId = fixture.inviterUserId,
                correlationId = "corr-$idempotencyKey",
            ),
            correlationId = "corr-$idempotencyKey",
            idempotencyKey = idempotencyKey,
            httpMethod = "POST",
            requestPath = "/api/v1/tenants/${fixture.tenantId}/users/invitations",
        )
    }

    private fun insertTenantFixture(fixture: TenantFixture) {
        jdbcTemplate.update(
            """
            INSERT INTO plans (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.planId,
            "Plan ${fixture.planId}",
            "plan-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, schema_name, plan_id)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            fixture.tenantId,
            "Tenant ${fixture.tenantId}",
            "tenant-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status)
            VALUES (?, ?, ?, ?, 'active')
            """.trimIndent(),
            fixture.inviterUserId,
            fixture.tenantId,
            "Inviter ${fixture.inviterUserId}",
            "inviter-${fixture.inviterUserId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.roleId,
            fixture.tenantId,
            "Tenant Role ${fixture.roleId}",
            "tenant-role-${fixture.roleId}",
        )
    }

    private data class TenantFixture(
        val planId: UUID,
        val tenantId: UUID,
        val inviterUserId: UUID,
        val roleId: UUID,
    )
}
