package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.AssignTenantAdministratorCommand
import com.mwombeki.peak.usermanagement.api.AssignTenantUserRoleCommand
import com.mwombeki.peak.usermanagement.api.CreateTenantRoleCommand
import com.mwombeki.peak.usermanagement.api.DeactivateTenantRoleCommand
import com.mwombeki.peak.usermanagement.api.ListTenantAdministratorsQuery
import com.mwombeki.peak.usermanagement.api.ListTenantPermissionsQuery
import com.mwombeki.peak.usermanagement.api.ListTenantRolesQuery
import com.mwombeki.peak.usermanagement.api.RevokeTenantAdministratorCommand
import com.mwombeki.peak.usermanagement.api.RevokeTenantUserRoleCommand
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementNotFoundException
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementPort
import com.mwombeki.peak.usermanagement.api.UpdateTenantRoleCommand
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TenantUserRoleManagementServiceIntegrationTests {

    @Autowired
    private lateinit var roleManagementPort: TenantUserRoleManagementPort

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
    }

    @Test
    fun assignsTenantUserRoleWithAuditOutboxAndIdempotency() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-role-assign"))

        val receipt = roleManagementPort.assignTenantUserRole(
            AssignTenantUserRoleCommand(
                tenantId = fixture.tenantId,
                userId = fixture.targetUserId,
                tenantRoleId = fixture.targetRoleId,
            ),
        )

        assertEquals(fixture.tenantId, receipt.tenantId)
        assertEquals(fixture.targetUserId, receipt.userId)
        assertEquals(fixture.targetRoleId, receipt.tenantRoleId)
        assertTrue(receipt.assigned)
        assertTrue(receipt.changed)
        assertFalse(receipt.replayed)
        assertEquals(1, roleAssignmentCount(fixture))
        assertEquals(fixture.actorUserId, assignedBy(fixture))
        assertEquals(
            1,
            auditCount(fixture, "tenant.users.role.assign", fixture.targetUserId),
        )
        assertEquals(
            1,
            outboxCount(fixture, "tenant.user.role.assigned", fixture.targetUserId),
        )
        assertEquals("succeeded", idempotencyStatus(fixture, "idem-role-assign"))
    }

    @Test
    fun replaysRoleAssignmentWithoutDuplicatingSideEffects() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-role-assign-replay"))
        val command = AssignTenantUserRoleCommand(
            tenantId = fixture.tenantId,
            userId = fixture.targetUserId,
            tenantRoleId = fixture.targetRoleId,
        )

        val first = roleManagementPort.assignTenantUserRole(command)
        val second = roleManagementPort.assignTenantUserRole(command)

        assertFalse(first.replayed)
        assertTrue(second.replayed)
        assertEquals(first.copy(replayed = true), second)
        assertEquals(1, roleAssignmentCount(fixture))
        assertEquals(
            1,
            outboxCount(fixture, "tenant.user.role.assigned", fixture.targetUserId),
        )
    }

    @Test
    fun revokesTenantUserRoleWithAuditOutboxAndIdempotency() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        insertTargetRoleAssignment(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-role-revoke"))

        val receipt = roleManagementPort.revokeTenantUserRole(
            RevokeTenantUserRoleCommand(
                tenantId = fixture.tenantId,
                userId = fixture.targetUserId,
                tenantRoleId = fixture.targetRoleId,
            ),
        )

        assertEquals(fixture.tenantId, receipt.tenantId)
        assertEquals(fixture.targetUserId, receipt.userId)
        assertEquals(fixture.targetRoleId, receipt.tenantRoleId)
        assertFalse(receipt.assigned)
        assertTrue(receipt.changed)
        assertFalse(receipt.replayed)
        assertEquals(0, roleAssignmentCount(fixture))
        assertEquals(
            1,
            auditCount(fixture, "tenant.users.role.revoke", fixture.targetUserId),
        )
        assertEquals(
            1,
            outboxCount(fixture, "tenant.user.role.revoked", fixture.targetUserId),
        )
        assertEquals("succeeded", idempotencyStatus(fixture, "idem-role-revoke"))
    }

    @Test
    fun listsTenantRolesAndPermissions() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        requestContextHolder.set(
            tenantContext(
                fixture = fixture,
                idempotencyKey = null,
                httpMethod = "GET",
                requestPath = "/api/v1/tenants/${fixture.tenantId}/roles",
            ),
        )

        val roles = roleManagementPort.listTenantRoles(
            ListTenantRolesQuery(fixture.tenantId),
        )
        val targetRole = roles.single { it.tenantRoleId == fixture.targetRoleId }
        val permissions = roleManagementPort.listTenantPermissions(
            ListTenantPermissionsQuery(fixture.tenantId),
        )

        assertEquals(fixture.targetRoleCode, targetRole.code)
        assertEquals(fixture.targetRoleName, targetRole.name)
        assertEquals(listOf("reports.view"), targetRole.permissionCodes)
        assertEquals(
            listOf("reports.view", "tenant.users.manage"),
            permissions.map { it.code },
        )
    }

    @Test
    fun rejectsSelfRoleAssignment() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-role-self-assign"))

        val error = assertFailsWith<IllegalArgumentException> {
            roleManagementPort.assignTenantUserRole(
                AssignTenantUserRoleCommand(
                    tenantId = fixture.tenantId,
                    userId = fixture.actorUserId,
                    tenantRoleId = fixture.targetRoleId,
                ),
            )
        }

        assertEquals("Tenant user cannot assign own tenant roles", error.message)
    }

    @Test
    fun rejectsSystemRoleAssignmentAndRevocationThroughTenantSelfService() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        jdbcTemplate.update(
            "UPDATE tenant_roles SET is_system = true WHERE id = ?",
            fixture.targetRoleId,
        )

        requestContextHolder.set(tenantContext(fixture, "idem-role-system-assign"))
        val assignError = assertFailsWith<IllegalArgumentException> {
            roleManagementPort.assignTenantUserRole(
                AssignTenantUserRoleCommand(
                    tenantId = fixture.tenantId,
                    userId = fixture.targetUserId,
                    tenantRoleId = fixture.targetRoleId,
                ),
            )
        }

        assertEquals(
            "System tenant roles cannot be assigned or revoked through tenant role management",
            assignError.message,
        )
        assertEquals(0, roleAssignmentCount(fixture))

        insertTargetRoleAssignment(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-role-system-revoke"))
        val revokeError = assertFailsWith<IllegalArgumentException> {
            roleManagementPort.revokeTenantUserRole(
                RevokeTenantUserRoleCommand(
                    tenantId = fixture.tenantId,
                    userId = fixture.targetUserId,
                    tenantRoleId = fixture.targetRoleId,
                ),
            )
        }

        assertEquals(
            "System tenant roles cannot be assigned or revoked through tenant role management",
            revokeError.message,
        )
        assertEquals(1, roleAssignmentCount(fixture))
    }

    @Test
    fun rejectsRoleFromAnotherTenant() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        val otherPlanId = UUID.randomUUID()
        val otherTenantId = UUID.randomUUID()
        val otherRoleId = UUID.randomUUID()
        insertPlan(otherPlanId)
        insertTenant(otherTenantId, otherPlanId)
        insertTenantRole(
            tenantId = otherTenantId,
            roleId = otherRoleId,
            name = "Other Tenant Role $otherRoleId",
            code = "other-tenant-role-$otherRoleId",
        )

        requestContextHolder.set(tenantContext(fixture, "idem-role-cross-tenant"))
        val error = assertFailsWith<TenantUserRoleManagementNotFoundException> {
            roleManagementPort.assignTenantUserRole(
                AssignTenantUserRoleCommand(
                    tenantId = fixture.tenantId,
                    userId = fixture.targetUserId,
                    tenantRoleId = otherRoleId,
                ),
            )
        }

        assertEquals("Active tenant role was not found", error.message)
        assertEquals(0, roleAssignmentCount(fixture))
    }

    @Test
    fun rejectsDelegatingPermissionActorDoesNotHold() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        removeActorReportsPermission(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-role-escalation"))

        val error = assertFailsWith<IllegalArgumentException> {
            roleManagementPort.assignTenantUserRole(
                AssignTenantUserRoleCommand(
                    tenantId = fixture.tenantId,
                    userId = fixture.targetUserId,
                    tenantRoleId = fixture.targetRoleId,
                ),
            )
        }

        assertEquals(
            "Tenant roles cannot include permissions the actor does not hold",
            error.message,
        )
        assertEquals(0, roleAssignmentCount(fixture))
    }

    @Test
    fun rejectsRevokingTenantRoleWithPermissionActorDoesNotHold() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        insertTargetRoleAssignment(fixture)
        removeActorReportsPermission(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-role-escalation-revoke"))

        val error = assertFailsWith<IllegalArgumentException> {
            roleManagementPort.revokeTenantUserRole(
                RevokeTenantUserRoleCommand(
                    tenantId = fixture.tenantId,
                    userId = fixture.targetUserId,
                    tenantRoleId = fixture.targetRoleId,
                ),
            )
        }

        assertEquals(
            "Tenant roles cannot include permissions the actor does not hold",
            error.message,
        )
        assertEquals(1, roleAssignmentCount(fixture))
    }

    @Test
    fun rejectsAssigningTenantRoleToUserWithTenantPermissionActorDoesNotHold() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        insertHigherPrivilegeTargetTenantRoleAssignment(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-role-target-tenant-hierarchy-assign"))

        val error = assertFailsWith<IllegalArgumentException> {
            roleManagementPort.assignTenantUserRole(
                AssignTenantUserRoleCommand(
                    tenantId = fixture.tenantId,
                    userId = fixture.targetUserId,
                    tenantRoleId = fixture.targetRoleId,
                ),
            )
        }

        assertEquals(
            "Tenant user cannot manage a user with permissions the actor does not hold",
            error.message,
        )
        assertEquals(0, roleAssignmentCount(fixture))
    }

    @Test
    fun rejectsRevokingTenantRoleFromUserWithTenantPermissionActorDoesNotHold() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        insertTargetRoleAssignment(fixture)
        insertHigherPrivilegeTargetTenantRoleAssignment(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-role-target-tenant-hierarchy-revoke"))

        val error = assertFailsWith<IllegalArgumentException> {
            roleManagementPort.revokeTenantUserRole(
                RevokeTenantUserRoleCommand(
                    tenantId = fixture.tenantId,
                    userId = fixture.targetUserId,
                    tenantRoleId = fixture.targetRoleId,
                ),
            )
        }

        assertEquals(
            "Tenant user cannot manage a user with permissions the actor does not hold",
            error.message,
        )
        assertEquals(1, roleAssignmentCount(fixture))
    }

    @Test
    fun rejectsAssigningTenantRoleToUserWithPropertyPermissionActorDoesNotHold() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        insertHigherPrivilegeTargetPropertyRoleAssignment(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-role-target-property-hierarchy-assign"))

        val error = assertFailsWith<IllegalArgumentException> {
            roleManagementPort.assignTenantUserRole(
                AssignTenantUserRoleCommand(
                    tenantId = fixture.tenantId,
                    userId = fixture.targetUserId,
                    tenantRoleId = fixture.targetRoleId,
                ),
            )
        }

        assertEquals(
            "Tenant user cannot manage a user with permissions the actor does not hold",
            error.message,
        )
        assertEquals(0, roleAssignmentCount(fixture))
    }

    @Test
    fun rejectsUpdatingTenantRoleWithCurrentPermissionActorDoesNotHold() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        removeActorReportsPermission(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-role-escalation-update"))

        val error = assertFailsWith<IllegalArgumentException> {
            roleManagementPort.updateTenantRole(
                UpdateTenantRoleCommand(
                    tenantId = fixture.tenantId,
                    tenantRoleId = fixture.targetRoleId,
                    name = "Renamed ${fixture.targetRoleId}",
                    description = null,
                    permissionCodes = null,
                ),
            )
        }

        assertEquals(
            "Tenant roles cannot include permissions the actor does not hold",
            error.message,
        )
        assertEquals(fixture.targetRoleName, tenantRoleName(fixture))
    }

    @Test
    fun rejectsDeactivatingTenantRoleWithCurrentPermissionActorDoesNotHold() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        insertTargetRoleAssignment(fixture)
        removeActorReportsPermission(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-role-escalation-deactivate"))

        val error = assertFailsWith<IllegalArgumentException> {
            roleManagementPort.deactivateTenantRole(
                DeactivateTenantRoleCommand(
                    tenantId = fixture.tenantId,
                    tenantRoleId = fixture.targetRoleId,
                ),
            )
        }

        assertEquals(
            "Tenant roles cannot include permissions the actor does not hold",
            error.message,
        )
        assertTrue(tenantRoleActive(fixture))
        assertEquals(1, roleAssignmentCount(fixture))
    }

    @Test
    fun tenantSuperAdministratorAssignsListsAndRevokesTenantAdministrator() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        val tenantAdminRoleId = insertSystemTenantAdministratorRole(
            fixture = fixture,
            assignedUserId = fixture.actorUserId,
        )
        insertActiveIdentityLink(fixture.tenantId, fixture.actorUserId)
        insertActiveIdentityLink(fixture.tenantId, fixture.targetUserId)

        requestContextHolder.set(tenantContext(fixture, "idem-tenant-admin-assign"))
        val assignCommand = AssignTenantAdministratorCommand(
            tenantId = fixture.tenantId,
            userId = fixture.targetUserId,
        )
        val assigned = roleManagementPort.assignTenantAdministrator(assignCommand)
        val replayedAssignment = roleManagementPort.assignTenantAdministrator(assignCommand)

        assertTrue(assigned.changed)
        assertTrue(assigned.assigned)
        assertEquals(tenantAdminRoleId, assigned.tenantRoleId)
        assertTrue(replayedAssignment.replayed)
        assertEquals(assigned.copy(replayed = true), replayedAssignment)
        assertEquals(
            1,
            tenantRoleAssignmentCount(
                fixture.tenantId,
                fixture.targetUserId,
                tenantAdminRoleId,
            ),
        )

        requestContextHolder.set(
            tenantContext(
                fixture = fixture,
                idempotencyKey = null,
                httpMethod = "GET",
                requestPath = "/api/v1/tenants/${fixture.tenantId}/administrators",
            ),
        )
        val administrators = roleManagementPort.listTenantAdministrators(
            ListTenantAdministratorsQuery(fixture.tenantId),
        )
        assertEquals(2, administrators.size)
        assertTrue(administrators.all { it.hasActiveIdentity })

        requestContextHolder.set(tenantContext(fixture, "idem-tenant-admin-revoke"))
        val revokeCommand = RevokeTenantAdministratorCommand(
            tenantId = fixture.tenantId,
            userId = fixture.targetUserId,
        )
        val revoked = roleManagementPort.revokeTenantAdministrator(revokeCommand)
        val replayedRevocation = roleManagementPort.revokeTenantAdministrator(revokeCommand)

        assertTrue(revoked.changed)
        assertFalse(revoked.assigned)
        assertTrue(replayedRevocation.replayed)
        assertEquals(revoked.copy(replayed = true), replayedRevocation)
        assertEquals(
            0,
            tenantRoleAssignmentCount(
                fixture.tenantId,
                fixture.targetUserId,
                tenantAdminRoleId,
            ),
        )
        assertEquals(
            1,
            auditCount(fixture, "tenant.administrator.revoked", fixture.targetUserId),
        )
        assertEquals(
            1,
            outboxCount(fixture, "tenant.administrator.revoked", fixture.targetUserId),
        )
    }

    @Test
    fun delegatedTenantGovernorCanAppointAnotherTenantAdministrator() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        val tenantAdminRoleId = insertSystemTenantAdministratorRole(fixture)
        grantActorTenantPermission(fixture, "tenant.administrators.manage")
        requestContextHolder.set(tenantContext(fixture, "idem-delegated-tenant-admin-assign"))

        val receipt = roleManagementPort.assignTenantAdministrator(
            AssignTenantAdministratorCommand(
                tenantId = fixture.tenantId,
                userId = fixture.targetUserId,
            ),
        )

        assertTrue(receipt.changed)
        assertTrue(receipt.assigned)
        assertEquals(tenantAdminRoleId, receipt.tenantRoleId)
    }

    @Test
    fun rejectsRevokingLastEffectiveTenantAdministrator() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        val tenantAdminRoleId = insertSystemTenantAdministratorRole(
            fixture = fixture,
            assignedUserId = fixture.targetUserId,
        )
        grantActorTenantPermission(fixture, "tenant.administrators.manage")
        insertActiveIdentityLink(fixture.tenantId, fixture.targetUserId)
        requestContextHolder.set(tenantContext(fixture, "idem-last-tenant-admin-revoke"))

        val error = assertFailsWith<IllegalArgumentException> {
            roleManagementPort.revokeTenantAdministrator(
                RevokeTenantAdministratorCommand(
                    tenantId = fixture.tenantId,
                    userId = fixture.targetUserId,
                ),
            )
        }

        assertEquals(
            "Tenant administrator access cannot be removed without another active administrator",
            error.message,
        )
        assertEquals(
            1,
            tenantRoleAssignmentCount(
                fixture.tenantId,
                fixture.targetUserId,
                tenantAdminRoleId,
            ),
        )
    }

    @Test
    fun rejectsTenantAdministratorAssignmentWithoutGovernancePermission() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)
        insertSystemTenantAdministratorRole(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-tenant-admin-permission-denied"))

        val error = assertFailsWith<IllegalArgumentException> {
            roleManagementPort.assignTenantAdministrator(
                AssignTenantAdministratorCommand(
                    tenantId = fixture.tenantId,
                    userId = fixture.targetUserId,
                ),
            )
        }

        assertEquals(
            "Tenant user lacks tenant administrator management permission",
            error.message,
        )
    }

    @Test
    fun rejectsReservedTenantAdministratorCodeAndNameForDynamicRoles() {
        val fixture = roleManagementFixture()
        insertRoleManagementFixture(fixture)

        requestContextHolder.set(tenantContext(fixture, "idem-reserved-tenant-admin-code"))
        val codeError = assertFailsWith<IllegalArgumentException> {
            roleManagementPort.createTenantRole(
                CreateTenantRoleCommand(
                    tenantId = fixture.tenantId,
                    code = " TENANT_ADMIN ",
                    name = "Different Name",
                    description = null,
                    permissionCodes = listOf("reports.view"),
                ),
            )
        }
        assertEquals(
            "tenant_admin is reserved for the system Tenant Administrator role",
            codeError.message,
        )

        requestContextHolder.set(tenantContext(fixture, "idem-reserved-tenant-admin-name"))
        val nameError = assertFailsWith<IllegalArgumentException> {
            roleManagementPort.createTenantRole(
                CreateTenantRoleCommand(
                    tenantId = fixture.tenantId,
                    code = "different-code",
                    name = " tenant administrator ",
                    description = null,
                    permissionCodes = listOf("reports.view"),
                ),
            )
        }
        assertEquals(
            "Tenant Administrator is reserved for the system tenant role",
            nameError.message,
        )
    }

    private fun roleManagementFixture(): RoleManagementFixture {
        val actorRoleId = UUID.randomUUID()
        val targetRoleId = UUID.randomUUID()
        return RoleManagementFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            actorUserId = UUID.randomUUID(),
            targetUserId = UUID.randomUUID(),
            actorRoleId = actorRoleId,
            targetRoleId = targetRoleId,
            managePermissionId = UUID.randomUUID(),
            reportsPermissionId = UUID.randomUUID(),
            actorRoleCode = "actor-$actorRoleId",
            targetRoleCode = "assignable-$targetRoleId",
            actorRoleName = "Actor Role $actorRoleId",
            targetRoleName = "Assignable Role $targetRoleId",
        )
    }

    private fun insertRoleManagementFixture(fixture: RoleManagementFixture) {
        insertPlan(fixture.planId)
        insertTenant(fixture.tenantId, fixture.planId)
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
            VALUES (?, 'tenant_admin', true, true)
            """.trimIndent(),
            fixture.tenantId,
        )
        insertTenantUser(
            tenantId = fixture.tenantId,
            userId = fixture.actorUserId,
            email = "actor-${fixture.actorUserId}@example.com",
        )
        insertTenantUser(
            tenantId = fixture.tenantId,
            userId = fixture.targetUserId,
            email = "target-${fixture.targetUserId}@example.com",
        )
        insertTenantRole(
            tenantId = fixture.tenantId,
            roleId = fixture.actorRoleId,
            name = fixture.actorRoleName,
            code = fixture.actorRoleCode,
        )
        insertTenantRole(
            tenantId = fixture.tenantId,
            roleId = fixture.targetRoleId,
            name = fixture.targetRoleName,
            code = fixture.targetRoleCode,
        )
        insertPermission(
            tenantId = fixture.tenantId,
            permissionId = fixture.managePermissionId,
            code = "tenant.users.manage",
            description = "Manage tenant users",
        )
        insertPermission(
            tenantId = fixture.tenantId,
            permissionId = fixture.reportsPermissionId,
            code = "reports.view",
            description = "View reports",
        )
        insertRolePermission(fixture.actorRoleId, fixture.managePermissionId)
        insertRolePermission(fixture.actorRoleId, fixture.reportsPermissionId)
        insertRolePermission(fixture.targetRoleId, fixture.reportsPermissionId)
        jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.actorUserId,
            fixture.tenantId,
            fixture.actorRoleId,
        )
    }

    private fun insertTenantUser(
        tenantId: UUID,
        userId: UUID,
        email: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            userId,
            tenantId,
            "User $userId",
            email,
        )
    }

    private fun insertSystemTenantAdministratorRole(
        fixture: RoleManagementFixture,
        assignedUserId: UUID? = null,
    ): UUID {
        val tenantRoleId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (
                id,
                tenant_id,
                name,
                code,
                description,
                is_system,
                is_active
            )
            VALUES (
                ?, ?, 'Tenant Administrator', 'tenant_admin',
                'Immutable tenant administrator role provisioned by the platform',
                true, true
            )
            """.trimIndent(),
            tenantRoleId,
            fixture.tenantId,
        )
        listOf(
            "tenant.admin.all",
            "tenant.administrators.manage",
            "tenant.roles.view",
        ).forEach { code ->
            val permissionId = ensureTenantPermission(fixture.tenantId, code)
            jdbcTemplate.update(
                """
                INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
                VALUES (?, ?)
                ON CONFLICT ON CONSTRAINT tenant_role_permissions_pkey DO NOTHING
                """.trimIndent(),
                tenantRoleId,
                permissionId,
            )
        }
        if (assignedUserId != null) {
            jdbcTemplate.update(
                """
                INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
                VALUES (?, ?, ?)
                """.trimIndent(),
                assignedUserId,
                fixture.tenantId,
                tenantRoleId,
            )
        }
        return tenantRoleId
    }

    private fun grantActorTenantPermission(fixture: RoleManagementFixture, code: String) {
        val permissionId = ensureTenantPermission(fixture.tenantId, code)
        jdbcTemplate.update(
            """
            INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
            VALUES (?, ?)
            ON CONFLICT ON CONSTRAINT tenant_role_permissions_pkey DO NOTHING
            """.trimIndent(),
            fixture.actorRoleId,
            permissionId,
        )
    }

    private fun ensureTenantPermission(tenantId: UUID, code: String): UUID {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                INSERT INTO permissions (id, tenant_id, code, description)
                SELECT gen_random_uuid(), ?, pc.code, pc.description
                FROM permission_catalog pc
                WHERE pc.code = ?
                ON CONFLICT (tenant_id, code) DO UPDATE SET
                    description = EXCLUDED.description,
                    updated_at = now()
                RETURNING id
                """.trimIndent(),
                UUID::class.java,
                tenantId,
                code,
            ),
        )
    }

    private fun insertActiveIdentityLink(tenantId: UUID, userId: UUID) {
        val identityLinkId = UUID.randomUUID()
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
            SELECT ?, 'tenant', 'oidc', ?, ?, tenant_id, id, email
            FROM users
            WHERE tenant_id = ?
              AND id = ?
            """.trimIndent(),
            identityLinkId,
            "https://issuer.example.com/realms/$tenantId",
            "subject-$identityLinkId",
            tenantId,
            userId,
        )
    }

    private fun insertPlan(planId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO plans (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            planId,
            "Plan $planId",
            "plan-$planId",
        )
    }

    private fun insertTenant(tenantId: UUID, planId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, schema_name, plan_id)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            tenantId,
            "Tenant $tenantId",
            "tenant-$tenantId",
            "tenant_$tenantId".replace("-", "_"),
            planId,
        )
    }

    private fun insertTenantRole(
        tenantId: UUID,
        roleId: UUID,
        name: String,
        code: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            roleId,
            tenantId,
            name,
            code,
        )
    }

    private fun insertPermission(
        tenantId: UUID,
        permissionId: UUID,
        code: String,
        description: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO permissions (id, tenant_id, code, description)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            permissionId,
            tenantId,
            code,
            description,
        )
    }

    private fun insertRolePermission(roleId: UUID, permissionId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
            VALUES (?, ?)
            """.trimIndent(),
            roleId,
            permissionId,
        )
    }

    private fun insertTargetRoleAssignment(fixture: RoleManagementFixture) {
        jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id, assigned_by)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.targetUserId,
            fixture.tenantId,
            fixture.targetRoleId,
            fixture.actorUserId,
        )
    }

    private fun insertHigherPrivilegeTargetTenantRoleAssignment(fixture: RoleManagementFixture) {
        val elevatedPermissionId = UUID.randomUUID()
        val elevatedRoleId = UUID.randomUUID()
        insertPermission(
            tenantId = fixture.tenantId,
            permissionId = elevatedPermissionId,
            code = "tenant.roles.view",
            description = "View tenant roles",
        )
        insertTenantRole(
            tenantId = fixture.tenantId,
            roleId = elevatedRoleId,
            name = "Elevated Tenant Role $elevatedRoleId",
            code = "elevated-tenant-role-$elevatedRoleId",
        )
        insertRolePermission(elevatedRoleId, elevatedPermissionId)
        jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.targetUserId,
            fixture.tenantId,
            elevatedRoleId,
        )
    }

    private fun insertHigherPrivilegeTargetPropertyRoleAssignment(fixture: RoleManagementFixture) {
        val propertyId = UUID.randomUUID()
        val propertyRoleId = UUID.randomUUID()
        val propertyPermissionId = UUID.randomUUID()
        insertProperty(fixture.tenantId, propertyId)
        insertPermission(
            tenantId = fixture.tenantId,
            permissionId = propertyPermissionId,
            code = "property.manage",
            description = "Manage property",
        )
        jdbcTemplate.update(
            """
            INSERT INTO roles (id, tenant_id, name, is_active)
            VALUES (?, ?, ?, true)
            """.trimIndent(),
            propertyRoleId,
            fixture.tenantId,
            "Elevated Property Role $propertyRoleId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO role_permissions (role_id, permission_id)
            VALUES (?, ?)
            """.trimIndent(),
            propertyRoleId,
            propertyPermissionId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.targetUserId,
            propertyId,
            propertyRoleId,
            fixture.tenantId,
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

    private fun removeActorReportsPermission(fixture: RoleManagementFixture) {
        jdbcTemplate.update(
            """
            DELETE FROM tenant_role_permissions
            WHERE tenant_role_id = ?
              AND permission_id = ?
            """.trimIndent(),
            fixture.actorRoleId,
            fixture.reportsPermissionId,
        )
    }

    private fun tenantRoleName(fixture: RoleManagementFixture): String? {
        return jdbcTemplate.queryForObject(
            """
            SELECT name
            FROM tenant_roles
            WHERE tenant_id = ?
              AND id = ?
            """.trimIndent(),
            String::class.java,
            fixture.tenantId,
            fixture.targetRoleId,
        )
    }

    private fun tenantRoleActive(fixture: RoleManagementFixture): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT is_active
            FROM tenant_roles
            WHERE tenant_id = ?
              AND id = ?
            """.trimIndent(),
            Boolean::class.java,
            fixture.tenantId,
            fixture.targetRoleId,
        ) == true
    }

    private fun roleAssignmentCount(fixture: RoleManagementFixture): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM user_tenant_roles
                WHERE tenant_id = ?
                  AND user_id = ?
                  AND tenant_role_id = ?
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
                fixture.targetUserId,
                fixture.targetRoleId,
            ),
        )
    }

    private fun tenantRoleAssignmentCount(
        tenantId: UUID,
        userId: UUID,
        tenantRoleId: UUID,
    ): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM user_tenant_roles
                WHERE tenant_id = ?
                  AND user_id = ?
                  AND tenant_role_id = ?
                """.trimIndent(),
                Int::class.java,
                tenantId,
                userId,
                tenantRoleId,
            ),
        )
    }

    private fun assignedBy(fixture: RoleManagementFixture): UUID? {
        return jdbcTemplate.queryForObject(
            """
            SELECT assigned_by
            FROM user_tenant_roles
            WHERE tenant_id = ?
              AND user_id = ?
              AND tenant_role_id = ?
            """.trimIndent(),
            UUID::class.java,
            fixture.tenantId,
            fixture.targetUserId,
            fixture.targetRoleId,
        )
    }

    private fun auditCount(
        fixture: RoleManagementFixture,
        action: String,
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
                  AND entity_type = 'user_tenant_roles'
                  AND entity_id = ?
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
                fixture.actorUserId,
                action,
                entityId,
            ),
        )
    }

    private fun outboxCount(
        fixture: RoleManagementFixture,
        eventType: String,
        aggregateId: UUID,
    ): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM outbox_events
                WHERE tenant_id = ?
                  AND aggregate_type = 'user_tenant_roles'
                  AND aggregate_id = ?
                  AND event_type = ?
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
                aggregateId,
                eventType,
            ),
        )
    }

    private fun idempotencyStatus(
        fixture: RoleManagementFixture,
        idempotencyKey: String,
    ): String? {
        return jdbcTemplate.queryForObject(
            """
            SELECT status
            FROM idempotency_keys
            WHERE tenant_id = ?
              AND idempotency_key = ?
            """.trimIndent(),
            String::class.java,
            fixture.tenantId,
            idempotencyKey,
        )
    }

    private fun tenantContext(
        fixture: RoleManagementFixture,
        idempotencyKey: String?,
        httpMethod: String = "POST",
        requestPath: String = "/api/v1/tenants/${fixture.tenantId}/users/" +
                "${fixture.targetUserId}/roles/${fixture.targetRoleId}",
    ): RequestContext {
        val correlationSuffix = idempotencyKey ?: "role-read"
        return RequestContext(
            identity = RequestIdentity.Tenant(
                tenantId = fixture.tenantId,
                tenantUserId = fixture.actorUserId,
                correlationId = "corr-$correlationSuffix",
            ),
            correlationId = "corr-$correlationSuffix",
            idempotencyKey = idempotencyKey,
            httpMethod = httpMethod,
            requestPath = requestPath,
        )
    }

    private data class RoleManagementFixture(
        val planId: UUID,
        val tenantId: UUID,
        val actorUserId: UUID,
        val targetUserId: UUID,
        val actorRoleId: UUID,
        val targetRoleId: UUID,
        val managePermissionId: UUID,
        val reportsPermissionId: UUID,
        val actorRoleCode: String,
        val targetRoleCode: String,
        val actorRoleName: String,
        val targetRoleName: String,
    )
}
