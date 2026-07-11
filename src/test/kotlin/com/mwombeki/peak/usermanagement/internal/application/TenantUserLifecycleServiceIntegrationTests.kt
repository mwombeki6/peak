package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.ExternalIdentityPrincipal
import com.mwombeki.peak.shared.context.ExternalIdentityResolver
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.context.ResolvedExternalIdentity
import com.mwombeki.peak.usermanagement.api.RevokeTenantUserIdentityLinkCommand
import com.mwombeki.peak.usermanagement.api.TenantUserLifecycleAction
import com.mwombeki.peak.usermanagement.api.TenantUserLifecycleCommand
import com.mwombeki.peak.usermanagement.api.TenantUserLifecyclePort
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TenantUserLifecycleServiceIntegrationTests {

    @Autowired
    private lateinit var lifecyclePort: TenantUserLifecyclePort

    @Autowired
    private lateinit var externalIdentityResolver: ExternalIdentityResolver

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
    }

    @Test
    fun disablesTenantUserAndBlocksIdentityResolution() {
        val fixture = lifecycleFixture()
        insertLifecycleFixture(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-lifecycle-disable"))

        assertIs<ResolvedExternalIdentity.Tenant>(resolveIdentity(fixture))

        val receipt = lifecyclePort.changeTenantUserLifecycle(
            TenantUserLifecycleCommand(
                tenantId = fixture.tenantId,
                userId = fixture.targetUserId,
                action = TenantUserLifecycleAction.DISABLE,
            ),
        )

        assertEquals("disabled", receipt.status)
        assertEquals(false, receipt.isActive)
        assertEquals(true, receipt.changed)
        assertEquals(false, receipt.replayed)
        assertNull(resolveIdentity(fixture))

        val user = userRow(fixture)
        assertEquals("disabled", user["status"])
        assertEquals(false, user["is_active"])
        assertEquals(1, auditCount(fixture, "tenant.users.disable", "users", fixture.targetUserId))
        assertEquals(1, outboxCount(fixture, "tenant.user.disabled", "users", fixture.targetUserId))
    }

    @Test
    fun replaysLifecycleChangeWithoutDuplicatingSideEffects() {
        val fixture = lifecycleFixture()
        insertLifecycleFixture(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-lifecycle-replay"))
        val command = TenantUserLifecycleCommand(
            tenantId = fixture.tenantId,
            userId = fixture.targetUserId,
            action = TenantUserLifecycleAction.DISABLE,
        )

        val first = lifecyclePort.changeTenantUserLifecycle(command)
        val second = lifecyclePort.changeTenantUserLifecycle(command)

        assertEquals(false, first.replayed)
        assertEquals(true, second.replayed)
        assertEquals(first.copy(replayed = true), second)
        assertEquals(1, outboxCount(fixture, "tenant.user.disabled", "users", fixture.targetUserId))
    }

    @Test
    fun reactivatesDisabledTenantUserAndRestoresIdentityResolution() {
        val fixture = lifecycleFixture(targetStatus = "disabled", targetIsActive = false)
        insertLifecycleFixture(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-lifecycle-reactivate"))

        assertNull(resolveIdentity(fixture))

        val receipt = lifecyclePort.changeTenantUserLifecycle(
            TenantUserLifecycleCommand(
                tenantId = fixture.tenantId,
                userId = fixture.targetUserId,
                action = TenantUserLifecycleAction.REACTIVATE,
            ),
        )

        assertEquals("active", receipt.status)
        assertEquals(true, receipt.isActive)
        assertEquals(true, receipt.changed)
        assertIs<ResolvedExternalIdentity.Tenant>(resolveIdentity(fixture))
    }

    @Test
    fun locksAndUnlocksTenantUser() {
        val fixture = lifecycleFixture()
        insertLifecycleFixture(fixture)

        requestContextHolder.set(tenantContext(fixture, "idem-lifecycle-lock"))
        val locked = lifecyclePort.changeTenantUserLifecycle(
            TenantUserLifecycleCommand(
                tenantId = fixture.tenantId,
                userId = fixture.targetUserId,
                action = TenantUserLifecycleAction.LOCK,
            ),
        )

        assertEquals("locked", locked.status)
        assertEquals(true, locked.isActive)
        assertNull(resolveIdentity(fixture))

        requestContextHolder.set(tenantContext(fixture, "idem-lifecycle-unlock"))
        val unlocked = lifecyclePort.changeTenantUserLifecycle(
            TenantUserLifecycleCommand(
                tenantId = fixture.tenantId,
                userId = fixture.targetUserId,
                action = TenantUserLifecycleAction.UNLOCK,
            ),
        )

        assertEquals("active", unlocked.status)
        assertEquals(true, unlocked.isActive)
        assertIs<ResolvedExternalIdentity.Tenant>(resolveIdentity(fixture))
    }

    @Test
    fun revokesIdentityLinkAndBlocksIdentityResolution() {
        val fixture = lifecycleFixture()
        insertLifecycleFixture(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-lifecycle-link-revoke"))

        assertIs<ResolvedExternalIdentity.Tenant>(resolveIdentity(fixture))

        val receipt = lifecyclePort.revokeTenantUserIdentityLink(
            RevokeTenantUserIdentityLinkCommand(
                tenantId = fixture.tenantId,
                userId = fixture.targetUserId,
                identityLinkId = fixture.identityLinkId,
            ),
        )

        assertEquals(fixture.identityLinkId, receipt.identityLinkId)
        assertEquals(true, receipt.changed)
        assertNotNull(receipt.revokedAt)
        assertNull(resolveIdentity(fixture))
        assertEquals(
            1,
            auditCount(
                fixture = fixture,
                action = "tenant.users.identity_link.revoke",
                entityType = "identity_links",
                entityId = fixture.identityLinkId,
            ),
        )
        assertEquals(
            1,
            outboxCount(
                fixture = fixture,
                eventType = "tenant.user.identity_link.revoked",
                aggregateType = "identity_links",
                aggregateId = fixture.identityLinkId,
            ),
        )
    }

    @Test
    fun rejectsLifecycleChangeAgainstUserWithTenantPermissionActorDoesNotHold() {
        val fixture = lifecycleFixture()
        insertLifecycleFixture(fixture)
        ensureTenantPermission(fixture.tenantId, "tenant.users.manage")
        ensureTenantPermission(fixture.tenantId, "reports.view")
        val actorRoleId = insertTenantRole(fixture.tenantId, "actor-lifecycle")
        val targetRoleId = insertTenantRole(fixture.tenantId, "target-reporting")
        insertTenantRolePermission(actorRoleId, fixture.tenantId, "tenant.users.manage")
        insertTenantRolePermission(targetRoleId, fixture.tenantId, "reports.view")
        insertTenantRoleAssignment(fixture.tenantId, fixture.actorUserId, actorRoleId)
        insertTenantRoleAssignment(fixture.tenantId, fixture.targetUserId, targetRoleId)
        requestContextHolder.set(tenantContext(fixture, "idem-lifecycle-hierarchy-denied"))

        val error = assertFailsWith<IllegalArgumentException> {
            lifecyclePort.changeTenantUserLifecycle(
                TenantUserLifecycleCommand(
                    tenantId = fixture.tenantId,
                    userId = fixture.targetUserId,
                    action = TenantUserLifecycleAction.DISABLE,
                ),
            )
        }

        assertEquals(
            "Tenant user cannot manage a user with permissions the actor does not hold",
            error.message,
        )
        assertEquals("active", userRow(fixture)["status"])
    }

    @Test
    fun tenantSuperAdminCanLifecycleHigherPrivilegeTenantUser() {
        val fixture = lifecycleFixture()
        insertLifecycleFixture(fixture)
        ensureTenantPermission(fixture.tenantId, "tenant.admin.all")
        ensureTenantPermission(fixture.tenantId, "reports.view")
        val actorRoleId = insertTenantRole(fixture.tenantId, "actor-tenant-admin")
        val targetRoleId = insertTenantRole(fixture.tenantId, "target-reporting")
        insertTenantRolePermission(actorRoleId, fixture.tenantId, "tenant.admin.all")
        insertTenantRolePermission(targetRoleId, fixture.tenantId, "reports.view")
        insertTenantRoleAssignment(fixture.tenantId, fixture.actorUserId, actorRoleId)
        insertTenantRoleAssignment(fixture.tenantId, fixture.targetUserId, targetRoleId)
        requestContextHolder.set(tenantContext(fixture, "idem-lifecycle-hierarchy-admin"))

        val receipt = lifecyclePort.changeTenantUserLifecycle(
            TenantUserLifecycleCommand(
                tenantId = fixture.tenantId,
                userId = fixture.targetUserId,
                action = TenantUserLifecycleAction.DISABLE,
            ),
        )

        assertEquals("disabled", receipt.status)
        assertEquals(true, receipt.changed)
    }

    @Test
    fun rejectsIdentityLinkRevocationAgainstUserWithPropertyPermissionActorDoesNotHold() {
        val fixture = lifecycleFixture()
        val propertyId = UUID.randomUUID()
        insertLifecycleFixture(fixture)
        insertProperty(fixture.tenantId, propertyId)
        ensureTenantPermission(fixture.tenantId, "tenant.users.manage")
        ensureTenantPermission(fixture.tenantId, "property.manage")
        val actorTenantRoleId = insertTenantRole(fixture.tenantId, "actor-lifecycle")
        val targetPropertyRoleId = insertPropertyRole(fixture.tenantId, "target-property-manager")
        insertTenantRolePermission(actorTenantRoleId, fixture.tenantId, "tenant.users.manage")
        insertPropertyRolePermission(targetPropertyRoleId, fixture.tenantId, "property.manage")
        insertTenantRoleAssignment(fixture.tenantId, fixture.actorUserId, actorTenantRoleId)
        insertPropertyRoleAssignment(fixture.tenantId, propertyId, fixture.targetUserId, targetPropertyRoleId)
        requestContextHolder.set(tenantContext(fixture, "idem-link-revoke-hierarchy-denied"))

        val error = assertFailsWith<IllegalArgumentException> {
            lifecyclePort.revokeTenantUserIdentityLink(
                RevokeTenantUserIdentityLinkCommand(
                    tenantId = fixture.tenantId,
                    userId = fixture.targetUserId,
                    identityLinkId = fixture.identityLinkId,
                ),
            )
        }

        assertEquals(
            "Tenant user cannot manage a user with permissions the actor does not hold",
            error.message,
        )
        assertNull(identityLinkRevokedAt(fixture.identityLinkId))
    }

    @Test
    fun rejectsSelfLifecycleChange() {
        val fixture = lifecycleFixture()
        insertLifecycleFixture(fixture)
        requestContextHolder.set(
            tenantContext(
                fixture = fixture.copy(targetUserId = fixture.actorUserId),
                idempotencyKey = "idem-lifecycle-self",
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            lifecyclePort.changeTenantUserLifecycle(
                TenantUserLifecycleCommand(
                    tenantId = fixture.tenantId,
                    userId = fixture.actorUserId,
                    action = TenantUserLifecycleAction.DISABLE,
                ),
            )
        }

        assertEquals("Tenant user cannot change own lifecycle state", error.message)
    }

    private fun lifecycleFixture(
        targetStatus: String = "active",
        targetIsActive: Boolean = true,
    ): LifecycleFixture {
        val targetUserId = UUID.randomUUID()
        val subject = "target-subject-$targetUserId"
        return LifecycleFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            actorUserId = UUID.randomUUID(),
            targetUserId = targetUserId,
            identityLinkId = UUID.randomUUID(),
            issuer = "https://issuer.example.com/realms/${UUID.randomUUID()}",
            subject = subject,
            email = "$subject@example.com",
            targetStatus = targetStatus,
            targetIsActive = targetIsActive,
        )
    }

    private fun insertLifecycleFixture(fixture: LifecycleFixture) {
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
        insertTenantUser(
            tenantId = fixture.tenantId,
            userId = fixture.actorUserId,
            email = "actor-${fixture.actorUserId}@example.com",
            status = "active",
            isActive = true,
        )
        insertTenantUser(
            tenantId = fixture.tenantId,
            userId = fixture.targetUserId,
            email = fixture.email,
            status = fixture.targetStatus,
            isActive = fixture.targetIsActive,
        )
        jdbcTemplate.update(
            """
            INSERT INTO identity_links (
                id,
                identity_mode,
                provider,
                issuer,
                subject,
                tenant_id,
                user_id,
                email
            )
            VALUES (?, 'tenant', 'oidc', ?, ?, ?, ?, ?)
            """.trimIndent(),
            fixture.identityLinkId,
            fixture.issuer,
            fixture.subject,
            fixture.tenantId,
            fixture.targetUserId,
            fixture.email,
        )
    }

    private fun insertTenantUser(
        tenantId: UUID,
        userId: UUID,
        email: String,
        status: String,
        isActive: Boolean,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            userId,
            tenantId,
            "User $userId",
            email,
            status,
            isActive,
        )
    }

    private fun resolveIdentity(fixture: LifecycleFixture): ResolvedExternalIdentity? {
        return externalIdentityResolver.resolve(
            ExternalIdentityPrincipal(
                issuer = fixture.issuer,
                subject = fixture.subject,
            ),
        )
    }

    private fun userRow(fixture: LifecycleFixture): Map<String, Any?> {
        return jdbcTemplate.queryForMap(
            """
            SELECT status, is_active
            FROM users
            WHERE tenant_id = ?
              AND id = ?
            """.trimIndent(),
            fixture.tenantId,
            fixture.targetUserId,
        )
    }

    private fun auditCount(
        fixture: LifecycleFixture,
        action: String,
        entityType: String,
        entityId: UUID,
    ): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM audit_logs
                WHERE tenant_id = ?
                  AND user_id = ?
                  AND action = ?
                  AND entity_type = ?
                  AND entity_id = ?
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
                fixture.actorUserId,
                action,
                entityType,
                entityId,
            ),
        )
    }

    private fun outboxCount(
        fixture: LifecycleFixture,
        eventType: String,
        aggregateType: String,
        aggregateId: UUID,
    ): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM outbox_events
                WHERE tenant_id = ?
                  AND aggregate_type = ?
                  AND aggregate_id = ?
                  AND event_type = ?
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
                aggregateType,
                aggregateId,
                eventType,
            ),
        )
    }

    private fun identityLinkRevokedAt(identityLinkId: UUID): Any? {
        return jdbcTemplate.queryForObject(
            "SELECT revoked_at FROM identity_links WHERE id = ?",
            Any::class.java,
            identityLinkId,
        )
    }

    private fun tenantContext(
        fixture: LifecycleFixture,
        idempotencyKey: String,
    ): RequestContext {
        return RequestContext(
            identity = RequestIdentity.Tenant(
                tenantId = fixture.tenantId,
                tenantUserId = fixture.actorUserId,
                correlationId = "corr-$idempotencyKey",
            ),
            correlationId = "corr-$idempotencyKey",
            idempotencyKey = idempotencyKey,
            httpMethod = "POST",
            requestPath = "/api/v1/tenants/${fixture.tenantId}/users/${fixture.targetUserId}",
        )
    }

    private fun ensureTenantPermission(tenantId: UUID, code: String) {
        jdbcTemplate.update(
            """
            INSERT INTO permissions (id, tenant_id, code, description)
            SELECT gen_random_uuid(), ?, pc.code, pc.description
            FROM permission_catalog pc
            WHERE pc.code = ?
            ON CONFLICT (tenant_id, code) DO UPDATE SET
                description = EXCLUDED.description,
                updated_at = now()
            """.trimIndent(),
            tenantId,
            code,
        )
    }

    private fun insertTenantRole(tenantId: UUID, codePrefix: String): UUID {
        val roleId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            roleId,
            tenantId,
            "Tenant Role $roleId",
            "$codePrefix-$roleId",
        )
        return roleId
    }

    private fun insertTenantRolePermission(roleId: UUID, tenantId: UUID, code: String) {
        jdbcTemplate.update(
            """
            INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
            SELECT ?, id
            FROM permissions
            WHERE tenant_id = ?
              AND code = ?
            """.trimIndent(),
            roleId,
            tenantId,
            code,
        )
    }

    private fun insertTenantRoleAssignment(tenantId: UUID, userId: UUID, roleId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
            VALUES (?, ?, ?)
            """.trimIndent(),
            userId,
            tenantId,
            roleId,
        )
    }

    private fun insertProperty(tenantId: UUID, propertyId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO properties (
                id, tenant_id, name, code, type, status, timezone, business_date
            )
            VALUES (?, ?, ?, ?, 'HOTEL', 'draft', 'Africa/Dar_es_Salaam', CURRENT_DATE)
            """.trimIndent(),
            propertyId,
            tenantId,
            "Property $propertyId",
            "P-${propertyId.toString().take(8)}",
        )
    }

    private fun insertPropertyRole(tenantId: UUID, namePrefix: String): UUID {
        val roleId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO roles (id, tenant_id, name, is_active)
            VALUES (?, ?, ?, true)
            """.trimIndent(),
            roleId,
            tenantId,
            "$namePrefix-$roleId",
        )
        return roleId
    }

    private fun insertPropertyRolePermission(roleId: UUID, tenantId: UUID, code: String) {
        jdbcTemplate.update(
            """
            INSERT INTO role_permissions (role_id, permission_id)
            SELECT ?, id
            FROM permissions
            WHERE tenant_id = ?
              AND code = ?
            """.trimIndent(),
            roleId,
            tenantId,
            code,
        )
    }

    private fun insertPropertyRoleAssignment(
        tenantId: UUID,
        propertyId: UUID,
        userId: UUID,
        roleId: UUID,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            userId,
            propertyId,
            roleId,
            tenantId,
        )
    }

    private data class LifecycleFixture(
        val planId: UUID,
        val tenantId: UUID,
        val actorUserId: UUID,
        val targetUserId: UUID,
        val identityLinkId: UUID,
        val issuer: String,
        val subject: String,
        val email: String,
        val targetStatus: String,
        val targetIsActive: Boolean,
    )
}
