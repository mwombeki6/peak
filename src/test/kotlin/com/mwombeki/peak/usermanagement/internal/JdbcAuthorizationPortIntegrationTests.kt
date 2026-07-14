package com.mwombeki.peak.usermanagement.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.usermanagement.api.AuthorizationDeniedException
import com.mwombeki.peak.usermanagement.api.AuthorizationPort
import com.mwombeki.peak.usermanagement.api.GuardMode
import com.mwombeki.peak.usermanagement.api.RouteAuthorizationRequest
import com.mwombeki.peak.usermanagement.api.RouteScope
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
class JdbcAuthorizationPortIntegrationTests {

    @Autowired
    private lateinit var authorizationPort: AuthorizationPort

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
    }

    @Test
    fun allowsTenantUserWithTenantPermission() {
        val fixture = tenantFixture("tenant.profile.manage")

        val decision = requireNotNull(
            transactionTemplate.execute {
                insertTenantFixture(fixture)
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Tenant(
                            tenantId = fixture.tenantId,
                            tenantUserId = fixture.userId,
                            correlationId = "corr-auth-tenant",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "tenant_admin",
                        guardMode = GuardMode.STAFF_PERMISSION,
                        routeScope = RouteScope.TENANT,
                        permissionCode = "tenant.profile.manage",
                        tenantId = fixture.tenantId,
                    ),
                )
            },
        )

        assertTrue(decision.allowed)
        assertEquals(null, decision.reason)
    }

    @Test
    fun deniesTenantUserWithoutPermission() {
        val fixture = tenantFixture("tenant.profile.view")

        val decision = requireNotNull(
            transactionTemplate.execute {
                insertTenantFixture(fixture)
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Tenant(
                            tenantId = fixture.tenantId,
                            tenantUserId = fixture.userId,
                            correlationId = "corr-auth-tenant-denied",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "tenant_admin",
                        guardMode = GuardMode.STAFF_PERMISSION,
                        routeScope = RouteScope.TENANT,
                        permissionCode = "tenant.profile.manage",
                        tenantId = fixture.tenantId,
                    ),
                )
            },
        )

        assertFalse(decision.allowed)
        assertEquals("Tenant user lacks required module permission", decision.reason)
    }

    @Test
    fun deniesTenantScopedCatalogWriteWhenUserOnlyHasPropertyRolePermission() {
        val fixture = catalogWriteFixture()

        val decision = requireNotNull(
            transactionTemplate.execute {
                insertCatalogWriteFixture(
                    fixture = fixture,
                    grantTenantCatalogPermission = false,
                    grantPropertyInventoryPermission = true,
                )
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Tenant(
                            tenantId = fixture.tenantId,
                            tenantUserId = fixture.userId,
                            correlationId = "corr-auth-catalog-property-denied",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "inventory",
                        guardMode = GuardMode.STAFF_PERMISSION,
                        routeScope = RouteScope.TENANT,
                        permissionCode = "inventory.catalog.manage",
                        tenantId = fixture.tenantId,
                        propertyId = fixture.propertyId,
                    ),
                )
            },
        )

        assertFalse(decision.allowed)
        assertEquals("Tenant user lacks required module permission", decision.reason)
    }

    @Test
    fun allowsTenantScopedCatalogWriteWhenUserHasTenantCatalogPermission() {
        val fixture = catalogWriteFixture()

        val decision = requireNotNull(
            transactionTemplate.execute {
                insertCatalogWriteFixture(
                    fixture = fixture,
                    grantTenantCatalogPermission = true,
                    grantPropertyInventoryPermission = false,
                )
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Tenant(
                            tenantId = fixture.tenantId,
                            tenantUserId = fixture.userId,
                            correlationId = "corr-auth-catalog-tenant-allowed",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "inventory",
                        guardMode = GuardMode.STAFF_PERMISSION,
                        routeScope = RouteScope.TENANT,
                        permissionCode = "inventory.catalog.manage",
                        tenantId = fixture.tenantId,
                        propertyId = fixture.propertyId,
                    ),
                )
            },
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun deniesPlatformIdentityOnTenantStaffGuard() {
        val fixture = tenantFixture("tenant.profile.manage")
        val platformUserId = UUID.randomUUID()

        val decision = requireNotNull(
            transactionTemplate.execute {
                insertTenantFixture(fixture)
                insertPlatformUser(platformUserId)
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Platform(
                            platformUserId = platformUserId,
                            correlationId = "corr-auth-platform-on-tenant",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "tenant_admin",
                        guardMode = GuardMode.STAFF_PERMISSION,
                        routeScope = RouteScope.TENANT,
                        permissionCode = "tenant.profile.manage",
                        tenantId = fixture.tenantId,
                    ),
                )
            },
        )

        assertFalse(decision.allowed)
        assertEquals("Tenant identity is required", decision.reason)
    }

    @Test
    fun allowsPublicAccessWhenTenantAndPropertyModuleAreEnabled() {
        val fixture = publicModuleFixture()

        val decision = requireNotNull(
            transactionTemplate.execute {
                insertPublicModuleFixture(fixture)
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Public(
                            tenantId = fixture.tenantId,
                            propertyId = fixture.propertyId,
                            correlationId = "corr-auth-public",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "booking_engine",
                        guardMode = GuardMode.MODULE_ONLY,
                        routeScope = RouteScope.PUBLIC_PROPERTY,
                        tenantId = fixture.tenantId,
                        propertyId = fixture.propertyId,
                    ),
                )
            },
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun resolvesTenantForPublicPropertyAuthorization() {
        val fixture = publicModuleFixture()

        val decision = requireNotNull(
            transactionTemplate.execute {
                insertPublicModuleFixture(fixture)
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Public(
                            propertyId = fixture.propertyId,
                            correlationId = "corr-auth-public-route",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "booking_engine",
                        guardMode = GuardMode.MODULE_ONLY,
                        routeScope = RouteScope.PUBLIC_PROPERTY,
                        propertyId = fixture.propertyId,
                    ),
                )
            },
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun deniesPublicAccessWhenIdentityPropertyDoesNotMatchRoute() {
        val fixture = publicModuleFixture()
        val otherPropertyId = UUID.randomUUID()

        val decision = requireNotNull(
            transactionTemplate.execute {
                insertPublicModuleFixture(fixture)
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Public(
                            propertyId = otherPropertyId,
                            correlationId = "corr-auth-public-property-mismatch",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "booking_engine",
                        guardMode = GuardMode.MODULE_ONLY,
                        routeScope = RouteScope.PUBLIC_PROPERTY,
                        propertyId = fixture.propertyId,
                    ),
                )
            },
        )

        assertFalse(decision.allowed)
        assertEquals("Requested property does not match public identity", decision.reason)
    }

    @Test
    fun allowsPlatformUserWithPlatformPermission() {
        val fixture = platformFixture()

        val decision = requireNotNull(
            transactionTemplate.execute {
                insertPlatformFixture(fixture)
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Platform(
                            platformUserId = fixture.platformUserId,
                            correlationId = "corr-auth-platform",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "platform_admin",
                        guardMode = GuardMode.PLATFORM_PERMISSION,
                        routeScope = RouteScope.PLATFORM,
                        permissionCode = "platform.tenants.manage",
                    ),
                )
            },
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun deniesPlatformUserWithoutPermission() {
        val platformUserId = UUID.randomUUID()

        val decision = requireNotNull(
            transactionTemplate.execute {
                insertPlatformUser(platformUserId)
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Platform(
                            platformUserId = platformUserId,
                            correlationId = "corr-auth-platform-denied",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "platform_admin",
                        guardMode = GuardMode.PLATFORM_PERMISSION,
                        routeScope = RouteScope.PLATFORM,
                        permissionCode = "platform.tenants.manage",
                    ),
                )
            },
        )

        assertFalse(decision.allowed)
        assertEquals("Platform user lacks required permission", decision.reason)
    }

    @Test
    fun deniesSupportIdentityOnGlobalPlatformRoute() {
        val fixture = platformFixture()
        val tenantId = UUID.randomUUID()
        val planId = UUID.randomUUID()
        val supportSessionId = UUID.randomUUID()
        insertPlatformFixture(fixture)
        insertPlan(planId)
        insertTenant(tenantId, planId)
        insertActiveSupportGrant(
            platformUserId = fixture.platformUserId,
            tenantId = tenantId,
            actionCode = "platform.tenants.manage",
            supportSessionId = supportSessionId,
        )

        val decision = requireNotNull(
            transactionTemplate.execute {
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Support(
                            platformUserId = fixture.platformUserId,
                            tenantId = tenantId,
                            supportSessionId = supportSessionId,
                            correlationId = "corr-auth-support-global-denied",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "platform_admin",
                        guardMode = GuardMode.PLATFORM_PERMISSION,
                        routeScope = RouteScope.PLATFORM,
                        permissionCode = "platform.tenants.manage",
                    ),
                )
            },
        )

        assertFalse(decision.allowed)
        assertEquals(
            "Support identity requires a tenant-targeted platform operation",
            decision.reason,
        )
    }

    @Test
    fun deniesSupportIdentityWhenClaimedSessionIsNotTheApprovedGrant() {
        val fixture = platformFixture()
        val tenantId = UUID.randomUUID()
        val planId = UUID.randomUUID()
        val approvedSessionId = UUID.randomUUID()
        insertPlatformFixture(fixture)
        insertPlan(planId)
        insertTenant(tenantId, planId)
        insertActiveSupportGrant(
            platformUserId = fixture.platformUserId,
            tenantId = tenantId,
            actionCode = "platform.tenants.manage",
            supportSessionId = approvedSessionId,
        )

        val decision = requireNotNull(
            transactionTemplate.execute {
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Support(
                            platformUserId = fixture.platformUserId,
                            tenantId = tenantId,
                            supportSessionId = UUID.randomUUID(),
                            correlationId = "corr-auth-support-session-denied",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "platform_admin",
                        guardMode = GuardMode.PLATFORM_PERMISSION,
                        routeScope = RouteScope.PLATFORM,
                        permissionCode = "platform.tenants.manage",
                        tenantId = tenantId,
                    ),
                )
            },
        )

        assertFalse(decision.allowed)
        assertEquals(
            "Active approved support break-glass access is required for tenant operation",
            decision.reason,
        )
    }

    @Test
    fun allowsSupportIdentityOnlyForItsExactActiveSessionTenantAndPermission() {
        val fixture = platformFixture()
        val tenantId = UUID.randomUUID()
        val planId = UUID.randomUUID()
        val supportSessionId = UUID.randomUUID()
        insertPlatformFixture(fixture)
        insertPlan(planId)
        insertTenant(tenantId, planId)
        insertActiveSupportGrant(
            platformUserId = fixture.platformUserId,
            tenantId = tenantId,
            actionCode = "platform.tenants.manage",
            supportSessionId = supportSessionId,
        )

        val decision = requireNotNull(
            transactionTemplate.execute {
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Support(
                            platformUserId = fixture.platformUserId,
                            tenantId = tenantId,
                            supportSessionId = supportSessionId,
                            correlationId = "corr-auth-support-allowed",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "platform_admin",
                        guardMode = GuardMode.PLATFORM_PERMISSION,
                        routeScope = RouteScope.PLATFORM,
                        permissionCode = "platform.tenants.manage",
                        tenantId = tenantId,
                    ),
                )
            },
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun deniesTenantIdentityOnPlatformGuard() {
        val fixture = tenantFixture("tenant.profile.manage")

        val decision = requireNotNull(
            transactionTemplate.execute {
                insertTenantFixture(fixture)
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Tenant(
                            tenantId = fixture.tenantId,
                            tenantUserId = fixture.userId,
                            correlationId = "corr-auth-tenant-on-platform",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "platform_admin",
                        guardMode = GuardMode.PLATFORM_PERMISSION,
                        routeScope = RouteScope.PLATFORM,
                        permissionCode = "platform.tenants.manage",
                    ),
                )
            },
        )

        assertFalse(decision.allowed)
        assertEquals("Platform identity is required", decision.reason)
    }

    @Test
    fun allowsPublicTokenGuardOnlyForPublicIdentity() {
        val tenantFixture = tenantFixture("tenant.profile.manage")

        val allowed = requireNotNull(
            transactionTemplate.execute {
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Public(
                            correlationId = "corr-auth-public-token",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "tenant_admin",
                        guardMode = GuardMode.PUBLIC_TOKEN,
                        routeScope = RouteScope.PUBLIC,
                    ),
                )
            },
        )

        val denied = requireNotNull(
            transactionTemplate.execute {
                insertTenantFixture(tenantFixture)
                requestContextHolder.set(
                    requestContext(
                        RequestIdentity.Tenant(
                            tenantId = tenantFixture.tenantId,
                            tenantUserId = tenantFixture.userId,
                            correlationId = "corr-auth-tenant-public-token",
                        ),
                    ),
                )

                authorizationPort.authorize(
                    RouteAuthorizationRequest(
                        moduleId = "tenant_admin",
                        guardMode = GuardMode.PUBLIC_TOKEN,
                        routeScope = RouteScope.PUBLIC,
                    ),
                )
            },
        )

        assertTrue(allowed.allowed)
        assertFalse(denied.allowed)
        assertEquals("Public identity is required", denied.reason)
    }

    @Test
    fun throwsWhenRequiredAuthorizationIsDenied() {
        val tenantId = UUID.randomUUID()
        val otherTenantId = UUID.randomUUID()

        val error = transactionTemplate.execute {
            requestContextHolder.set(
                requestContext(
                    RequestIdentity.Tenant(
                        tenantId = tenantId,
                        tenantUserId = UUID.randomUUID(),
                        correlationId = "corr-auth-required",
                    ),
                ),
            )

            assertFailsWith<AuthorizationDeniedException> {
                authorizationPort.requireAuthorized(
                    RouteAuthorizationRequest(
                        moduleId = "tenant_admin",
                        guardMode = GuardMode.STAFF_PERMISSION,
                        routeScope = RouteScope.TENANT,
                        permissionCode = "tenant.profile.manage",
                        tenantId = otherTenantId,
                    ),
                )
            }
        }

        assertEquals("Requested tenant does not match identity", error.message)
    }

    @Test
    fun rejectsAuthorizationOutsideTransaction() {
        requestContextHolder.set(
            requestContext(
                RequestIdentity.Platform(
                    platformUserId = UUID.randomUUID(),
                    correlationId = "corr-auth-outside-transaction",
                ),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            authorizationPort.authorize(
                RouteAuthorizationRequest(
                    moduleId = "platform_admin",
                    guardMode = GuardMode.PLATFORM_PERMISSION,
                    routeScope = RouteScope.PLATFORM,
                    permissionCode = "platform.tenants.manage",
                ),
            )
        }

        assertEquals(
            "Authorization checks must run inside an active transaction",
            error.message,
        )
    }

    private fun requestContext(identity: RequestIdentity): RequestContext {
        val correlationId = identity.correlationId ?: "corr-auth"
        return RequestContext(
            identity = identity,
            correlationId = correlationId,
            idempotencyKey = null,
            httpMethod = "GET",
            requestPath = "/test",
        )
    }

    private fun tenantFixture(permissionCode: String): TenantFixture {
        return TenantFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            roleId = UUID.randomUUID(),
            permissionId = UUID.randomUUID(),
            permissionCode = permissionCode,
        )
    }

    private fun catalogWriteFixture(): CatalogWriteFixture {
        return CatalogWriteFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            propertyRoleId = UUID.randomUUID(),
            propertyPermissionId = UUID.randomUUID(),
            tenantRoleId = UUID.randomUUID(),
            tenantPermissionId = UUID.randomUUID(),
        )
    }

    private fun insertTenantFixture(fixture: TenantFixture) {
        insertPlan(fixture.planId)
        insertTenant(fixture.tenantId, fixture.planId)
        insertTenantModule(fixture.tenantId, "tenant_admin")
        insertTenantUser(fixture.tenantId, fixture.userId)
        jdbcTemplate.update(
            """
            INSERT INTO permissions (id, tenant_id, code, description)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.permissionId,
            fixture.tenantId,
            fixture.permissionCode,
            "Fixture permission ${fixture.permissionCode}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.roleId,
            fixture.tenantId,
            "Tenant Auth Role ${fixture.roleId}",
            "tenant-auth-${fixture.roleId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
            VALUES (?, ?)
            """.trimIndent(),
            fixture.roleId,
            fixture.permissionId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.userId,
            fixture.tenantId,
            fixture.roleId,
        )
    }

    private fun insertCatalogWriteFixture(
        fixture: CatalogWriteFixture,
        grantTenantCatalogPermission: Boolean,
        grantPropertyInventoryPermission: Boolean,
    ) {
        insertPlan(fixture.planId)
        insertTenant(fixture.tenantId, fixture.planId)
        insertTenantModule(fixture.tenantId, "inventory")
        insertProperty(fixture.tenantId, fixture.propertyId)
        insertTenantUser(fixture.tenantId, fixture.userId)

        if (grantPropertyInventoryPermission) {
            jdbcTemplate.update(
                """
                INSERT INTO permissions (id, tenant_id, code, description)
                VALUES (?, ?, 'inventory.manage', 'Manage property inventory')
                """.trimIndent(),
                fixture.propertyPermissionId,
                fixture.tenantId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO roles (id, tenant_id, name)
                VALUES (?, ?, ?)
                """.trimIndent(),
                fixture.propertyRoleId,
                fixture.tenantId,
                "Catalog Property Role ${fixture.propertyRoleId}",
            )
            jdbcTemplate.update(
                """
                INSERT INTO role_permissions (role_id, permission_id)
                VALUES (?, ?)
                """.trimIndent(),
                fixture.propertyRoleId,
                fixture.propertyPermissionId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                fixture.userId,
                fixture.propertyId,
                fixture.propertyRoleId,
                fixture.tenantId,
            )
        }

        if (grantTenantCatalogPermission) {
            jdbcTemplate.update(
                """
                INSERT INTO permissions (id, tenant_id, code, description)
                VALUES (?, ?, 'inventory.catalog.manage', 'Manage tenant inventory catalog')
                """.trimIndent(),
                fixture.tenantPermissionId,
                fixture.tenantId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO tenant_roles (id, tenant_id, name, code)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                fixture.tenantRoleId,
                fixture.tenantId,
                "Catalog Tenant Role ${fixture.tenantRoleId}",
                "catalog-tenant-${fixture.tenantRoleId}",
            )
            jdbcTemplate.update(
                """
                INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
                VALUES (?, ?)
                """.trimIndent(),
                fixture.tenantRoleId,
                fixture.tenantPermissionId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
                VALUES (?, ?, ?)
                """.trimIndent(),
                fixture.userId,
                fixture.tenantId,
                fixture.tenantRoleId,
            )
        }
    }

    private fun publicModuleFixture(): PublicModuleFixture {
        return PublicModuleFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
        )
    }

    private fun insertPublicModuleFixture(fixture: PublicModuleFixture) {
        insertPlan(fixture.planId)
        insertTenant(fixture.tenantId, fixture.planId)
        insertProperty(fixture.tenantId, fixture.propertyId)
        insertTenantModule(fixture.tenantId, "booking_engine")
        jdbcTemplate.update(
            """
            INSERT INTO property_modules (tenant_id, property_id, module_id, is_enabled, is_configured)
            VALUES (?, ?, 'booking_engine', true, true)
            """.trimIndent(),
            fixture.tenantId,
            fixture.propertyId,
        )
    }

    private fun platformFixture(): PlatformFixture {
        return PlatformFixture(
            platformUserId = UUID.randomUUID(),
            platformRoleId = UUID.randomUUID(),
        )
    }

    private fun insertPlatformFixture(fixture: PlatformFixture) {
        insertPlatformUser(fixture.platformUserId)
        jdbcTemplate.update(
            """
            INSERT INTO platform_roles (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.platformRoleId,
            "Platform Auth Role ${fixture.platformRoleId}",
            "platform-auth-${fixture.platformRoleId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
            SELECT ?, id
            FROM platform_permissions
            WHERE code = 'platform.tenants.manage'
            """.trimIndent(),
            fixture.platformRoleId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_user_roles (platform_user_id, platform_role_id)
            VALUES (?, ?)
            """.trimIndent(),
            fixture.platformUserId,
            fixture.platformRoleId,
        )
    }

    private fun insertPlatformUser(id: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, full_name, email, status)
            VALUES (?, ?, ?, 'active')
            """.trimIndent(),
            id,
            "Platform User $id",
            "platform-auth-$id@example.com",
        )
    }

    private fun insertActiveSupportGrant(
        platformUserId: UUID,
        tenantId: UUID,
        actionCode: String,
        supportSessionId: UUID,
    ) {
        val approverId = UUID.randomUUID()
        insertPlatformUser(approverId)
        jdbcTemplate.update(
            """
            INSERT INTO platform_break_glass_access (
                id,
                platform_user_id,
                tenant_id,
                action_code,
                reason,
                status,
                approved_by,
                approved_at,
                activated_at,
                starts_at,
                expires_at
            ) VALUES (
                ?, ?, ?, ?, 'Authorization integration test', 'active',
                ?, now(), now(), now() - interval '1 minute',
                now() + interval '1 hour'
            )
            """.trimIndent(),
            supportSessionId,
            platformUserId,
            tenantId,
            actionCode,
            approverId,
        )
    }

    private fun insertPlan(id: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO plans (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            id,
            "Plan $id",
            "plan-$id",
        )
    }

    private fun insertTenant(id: UUID, planId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO tenants (
                id,
                name,
                slug,
                schema_name,
                plan_id
            )
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            "Tenant $id",
            "tenant-$id",
            "tenant_$id".replace("-", "_"),
            planId,
        )
    }

    private fun insertTenantModule(tenantId: UUID, moduleId: String) {
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
            VALUES (?, ?, true, true)
            """.trimIndent(),
            tenantId,
            moduleId,
        )
    }

    private fun insertTenantUser(tenantId: UUID, userId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status)
            VALUES (?, ?, ?, ?, 'active')
            """.trimIndent(),
            userId,
            tenantId,
            "Tenant User $userId",
            "tenant-auth-$userId@example.com",
        )
    }

    private fun insertProperty(tenantId: UUID, propertyId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, code, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            propertyId,
            tenantId,
            "Property $propertyId",
            "P${propertyId.toString().take(8)}",
        )
    }

    private data class TenantFixture(
        val planId: UUID,
        val tenantId: UUID,
        val userId: UUID,
        val roleId: UUID,
        val permissionId: UUID,
        val permissionCode: String,
    )

    private data class CatalogWriteFixture(
        val planId: UUID,
        val tenantId: UUID,
        val propertyId: UUID,
        val userId: UUID,
        val propertyRoleId: UUID,
        val propertyPermissionId: UUID,
        val tenantRoleId: UUID,
        val tenantPermissionId: UUID,
    )

    private data class PublicModuleFixture(
        val planId: UUID,
        val tenantId: UUID,
        val propertyId: UUID,
    )

    private data class PlatformFixture(
        val platformUserId: UUID,
        val platformRoleId: UUID,
    )
}
