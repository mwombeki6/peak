package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.AssignPropertyUserRoleCommand
import com.mwombeki.peak.usermanagement.api.CreatePropertyRoleCommand
import com.mwombeki.peak.usermanagement.api.DeactivatePropertyRoleCommand
import com.mwombeki.peak.usermanagement.api.EnsurePropertyAdministratorCommand
import com.mwombeki.peak.usermanagement.api.GetPropertyRoleQuery
import com.mwombeki.peak.usermanagement.api.ListPropertyRolesQuery
import com.mwombeki.peak.usermanagement.api.ListUserPropertyRolesQuery
import com.mwombeki.peak.usermanagement.api.PropertyAccessBootstrapPort
import com.mwombeki.peak.usermanagement.api.RevokePropertyUserRoleCommand
import com.mwombeki.peak.usermanagement.api.TenantPropertyRoleManagementPort
import com.mwombeki.peak.usermanagement.api.TenantUserRoleManagementNotFoundException
import com.mwombeki.peak.usermanagement.api.UpdatePropertyRoleCommand
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
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TenantPropertyRoleManagementServiceIntegrationTests {

    @Autowired
    private lateinit var propertyRoleManagementPort: TenantPropertyRoleManagementPort

    @Autowired
    private lateinit var propertyAccessBootstrapPort: PropertyAccessBootstrapPort

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @Autowired
    private lateinit var databaseSessionContext: DatabaseSessionContext

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
    }

    @Test
    fun tenantAdminAssignsAndRevokesPropertyRoleAccess() {
        val fixture = propertyRoleFixture()
        insertPropertyRoleFixture(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-property-role-create"))

        val role = propertyRoleManagementPort.createPropertyRole(
            CreatePropertyRoleCommand(
                tenantId = fixture.tenantId,
                propertyId = fixture.propertyId,
                name = "Front Office ${fixture.propertyId}",
                permissionCodes = listOf("property.view"),
            ),
        )

        assertTrue(role.changed)
        assertFalse(role.replayed)
        assertEquals(0, propertyAssignmentCount(fixture, role.propertyRoleId))
        assertFalse(targetCanAccessProperty(fixture, "property.view"))

        requestContextHolder.set(tenantContext(fixture, "idem-property-role-assign"))
        val assignment = propertyRoleManagementPort.assignPropertyUserRole(
            AssignPropertyUserRoleCommand(
                tenantId = fixture.tenantId,
                propertyId = fixture.propertyId,
                userId = fixture.targetUserId,
                propertyRoleId = role.propertyRoleId,
            ),
        )

        assertTrue(assignment.assigned)
        assertTrue(assignment.changed)
        assertEquals(1, propertyAssignmentCount(fixture, role.propertyRoleId))
        assertTrue(targetCanAccessProperty(fixture, "property.view"))

        requestContextHolder.set(tenantContext(fixture, "idem-property-role-revoke"))
        val revocation = propertyRoleManagementPort.revokePropertyUserRole(
            RevokePropertyUserRoleCommand(
                tenantId = fixture.tenantId,
                propertyId = fixture.propertyId,
                userId = fixture.targetUserId,
                propertyRoleId = role.propertyRoleId,
            ),
        )

        assertFalse(revocation.assigned)
        assertTrue(revocation.changed)
        assertEquals(0, propertyAssignmentCount(fixture, role.propertyRoleId))
        assertFalse(targetCanAccessProperty(fixture, "property.view"))
    }

    @Test
    fun propertyRoleViewerCanReadWithoutManageAccess() {
        val fixture = propertyRoleFixture()
        insertPropertyRoleFixture(
            fixture = fixture,
            grantTenantAdmin = false,
            grantManageAccess = false,
            grantPropertyRoleView = true,
        )
        val roleId = insertPropertyRole(
            fixture = fixture,
            name = "Read Only Visible ${fixture.propertyId}",
            permissionCodes = listOf("property.view"),
        )
        insertPropertyRoleAssignment(fixture, roleId, fixture.targetUserId)
        requestContextHolder.set(tenantContext(fixture, idempotencyKey = null))

        assertFalse(actorHasDirectTenantPermission(fixture, "tenant.properties.manage_access"))

        val roles = propertyRoleManagementPort.listPropertyRoles(
            ListPropertyRolesQuery(
                tenantId = fixture.tenantId,
                propertyId = fixture.propertyId,
            ),
        )
        val role = propertyRoleManagementPort.getPropertyRole(
            GetPropertyRoleQuery(
                tenantId = fixture.tenantId,
                propertyId = fixture.propertyId,
                propertyRoleId = roleId,
            ),
        )
        val userRoles = propertyRoleManagementPort.listUserPropertyRoles(
            ListUserPropertyRolesQuery(
                tenantId = fixture.tenantId,
                propertyId = fixture.propertyId,
                userId = fixture.targetUserId,
            ),
        )

        assertTrue(roles.any { it.propertyRoleId == roleId })
        assertEquals(roleId, role?.propertyRoleId)
        assertEquals(listOf(roleId), userRoles.map { it.propertyRoleId })
    }

    @Test
    fun accessManagerCanOnlyCreatePropertyRoleWithPermissionsHeldForThatProperty() {
        val fixture = propertyRoleFixture()
        insertPropertyRoleFixture(fixture, grantTenantAdmin = false)
        val actorRoleId = insertPropertyRole(
            fixture = fixture,
            name = "Actor Viewer ${fixture.propertyId}",
            permissionCodes = listOf("property.view"),
        )
        insertPropertyRoleAssignment(fixture, actorRoleId, fixture.actorUserId)
        requestContextHolder.set(tenantContext(fixture, "idem-property-role-delegated-create"))

        val role = propertyRoleManagementPort.createPropertyRole(
            CreatePropertyRoleCommand(
                tenantId = fixture.tenantId,
                propertyId = fixture.propertyId,
                name = "Delegated Viewer ${fixture.propertyId}",
                permissionCodes = listOf("property.view"),
            ),
        )

        assertTrue(role.changed)
    }

    @Test
    fun rejectsCreatingPropertyRoleWithPermissionActorDoesNotHold() {
        val fixture = propertyRoleFixture()
        insertPropertyRoleFixture(fixture, grantTenantAdmin = false)
        requestContextHolder.set(tenantContext(fixture, "idem-property-role-escalation-create"))

        val error = assertFailsWith<IllegalArgumentException> {
            propertyRoleManagementPort.createPropertyRole(
                CreatePropertyRoleCommand(
                    tenantId = fixture.tenantId,
                    propertyId = fixture.propertyId,
                    name = "Escalated Manager ${fixture.propertyId}",
                    permissionCodes = listOf("property.manage"),
                ),
            )
        }

        assertEquals(
            "Property roles cannot include permissions the actor does not hold for this property",
            error.message,
        )
        assertEquals(0, propertyRoleCount(fixture, "Escalated Manager ${fixture.propertyId}"))
    }

    @Test
    fun rejectsAssigningPropertyRoleWithPermissionActorDoesNotHold() {
        val fixture = propertyRoleFixture()
        insertPropertyRoleFixture(fixture, grantTenantAdmin = false)
        val roleId = insertPropertyRole(
            fixture = fixture,
            name = "Escalated Assign ${fixture.propertyId}",
            permissionCodes = listOf("property.manage"),
        )
        requestContextHolder.set(tenantContext(fixture, "idem-property-role-escalation-assign"))

        val error = assertFailsWith<IllegalArgumentException> {
            propertyRoleManagementPort.assignPropertyUserRole(
                AssignPropertyUserRoleCommand(
                    tenantId = fixture.tenantId,
                    propertyId = fixture.propertyId,
                    userId = fixture.targetUserId,
                    propertyRoleId = roleId,
                ),
            )
        }

        assertEquals(
            "Property roles cannot include permissions the actor does not hold for this property",
            error.message,
        )
        assertEquals(0, propertyAssignmentCount(fixture, roleId))
    }

    @Test
    fun rejectsRevokingPropertyRoleWithPermissionActorDoesNotHold() {
        val fixture = propertyRoleFixture()
        insertPropertyRoleFixture(fixture, grantTenantAdmin = false)
        val roleId = insertPropertyRole(
            fixture = fixture,
            name = "Escalated Revoke ${fixture.propertyId}",
            permissionCodes = listOf("property.manage"),
        )
        insertPropertyRoleAssignment(fixture, roleId, fixture.targetUserId)
        requestContextHolder.set(tenantContext(fixture, "idem-property-role-escalation-revoke"))

        val error = assertFailsWith<IllegalArgumentException> {
            propertyRoleManagementPort.revokePropertyUserRole(
                RevokePropertyUserRoleCommand(
                    tenantId = fixture.tenantId,
                    propertyId = fixture.propertyId,
                    userId = fixture.targetUserId,
                    propertyRoleId = roleId,
                ),
            )
        }

        assertEquals(
            "Property roles cannot include permissions the actor does not hold for this property",
            error.message,
        )
        assertEquals(1, propertyAssignmentCount(fixture, roleId))
    }

    @Test
    fun rejectsAssigningPropertyRoleToUserWithTenantPermissionActorDoesNotHold() {
        val fixture = propertyRoleFixture()
        insertPropertyRoleFixture(fixture, grantTenantAdmin = false)
        val actorViewerRoleId = insertPropertyRole(
            fixture = fixture,
            name = "Actor Assign Viewer ${fixture.propertyId}",
            permissionCodes = listOf("property.view"),
        )
        insertPropertyRoleAssignment(fixture, actorViewerRoleId, fixture.actorUserId)
        val roleId = insertPropertyRole(
            fixture = fixture,
            name = "Target Assign Viewer ${fixture.propertyId}",
            permissionCodes = listOf("property.view"),
        )
        insertTargetTenantRole(fixture, "tenant.properties.roles.view")
        requestContextHolder.set(tenantContext(fixture, "idem-property-role-target-tenant-assign"))

        val error = assertFailsWith<IllegalArgumentException> {
            propertyRoleManagementPort.assignPropertyUserRole(
                AssignPropertyUserRoleCommand(
                    tenantId = fixture.tenantId,
                    propertyId = fixture.propertyId,
                    userId = fixture.targetUserId,
                    propertyRoleId = roleId,
                ),
            )
        }

        assertEquals(
            "Tenant user cannot manage a user with permissions the actor does not hold",
            error.message,
        )
        assertEquals(0, propertyAssignmentCount(fixture, roleId))
    }

    @Test
    fun rejectsRevokingPropertyRoleFromUserWithTenantPermissionActorDoesNotHold() {
        val fixture = propertyRoleFixture()
        insertPropertyRoleFixture(fixture, grantTenantAdmin = false)
        val actorViewerRoleId = insertPropertyRole(
            fixture = fixture,
            name = "Actor Revoke Viewer ${fixture.propertyId}",
            permissionCodes = listOf("property.view"),
        )
        insertPropertyRoleAssignment(fixture, actorViewerRoleId, fixture.actorUserId)
        val roleId = insertPropertyRole(
            fixture = fixture,
            name = "Target Revoke Viewer ${fixture.propertyId}",
            permissionCodes = listOf("property.view"),
        )
        insertPropertyRoleAssignment(fixture, roleId, fixture.targetUserId)
        insertTargetTenantRole(fixture, "tenant.properties.roles.view")
        requestContextHolder.set(tenantContext(fixture, "idem-property-role-target-tenant-revoke"))

        val error = assertFailsWith<IllegalArgumentException> {
            propertyRoleManagementPort.revokePropertyUserRole(
                RevokePropertyUserRoleCommand(
                    tenantId = fixture.tenantId,
                    propertyId = fixture.propertyId,
                    userId = fixture.targetUserId,
                    propertyRoleId = roleId,
                ),
            )
        }

        assertEquals(
            "Tenant user cannot manage a user with permissions the actor does not hold",
            error.message,
        )
        assertEquals(1, propertyAssignmentCount(fixture, roleId))
    }

    @Test
    fun rejectsAssigningPropertyRoleToUserWithSamePropertyPermissionActorDoesNotHold() {
        val fixture = propertyRoleFixture()
        insertPropertyRoleFixture(fixture, grantTenantAdmin = false)
        val actorViewerRoleId = insertPropertyRole(
            fixture = fixture,
            name = "Actor Property Viewer ${fixture.propertyId}",
            permissionCodes = listOf("property.view"),
        )
        insertPropertyRoleAssignment(fixture, actorViewerRoleId, fixture.actorUserId)
        val targetManagerRoleId = insertPropertyRole(
            fixture = fixture,
            name = "Target Existing Manager ${fixture.propertyId}",
            permissionCodes = listOf("property.manage"),
        )
        insertPropertyRoleAssignment(fixture, targetManagerRoleId, fixture.targetUserId)
        val roleId = insertPropertyRole(
            fixture = fixture,
            name = "Target New Viewer ${fixture.propertyId}",
            permissionCodes = listOf("property.view"),
        )
        requestContextHolder.set(tenantContext(fixture, "idem-property-role-target-property-assign"))

        val error = assertFailsWith<IllegalArgumentException> {
            propertyRoleManagementPort.assignPropertyUserRole(
                AssignPropertyUserRoleCommand(
                    tenantId = fixture.tenantId,
                    propertyId = fixture.propertyId,
                    userId = fixture.targetUserId,
                    propertyRoleId = roleId,
                ),
            )
        }

        assertEquals(
            "Tenant user cannot manage a user with permissions the actor does not hold",
            error.message,
        )
        assertEquals(0, propertyAssignmentCount(fixture, roleId))
        assertEquals(1, propertyAssignmentCount(fixture, targetManagerRoleId))
    }

    @Test
    fun rejectsUpdatingPropertyRoleWithCurrentPermissionActorDoesNotHold() {
        val fixture = propertyRoleFixture()
        insertPropertyRoleFixture(fixture, grantTenantAdmin = false)
        val roleName = "Escalated Update ${fixture.propertyId}"
        val roleId = insertPropertyRole(
            fixture = fixture,
            name = roleName,
            permissionCodes = listOf("property.manage"),
        )
        requestContextHolder.set(tenantContext(fixture, "idem-property-role-escalation-update"))

        val error = assertFailsWith<IllegalArgumentException> {
            propertyRoleManagementPort.updatePropertyRole(
                UpdatePropertyRoleCommand(
                    tenantId = fixture.tenantId,
                    propertyId = fixture.propertyId,
                    propertyRoleId = roleId,
                    name = "Renamed ${fixture.propertyId}",
                    permissionCodes = null,
                ),
            )
        }

        assertEquals(
            "Property roles cannot include permissions the actor does not hold for this property",
            error.message,
        )
        assertEquals(roleName, propertyRoleName(fixture, roleId))
    }

    @Test
    fun rejectsDeactivatingPropertyRoleWithCurrentPermissionActorDoesNotHold() {
        val fixture = propertyRoleFixture()
        insertPropertyRoleFixture(fixture, grantTenantAdmin = false)
        val roleId = insertPropertyRole(
            fixture = fixture,
            name = "Escalated Deactivate ${fixture.propertyId}",
            permissionCodes = listOf("property.manage"),
        )
        insertPropertyRoleAssignment(fixture, roleId, fixture.targetUserId)
        requestContextHolder.set(tenantContext(fixture, "idem-property-role-escalation-deactivate"))

        val error = assertFailsWith<IllegalArgumentException> {
            propertyRoleManagementPort.deactivatePropertyRole(
                DeactivatePropertyRoleCommand(
                    tenantId = fixture.tenantId,
                    propertyId = fixture.propertyId,
                    propertyRoleId = roleId,
                ),
            )
        }

        assertEquals(
            "Property roles cannot include permissions the actor does not hold for this property",
            error.message,
        )
        assertTrue(propertyRoleActive(fixture, roleId))
        assertEquals(1, propertyAssignmentCount(fixture, roleId))
    }

    @Test
    fun rejectsAssigningAndRevokingSystemPropertyRolesThroughTenantApi() {
        val fixture = propertyRoleFixture()
        insertPropertyRoleFixture(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-property-role-system-bootstrap"))
        val systemRoleId = propertyAccessBootstrapPort.ensurePropertyAdministrator(
            EnsurePropertyAdministratorCommand(
                tenantId = fixture.tenantId,
                propertyId = fixture.propertyId,
                tenantUserId = fixture.targetUserId,
            ),
        ).propertyRoleId

        requestContextHolder.set(tenantContext(fixture, "idem-property-role-system-assign"))
        val assignError = assertFailsWith<IllegalArgumentException> {
            propertyRoleManagementPort.assignPropertyUserRole(
                AssignPropertyUserRoleCommand(
                    tenantId = fixture.tenantId,
                    propertyId = fixture.propertyId,
                    userId = fixture.targetUserId,
                    propertyRoleId = systemRoleId,
                ),
            )
        }
        assertEquals(
            "System property roles cannot be assigned or revoked through tenant property role management",
            assignError.message,
        )

        requestContextHolder.set(tenantContext(fixture, "idem-property-role-system-revoke"))
        val revokeError = assertFailsWith<IllegalArgumentException> {
            propertyRoleManagementPort.revokePropertyUserRole(
                RevokePropertyUserRoleCommand(
                    tenantId = fixture.tenantId,
                    propertyId = fixture.propertyId,
                    userId = fixture.targetUserId,
                    propertyRoleId = systemRoleId,
                ),
            )
        }
        assertEquals(
            "System property roles cannot be assigned or revoked through tenant property role management",
            revokeError.message,
        )
        assertEquals(1, propertyAssignmentCount(fixture, systemRoleId))
    }

    @Test
    fun rejectsSelfPropertyRoleAssignment() {
        val fixture = propertyRoleFixture()
        insertPropertyRoleFixture(fixture)
        val roleId = insertPropertyRole(fixture, "Self Guard ${fixture.propertyId}", listOf("property.view"))
        requestContextHolder.set(tenantContext(fixture, "idem-property-role-self"))

        val error = assertFailsWith<IllegalArgumentException> {
            propertyRoleManagementPort.assignPropertyUserRole(
                AssignPropertyUserRoleCommand(
                    tenantId = fixture.tenantId,
                    propertyId = fixture.propertyId,
                    userId = fixture.actorUserId,
                    propertyRoleId = roleId,
                ),
            )
        }

        assertEquals("Tenant user cannot assign own property roles", error.message)
    }

    @Test
    fun rejectsManagingPropertyOutsideTenant() {
        val fixture = propertyRoleFixture()
        insertPropertyRoleFixture(fixture)
        val otherPlanId = UUID.randomUUID()
        val otherTenantId = UUID.randomUUID()
        val otherPropertyId = UUID.randomUUID()
        insertPlan(otherPlanId)
        insertTenant(otherTenantId, otherPlanId)
        insertProperty(otherTenantId, otherPropertyId)
        requestContextHolder.set(tenantContext(fixture, "idem-property-role-cross-tenant"))

        val error = assertFailsWith<TenantUserRoleManagementNotFoundException> {
            propertyRoleManagementPort.createPropertyRole(
                CreatePropertyRoleCommand(
                    tenantId = fixture.tenantId,
                    propertyId = otherPropertyId,
                    name = "Cross Tenant Property Role",
                    permissionCodes = listOf("property.view"),
                ),
            )
        }

        assertEquals("Property was not found for tenant", error.message)
    }

    @Test
    fun propertyBootstrapCreatesSystemAdministratorAssignment() {
        val fixture = propertyRoleFixture()
        insertPropertyRoleFixture(fixture)
        requestContextHolder.set(tenantContext(fixture, "idem-property-bootstrap"))

        val receipt = propertyAccessBootstrapPort.ensurePropertyAdministrator(
            EnsurePropertyAdministratorCommand(
                tenantId = fixture.tenantId,
                propertyId = fixture.propertyId,
                tenantUserId = fixture.actorUserId,
            ),
        )

        assertTrue(receipt.changed)
        assertEquals(1, propertyAssignmentCount(fixture, receipt.propertyRoleId, fixture.actorUserId))
        assertTrue(
            jdbcTemplate.queryForObject(
                """
                SELECT is_system
                FROM roles
                WHERE tenant_id = ?
                  AND id = ?
                """.trimIndent(),
                Boolean::class.java,
                fixture.tenantId,
                receipt.propertyRoleId,
            ) == true,
        )
    }

    private fun targetCanAccessProperty(
        fixture: PropertyRoleFixture,
        permissionCode: String,
    ): Boolean {
        return requireNotNull(
            transactionTemplate.execute {
                databaseSessionContext.bind(
                    RequestIdentity.Tenant(
                        tenantId = fixture.tenantId,
                        tenantUserId = fixture.targetUserId,
                        correlationId = "corr-property-access-check",
                    ),
                )
                jdbcTemplate.queryForObject(
                    "SELECT can_access_module(?, ?, ?, 'property', ?)",
                    Boolean::class.java,
                    fixture.targetUserId,
                    fixture.tenantId,
                    fixture.propertyId,
                    permissionCode,
                ) == true
            },
        )
    }

    private fun insertPropertyRoleFixture(
        fixture: PropertyRoleFixture,
        grantTenantAdmin: Boolean = true,
        grantManageAccess: Boolean = true,
        grantPropertyRoleView: Boolean = false,
    ) {
        insertPlan(fixture.planId)
        insertTenant(fixture.tenantId, fixture.planId)
        insertProperty(fixture.tenantId, fixture.propertyId)
        insertTenantModule(fixture.tenantId, "tenant_admin")
        insertTenantModule(fixture.tenantId, "property")
        insertPropertyModule(fixture.tenantId, fixture.propertyId, "property")
        insertTenantUser(fixture.tenantId, fixture.actorUserId, "actor-${fixture.actorUserId}@example.com")
        insertTenantUser(fixture.tenantId, fixture.targetUserId, "target-${fixture.targetUserId}@example.com")
        ensureTenantPermission(fixture.tenantId, "tenant.properties.manage_access")
        ensureTenantPermission(fixture.tenantId, "tenant.properties.roles.view")
        ensureTenantPermission(fixture.tenantId, "tenant.admin.all")
        ensureTenantPermission(fixture.tenantId, "property.view")
        ensureTenantPermission(fixture.tenantId, "property.manage")
        ensureTenantPermission(fixture.tenantId, "property.lifecycle")
        ensureTenantPermission(fixture.tenantId, "property.roles.view")
        ensureTenantPermission(fixture.tenantId, "property.roles.manage")
        ensureTenantPermission(fixture.tenantId, "realtime.stream")
        ensureTenantPermission(fixture.tenantId, "admin.all")
        insertTenantRole(fixture.tenantId, fixture.actorRoleId)
        if (grantManageAccess) {
            insertTenantRolePermission(fixture.actorRoleId, fixture.tenantId, "tenant.properties.manage_access")
        }
        if (grantPropertyRoleView) {
            insertTenantRolePermission(fixture.actorRoleId, fixture.tenantId, "tenant.properties.roles.view")
        }
        if (grantTenantAdmin) {
            insertTenantRolePermission(fixture.actorRoleId, fixture.tenantId, "tenant.admin.all")
        }
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

    private fun insertPropertyRole(
        fixture: PropertyRoleFixture,
        name: String,
        permissionCodes: List<String>,
    ): UUID {
        val roleId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO roles (id, tenant_id, name, is_active)
            VALUES (?, ?, ?, true)
            """.trimIndent(),
            roleId,
            fixture.tenantId,
            name,
        )
        permissionCodes.forEach { code ->
            jdbcTemplate.update(
                """
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id
                FROM permissions
                WHERE tenant_id = ?
                  AND code = ?
                """.trimIndent(),
                roleId,
                fixture.tenantId,
                code,
            )
        }
        return roleId
    }

    private fun insertPropertyRoleAssignment(
        fixture: PropertyRoleFixture,
        propertyRoleId: UUID,
        userId: UUID,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            userId,
            fixture.propertyId,
            propertyRoleId,
            fixture.tenantId,
        )
    }

    private fun insertTargetTenantRole(
        fixture: PropertyRoleFixture,
        permissionCode: String,
    ): UUID {
        val roleId = UUID.randomUUID()
        insertTenantRole(fixture.tenantId, roleId)
        insertTenantRolePermission(roleId, fixture.tenantId, permissionCode)
        jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.targetUserId,
            fixture.tenantId,
            roleId,
        )
        return roleId
    }

    private fun propertyAssignmentCount(
        fixture: PropertyRoleFixture,
        propertyRoleId: UUID,
        userId: UUID = fixture.targetUserId,
    ): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM user_property_roles
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND user_id = ?
                  AND role_id = ?
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
                fixture.propertyId,
                userId,
                propertyRoleId,
            ),
        )
    }

    private fun propertyRoleCount(fixture: PropertyRoleFixture, name: String): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM roles
                WHERE tenant_id = ?
                  AND name = ?
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
                name,
            ),
        )
    }

    private fun propertyRoleName(fixture: PropertyRoleFixture, propertyRoleId: UUID): String? {
        return jdbcTemplate.queryForObject(
            """
            SELECT name
            FROM roles
            WHERE tenant_id = ?
              AND id = ?
            """.trimIndent(),
            String::class.java,
            fixture.tenantId,
            propertyRoleId,
        )
    }

    private fun propertyRoleActive(fixture: PropertyRoleFixture, propertyRoleId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT is_active
            FROM roles
            WHERE tenant_id = ?
              AND id = ?
            """.trimIndent(),
            Boolean::class.java,
            fixture.tenantId,
            propertyRoleId,
        ) == true
    }

    private fun insertPlan(planId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
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

    private fun insertTenantModule(tenantId: UUID, moduleId: String) {
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
            VALUES (?, ?, true, true)
            ON CONFLICT ON CONSTRAINT tenant_modules_tenant_id_module_id_key
            DO UPDATE SET is_enabled = true, is_configured = true
            """.trimIndent(),
            tenantId,
            moduleId,
        )
    }

    private fun insertPropertyModule(tenantId: UUID, propertyId: UUID, moduleId: String) {
        jdbcTemplate.update(
            """
            INSERT INTO property_modules (tenant_id, property_id, module_id, is_enabled, is_configured)
            VALUES (?, ?, ?, true, true)
            ON CONFLICT ON CONSTRAINT property_modules_tenant_id_property_id_module_id_key
            DO UPDATE SET is_enabled = true, is_configured = true
            """.trimIndent(),
            tenantId,
            propertyId,
            moduleId,
        )
    }

    private fun insertTenantUser(tenantId: UUID, userId: UUID, email: String) {
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

    private fun insertTenantRole(tenantId: UUID, roleId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            roleId,
            tenantId,
            "Property Access Manager $roleId",
            "property-access-manager-$roleId",
        )
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

    private fun tenantContext(
        fixture: PropertyRoleFixture,
        idempotencyKey: String?,
    ): RequestContext {
        val suffix = idempotencyKey ?: "property-role-read"
        return RequestContext(
            identity = RequestIdentity.Tenant(
                tenantId = fixture.tenantId,
                tenantUserId = fixture.actorUserId,
                correlationId = "corr-$suffix",
            ),
            correlationId = "corr-$suffix",
            idempotencyKey = idempotencyKey,
            httpMethod = "POST",
            requestPath = "/api/v1/tenants/${fixture.tenantId}/properties/${fixture.propertyId}/roles",
        )
    }

    private fun actorHasDirectTenantPermission(fixture: PropertyRoleFixture, code: String): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM user_tenant_roles utr
                JOIN tenant_role_permissions trp
                  ON trp.tenant_role_id = utr.tenant_role_id
                JOIN permissions p
                  ON p.id = trp.permission_id
                 AND p.tenant_id = utr.tenant_id
                WHERE utr.tenant_id = ?
                  AND utr.user_id = ?
                  AND p.code = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            fixture.tenantId,
            fixture.actorUserId,
            code,
        ) == true
    }

    private fun propertyRoleFixture(): PropertyRoleFixture {
        return PropertyRoleFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            actorUserId = UUID.randomUUID(),
            targetUserId = UUID.randomUUID(),
            actorRoleId = UUID.randomUUID(),
        )
    }

    private data class PropertyRoleFixture(
        val planId: UUID,
        val tenantId: UUID,
        val propertyId: UUID,
        val actorUserId: UUID,
        val targetUserId: UUID,
        val actorRoleId: UUID,
    )
}
