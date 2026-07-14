package com.mwombeki.peak.usermanagement.internal.web

import com.jayway.jsonpath.JsonPath
import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.hamcrest.Matchers.hasItem
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PlatformAdministrationControllerIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun managesPlatformUsersRolesAndIdentityLinksThroughSecuredRoutes() {
        val actorId = insertPlatformSecurityActor()

        val createUserResponse = mockMvc.perform(
            post("/api/v1/platform/users")
                .platform(actorId, "corr-platform-user-create", "idem-platform-user-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "fullName": "Security Operator",
                      "email": "security.operator@example.com",
                      "status": "active"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val platformUserId = UUID.fromString(
            JsonPath.read(createUserResponse.response.contentAsString, "$.platformUserId"),
        )

        mockMvc.perform(
            post("/api/v1/platform/users")
                .platform(actorId, "corr-platform-user-create", "idem-platform-user-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "fullName": "Security Operator",
                      "email": "security.operator@example.com",
                      "status": "active"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.platformUserId").value(platformUserId.toString()))
            .andExpect(jsonPath("$.replayed").value(true))

        mockMvc.perform(
            get("/api/v1/platform/users")
                .platform(actorId, "corr-platform-users-list"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[*].email", hasItem("security.operator@example.com")))

        val roleResponse = mockMvc.perform(
            post("/api/v1/platform/roles")
                .platform(actorId, "corr-platform-role-create", "idem-platform-role-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "code": "incident_manager",
                      "name": "Incident Manager",
                      "description": "Can view audit and monitoring data",
                      "permissionCodes": ["platform.audit.view", "platform.monitoring.view"]
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.changed").value(true))
            .andReturn()

        val platformRoleId = UUID.fromString(
            JsonPath.read(roleResponse.response.contentAsString, "$.platformRoleId"),
        )

        mockMvc.perform(
            post("/api/v1/platform/users/$platformUserId/roles/$platformRoleId/assign")
                .platform(actorId, "corr-platform-role-assign", "idem-platform-role-assign"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.assigned").value(true))
            .andExpect(jsonPath("$.changed").value(true))

        val identityResponse = mockMvc.perform(
            post("/api/v1/platform/users/$platformUserId/identity-links")
                .platform(actorId, "corr-platform-identity-link", "idem-platform-identity-link")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "issuer": "https://keycloak.example.com/realms/peak",
                      "subject": "operator-subject",
                      "email": "security.operator@example.com"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.changed").value(true))
            .andReturn()

        val identityLinkId = UUID.fromString(
            JsonPath.read(identityResponse.response.contentAsString, "$.identityLinkId"),
        )

        mockMvc.perform(
            post("/api/v1/platform/users/$platformUserId/identity-links/$identityLinkId/revoke")
                .platform(actorId, "corr-platform-identity-revoke", "idem-platform-identity-revoke"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.revokedAt").exists())

        mockMvc.perform(
            post("/api/v1/platform/users/$platformUserId/disable")
                .platform(actorId, "corr-platform-user-disable", "idem-platform-user-disable"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("disabled"))
            .andExpect(jsonPath("$.changed").value(true))

        assertPlatformAuditAndOutboxWereRecorded(platformUserId)
    }

    @Test
    fun deniesPlatformAdministrationRouteWithoutPlatformSecurityPermission() {
        val platformUserId = insertPlatformUser()

        mockMvc.perform(
            get("/api/v1/platform/users")
                .platform(platformUserId, "corr-platform-denied"),
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun rejectsSelfLifecycleChange() {
        val actorId = insertPlatformSecurityActor()

        mockMvc.perform(
            post("/api/v1/platform/users/$actorId/disable")
                .platform(actorId, "corr-platform-self-disable", "idem-platform-self-disable"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("Platform operator cannot change own lifecycle state"))
    }

    @Test
    fun assignsListsAndRevokesPlatformAdministratorThroughDedicatedRoutes() {
        val rootRoleId = resetSystemPlatformRootRoleAssignments()
        val actorId = insertPlatformActorWithPermissions(
            "platform.administrators.manage",
            "platform.roles.view",
        )
        insertPlatformUserRole(actorId, rootRoleId, actorId)
        insertPlatformIdentityLink(actorId)
        val targetUserId = insertPlatformUser()
        insertPlatformIdentityLink(targetUserId)

        mockMvc.perform(
            post("/api/v1/platform/administrators/$targetUserId/assign")
                .platform(actorId, "corr-platform-admin-assign", "idem-platform-admin-assign"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.platformRoleId").value(rootRoleId.toString()))
            .andExpect(jsonPath("$.assigned").value(true))
            .andExpect(jsonPath("$.changed").value(true))

        mockMvc.perform(
            post("/api/v1/platform/administrators/$targetUserId/assign")
                .platform(actorId, "corr-platform-admin-assign", "idem-platform-admin-assign"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.replayed").value(true))

        mockMvc.perform(
            get("/api/v1/platform/administrators")
                .platform(actorId, "corr-platform-admin-list"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[*].platformUserId", hasItem(targetUserId.toString())))
            .andExpect(jsonPath("$[*].effective", hasItem(true)))

        mockMvc.perform(
            post("/api/v1/platform/administrators/$targetUserId/revoke")
                .platform(actorId, "corr-platform-admin-revoke", "idem-platform-admin-revoke"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.assigned").value(false))
            .andExpect(jsonPath("$.changed").value(true))

        check(platformUserRoleAssignmentCount(targetUserId, rootRoleId) == 0)
        assertPlatformAdministratorAuditAndOutboxWereRecorded(targetUserId)
    }

    @Test
    fun rejectsRemovingLastEffectivePlatformAdministratorThroughEveryAccessPath() {
        val rootRoleId = resetSystemPlatformRootRoleAssignments()
        val actorId = insertPlatformActorWithPermissions(
            "platform.admin.all",
            "platform.administrators.manage",
            "platform.users.manage",
            "platform.identity_links.manage",
        )
        val targetUserId = insertPlatformUser()
        insertPlatformUserRole(targetUserId, rootRoleId, actorId)
        val identityLinkId = insertPlatformIdentityLink(targetUserId)
        val expectedDetail =
            "Platform administrator access cannot be removed without another effective administrator"

        mockMvc.perform(
            post("/api/v1/platform/administrators/$targetUserId/revoke")
                .platform(actorId, "corr-last-platform-admin-revoke", "idem-last-platform-admin-revoke"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(expectedDetail))

        mockMvc.perform(
            post("/api/v1/platform/users/$targetUserId/disable")
                .platform(actorId, "corr-last-platform-admin-disable", "idem-last-platform-admin-disable"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(expectedDetail))

        mockMvc.perform(
            post("/api/v1/platform/users/$targetUserId/identity-links/$identityLinkId/revoke")
                .platform(
                    actorId,
                    "corr-last-platform-admin-identity",
                    "idem-last-platform-admin-identity",
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(expectedDetail))

        check(platformUserStatus(targetUserId) == "active")
        check(platformUserRoleAssignmentCount(targetUserId, rootRoleId) == 1)
        check(identityLinkRevokedAt(identityLinkId) == null)
    }

    @Test
    fun rejectsGenericSystemRoleAssignmentAndReservedDynamicRootIdentifiers() {
        val rootRoleId = resetSystemPlatformRootRoleAssignments()
        val actorId = insertPlatformActorWithPermissions(
            "platform.roles.manage",
            "platform.users.manage",
        )
        val targetUserId = insertPlatformUser()

        mockMvc.perform(
            post("/api/v1/platform/users/$targetUserId/roles/$rootRoleId/assign")
                .platform(actorId, "corr-generic-platform-root", "idem-generic-platform-root"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.detail")
                    .value("System platform role assignments require dedicated administrator routes"),
            )

        mockMvc.perform(
            post("/api/v1/platform/roles")
                .platform(actorId, "corr-reserved-platform-root-code", "idem-reserved-platform-root-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "code": "platform_root",
                      "name": "Different Name",
                      "permissionCodes": ["platform.roles.manage"]
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.detail")
                    .value("platform_root is reserved for the system Platform Root role"),
            )

        mockMvc.perform(
            post("/api/v1/platform/roles")
                .platform(actorId, "corr-reserved-platform-root-name", "idem-reserved-platform-root-name")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "code": "different_code",
                      "name": "Platform Root",
                      "permissionCodes": ["platform.roles.manage"]
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.detail")
                    .value("Platform Root is reserved for the system platform role"),
            )
    }

    @Test
    fun serializesConcurrentPlatformAdministratorRevocations() {
        val rootRoleId = resetSystemPlatformRootRoleAssignments()
        val actorId = insertPlatformActorWithPermissions("platform.administrators.manage")
        val firstRootId = insertPlatformUser()
        val secondRootId = insertPlatformUser()
        insertPlatformUserRole(firstRootId, rootRoleId, actorId)
        insertPlatformUserRole(secondRootId, rootRoleId, actorId)
        insertPlatformIdentityLink(firstRootId)
        insertPlatformIdentityLink(secondRootId)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = listOf(firstRootId, secondRootId).map { targetUserId ->
                executor.submit<Int> {
                    start.await(10, TimeUnit.SECONDS)
                    mockMvc.perform(
                        post("/api/v1/platform/administrators/$targetUserId/revoke")
                            .platform(
                                actorId,
                                "corr-concurrent-root-$targetUserId",
                                "idem-concurrent-root-$targetUserId",
                            ),
                    ).andReturn().response.status
                }
            }
            start.countDown()
            val statuses = futures.map { it.get(30, TimeUnit.SECONDS) }.sorted()

            assertEquals(listOf(200, 400), statuses)
            assertEquals(
                1,
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM platform_user_roles WHERE platform_role_id = ?",
                    Int::class.java,
                    rootRoleId,
                ),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun deniesPlatformAdministratorMutationWithoutDedicatedPermission() {
        val rootRoleId = resetSystemPlatformRootRoleAssignments()
        val actorId = insertPlatformActorWithPermissions("platform.roles.manage")
        val targetUserId = insertPlatformUser()
        insertPlatformUserRole(targetUserId, rootRoleId, actorId)
        insertPlatformIdentityLink(targetUserId)

        mockMvc.perform(
            post("/api/v1/platform/administrators/$targetUserId/revoke")
                .platform(actorId, "corr-platform-admin-denied", "idem-platform-admin-denied"),
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun rejectsUpdatingPlatformUserWithPermissionActorDoesNotHold() {
        val actorId = insertPlatformActorWithPermissions("platform.security.manage")
        val targetUserId = insertPlatformActorWithPermissions("platform.audit.view")

        mockMvc.perform(
            put("/api/v1/platform/users/$targetUserId")
                .platform(actorId, "corr-platform-user-update-hierarchy", "idem-platform-user-update-hierarchy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "fullName": "Renamed Platform Auditor"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.detail")
                    .value("Platform operator cannot manage a user with permissions the actor does not hold"),
            )

        check(platformUserFullName(targetUserId) == "Platform User")
    }

    @Test
    fun rejectsLifecycleChangeAgainstPlatformUserWithPermissionActorDoesNotHold() {
        val actorId = insertPlatformActorWithPermissions("platform.security.manage")
        val targetUserId = insertPlatformActorWithPermissions("platform.audit.view")

        mockMvc.perform(
            post("/api/v1/platform/users/$targetUserId/disable")
                .platform(actorId, "corr-platform-user-disable-hierarchy", "idem-platform-user-disable-hierarchy"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.detail")
                    .value("Platform operator cannot manage a user with permissions the actor does not hold"),
            )

        check(platformUserStatus(targetUserId) == "active")
    }

    @Test
    fun platformAdminAllCanLifecycleHigherPermissionPlatformUser() {
        val actorId = insertPlatformActorWithPermissions("platform.admin.all")
        val targetUserId = insertPlatformActorWithPermissions("platform.audit.view")

        mockMvc.perform(
            post("/api/v1/platform/users/$targetUserId/disable")
                .platform(actorId, "corr-platform-user-disable-admin-all", "idem-platform-user-disable-admin-all"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("disabled"))
            .andExpect(jsonPath("$.changed").value(true))
    }

    @Test
    fun rejectsIdentityLinkCreationAgainstPlatformUserWithPermissionActorDoesNotHold() {
        val actorId = insertPlatformActorWithPermissions("platform.security.manage")
        val targetUserId = insertPlatformActorWithPermissions("platform.audit.view")

        mockMvc.perform(
            post("/api/v1/platform/users/$targetUserId/identity-links")
                .platform(actorId, "corr-platform-identity-link-hierarchy", "idem-platform-identity-link-hierarchy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "issuer": "https://keycloak.example.com/realms/peak",
                      "subject": "auditor-${UUID.randomUUID()}",
                      "email": "platform-auditor@example.com"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.detail")
                    .value("Platform operator cannot manage a user with permissions the actor does not hold"),
            )

        check(activePlatformIdentityLinkCount(targetUserId) == 0)
    }

    @Test
    fun rejectsIdentityLinkRevocationAgainstPlatformUserWithPermissionActorDoesNotHold() {
        val actorId = insertPlatformActorWithPermissions("platform.security.manage")
        val targetUserId = insertPlatformActorWithPermissions("platform.audit.view")
        val identityLinkId = insertPlatformIdentityLink(targetUserId)

        mockMvc.perform(
            post("/api/v1/platform/users/$targetUserId/identity-links/$identityLinkId/revoke")
                .platform(actorId, "corr-platform-identity-revoke-hierarchy", "idem-platform-identity-revoke-hierarchy"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.detail")
                    .value("Platform operator cannot manage a user with permissions the actor does not hold"),
            )

        check(identityLinkRevokedAt(identityLinkId) == null)
    }

    @Test
    fun rejectsRoleAssignmentAgainstPlatformUserWithPermissionActorDoesNotHold() {
        val actorId = insertPlatformActorWithPermissions("platform.security.manage")
        val targetUserId = insertPlatformActorWithPermissions("platform.audit.view")
        val roleId = insertPlatformRoleWithPermissions("platform.security.manage")

        mockMvc.perform(
            post("/api/v1/platform/users/$targetUserId/roles/$roleId/assign")
                .platform(actorId, "corr-platform-role-assign-target-hierarchy", "idem-platform-role-assign-target-hierarchy"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.detail")
                    .value("Platform operator cannot manage a user with permissions the actor does not hold"),
            )

        check(platformUserRoleAssignmentCount(targetUserId, roleId) == 0)
    }

    @Test
    fun rejectsRoleRevocationAgainstPlatformUserWithPermissionActorDoesNotHold() {
        val actorId = insertPlatformActorWithPermissions("platform.security.manage")
        val targetUserId = insertPlatformActorWithPermissions("platform.audit.view")
        val roleId = insertPlatformRoleWithPermissions("platform.security.manage")
        insertPlatformUserRole(targetUserId, roleId, actorId)

        mockMvc.perform(
            post("/api/v1/platform/users/$targetUserId/roles/$roleId/revoke")
                .platform(actorId, "corr-platform-role-revoke-target-hierarchy", "idem-platform-role-revoke-target-hierarchy"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.detail")
                    .value("Platform operator cannot manage a user with permissions the actor does not hold"),
            )

        check(platformUserRoleAssignmentCount(targetUserId, roleId) == 1)
    }

    @Test
    fun rejectsSelfIdentityLinkManagement() {
        val actorId = insertPlatformSecurityActor()
        val identityLinkId = insertPlatformIdentityLink(actorId)

        mockMvc.perform(
            post("/api/v1/platform/users/$actorId/identity-links")
                .platform(actorId, "corr-platform-self-identity-link", "idem-platform-self-identity-link")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "issuer": "https://keycloak.example.com/realms/peak",
                      "subject": "self-link-${UUID.randomUUID()}"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("Platform operator cannot link own identity"))

        mockMvc.perform(
            post("/api/v1/platform/users/$actorId/identity-links/$identityLinkId/revoke")
                .platform(actorId, "corr-platform-self-identity-revoke", "idem-platform-self-identity-revoke"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("Platform operator cannot revoke own identity link"))

        check(identityLinkRevokedAt(identityLinkId) == null)
    }

    @Test
    fun rejectsPlatformRolePermissionEscalation() {
        val actorId = insertPlatformActorWithPermissions("platform.security.manage")

        mockMvc.perform(
            post("/api/v1/platform/roles")
                .platform(actorId, "corr-platform-role-escalation", "idem-platform-role-escalation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "code": "unauthorized_auditor",
                      "name": "Unauthorized Auditor",
                      "permissionCodes": ["platform.audit.view"]
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.detail")
                    .value("Platform roles cannot include permissions the actor does not hold"),
            )
    }

    @Test
    fun rejectsUpdatingPlatformRoleWithCurrentPermissionActorDoesNotHold() {
        val actorId = insertPlatformActorWithPermissions("platform.security.manage")
        val roleId = insertPlatformRoleWithPermissions("platform.audit.view")

        mockMvc.perform(
            put("/api/v1/platform/roles/$roleId")
                .platform(actorId, "corr-platform-role-update-escalation", "idem-platform-role-update-escalation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Renamed Audit Role"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.detail")
                    .value("Platform roles cannot include permissions the actor does not hold"),
            )

        check(platformRoleName(roleId)?.startsWith("Escalated Platform Role") == true)
    }

    @Test
    fun rejectsDeactivatingPlatformRoleWithCurrentPermissionActorDoesNotHold() {
        val actorId = insertPlatformActorWithPermissions("platform.security.manage")
        val roleId = insertPlatformRoleWithPermissions("platform.audit.view")

        mockMvc.perform(
            delete("/api/v1/platform/roles/$roleId")
                .platform(actorId, "corr-platform-role-deactivate-escalation", "idem-platform-role-deactivate-escalation"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.detail")
                    .value("Platform roles cannot include permissions the actor does not hold"),
            )

        check(platformRoleActive(roleId))
    }

    @Test
    fun rejectsRevokingPlatformRoleWithPermissionActorDoesNotHold() {
        val actorId = insertPlatformActorWithPermissions("platform.security.manage")
        val targetUserId = insertPlatformUser()
        val roleId = insertPlatformRoleWithPermissions("platform.audit.view")
        insertPlatformUserRole(targetUserId, roleId, actorId)

        mockMvc.perform(
            post("/api/v1/platform/users/$targetUserId/roles/$roleId/revoke")
                .platform(actorId, "corr-platform-role-revoke-escalation", "idem-platform-role-revoke-escalation"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.detail")
                    .value("Platform roles cannot include permissions the actor does not hold"),
            )

        check(platformUserRoleAssignmentCount(targetUserId, roleId) == 1)
    }

    @Test
    fun provisionsInitialAndRecoveryTenantAdministratorsWithoutManualTenantSql() {
        val actorId = insertPlatformActorWithPermissions(
            "platform.security.manage",
            "platform.tenants.manage",
            "platform.tenants.verify",
        )
        val tenantId = insertTenantForProvisioning()
        val subject = "tenant-admin-${UUID.randomUUID()}"
        val requestBody = """
            {
              "fullName": "Initial Tenant Administrator",
              "email": "initial-admin-$tenantId@example.com",
              "issuer": "https://keycloak.example.com/realms/peak",
              "subject": "$subject"
            }
        """.trimIndent()

        val provisionResult = mockMvc.perform(
            post("/api/v1/platform/tenants/$tenantId/administrators")
                .platform(actorId, "corr-tenant-admin-provision", "idem-tenant-admin-provision")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val tenantUserId = UUID.fromString(
            JsonPath.read(provisionResult.response.contentAsString, "$.tenantUserId"),
        )

        mockMvc.perform(
            post("/api/v1/platform/tenants/$tenantId/administrators")
                .platform(actorId, "corr-tenant-admin-provision-replay", "idem-tenant-admin-provision")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.tenantUserId").value(tenantUserId.toString()))
            .andExpect(jsonPath("$.replayed").value(true))

        val recoverySubject = "tenant-recovery-admin-${UUID.randomUUID()}"
        val recoveryResult = mockMvc.perform(
            post("/api/v1/platform/tenants/$tenantId/administrators")
                .platform(
                    actorId,
                    "corr-tenant-admin-recovery",
                    "idem-tenant-admin-recovery",
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "fullName": "Recovery Tenant Administrator",
                      "email": "recovery-admin-$tenantId@example.com",
                      "issuer": "https://keycloak.example.com/realms/peak",
                      "subject": "$recoverySubject"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()
        val recoveryTenantUserId = UUID.fromString(
            JsonPath.read(recoveryResult.response.contentAsString, "$.tenantUserId"),
        )
        check(recoveryTenantUserId != tenantUserId)
        val tenantAdministratorCount = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM user_tenant_roles utr
            JOIN tenant_roles tr
              ON tr.id = utr.tenant_role_id
             AND tr.tenant_id = utr.tenant_id
            WHERE utr.tenant_id = ?
              AND tr.code = 'tenant_admin'
              AND tr.is_system = true
            """.trimIndent(),
            Int::class.java,
            tenantId,
        )
        check(tenantAdministratorCount == 2)

        val provisionedPermissionCount = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM tenant_role_permissions trp
            JOIN tenant_roles tr ON tr.id = trp.tenant_role_id
            JOIN permissions p ON p.id = trp.permission_id
            WHERE tr.tenant_id = ?
              AND tr.code = 'tenant_admin'
              AND tr.is_system = true
              AND p.tenant_id = ?
            """.trimIndent(),
            Int::class.java,
            tenantId,
            tenantId,
        )
        val catalogPermissionCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM permission_catalog WHERE is_tenant_permission = true",
            Int::class.java,
        )
        check(provisionedPermissionCount == catalogPermissionCount) {
            "Tenant administrator must receive every immutable tenant permission"
        }

        mockMvc.perform(
            post("/api/v1/platform/tenants/$tenantId/profile/verify")
                .platform(actorId, "corr-tenant-profile-verify", "idem-tenant-profile-verify"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
            .andExpect(jsonPath("$.verificationStatus").value("verified"))
            .andExpect(jsonPath("$.changed").value(true))

        val verificationStatus = jdbcTemplate.queryForObject(
            "SELECT verification_status FROM tenant_profiles WHERE tenant_id = ?",
            String::class.java,
            tenantId,
        )
        check(verificationStatus == "verified")
    }

    @Test
    fun rejectsSupportSessionForDifferentTenantEvenWithBreakGlassGrant() {
        val actorId = insertPlatformActorWithPermissions("platform.security.manage")
        val supportTenantId = insertTenantForProvisioning()
        val targetTenantId = insertTenantForProvisioning()
        insertActiveBreakGlassGrant(
            platformUserId = actorId,
            tenantId = targetTenantId,
            actionCode = "platform.security.manage",
        )
        val subject = "tenant-admin-${UUID.randomUUID()}"

        mockMvc.perform(
            post("/api/v1/platform/tenants/$targetTenantId/administrators")
                .support(
                    platformUserId = actorId,
                    supportTenantId = supportTenantId,
                    supportSessionId = UUID.randomUUID(),
                    correlationId = "corr-support-tenant-mismatch",
                    idempotencyKey = "idem-support-tenant-mismatch",
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "fullName": "Wrong Tenant Support Admin",
                      "email": "wrong-support-$targetTenantId@example.com",
                      "issuer": "https://keycloak.example.com/realms/peak",
                      "subject": "$subject"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value("Support session tenant does not match target tenant"))

        val auditOutcome = jdbcTemplate.queryForObject(
            """
            SELECT outcome
            FROM platform_audit_logs
            WHERE action = 'platform.support.break_glass.access'
              AND tenant_id = ?
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
            String::class.java,
            targetTenantId,
        )
        check(auditOutcome == "denied") {
            "Expected denied support break-glass audit outcome for tenant mismatch"
        }
    }

    private fun insertPlatformSecurityActor(): UUID {
        return insertPlatformActorWithPermissions(
            "platform.security.manage",
            "platform.audit.view",
            "platform.monitoring.view",
        )
    }

    private fun insertPlatformUser(): UUID {
        val platformUserId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, full_name, email, status)
            VALUES (?, ?, ?, 'active')
            """.trimIndent(),
            platformUserId,
            "Platform User",
            "platform-${platformUserId.toString().take(8)}@example.com",
        )
        return platformUserId
    }

    private fun insertPlatformActorWithPermissions(vararg permissionCodes: String): UUID {
        val platformUserId = insertPlatformUser()
        val roleId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO platform_roles (id, code, name, is_system, is_active)
            VALUES (?, ?, ?, false, true)
            """.trimIndent(),
            roleId,
            "tenant-bootstrap-${roleId.toString().take(8)}",
            "Tenant Bootstrap Operator",
        )
        permissionCodes.forEach { permissionCode ->
            val permissionId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform_permissions WHERE code = ?",
                UUID::class.java,
                permissionCode,
            )
            jdbcTemplate.update(
                """
                INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
                VALUES (?, ?)
                """.trimIndent(),
                roleId,
                permissionId,
            )
        }
        jdbcTemplate.update(
            """
            INSERT INTO platform_user_roles (platform_user_id, platform_role_id, assigned_by)
            VALUES (?, ?, ?)
            """.trimIndent(),
            platformUserId,
            roleId,
            platformUserId,
        )
        return platformUserId
    }

    private fun insertPlatformRoleWithPermissions(vararg permissionCodes: String): UUID {
        val roleId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO platform_roles (id, code, name, is_system, is_active)
            VALUES (?, ?, ?, false, true)
            """.trimIndent(),
            roleId,
            "escalated-${roleId.toString().take(8)}",
            "Escalated Platform Role $roleId",
        )
        permissionCodes.forEach { permissionCode ->
            val permissionId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform_permissions WHERE code = ?",
                UUID::class.java,
                permissionCode,
            )
            jdbcTemplate.update(
                """
                INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
                VALUES (?, ?)
                """.trimIndent(),
                roleId,
                permissionId,
            )
        }
        return roleId
    }

    private fun resetSystemPlatformRootRoleAssignments(): UUID {
        val existingRoleId = jdbcTemplate.query(
            """
            SELECT id
            FROM platform_roles
            WHERE code = 'platform_root'
              AND is_system = true
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
        ).singleOrNull()
        val roleId = existingRoleId ?: UUID.randomUUID().also { newRoleId ->
            jdbcTemplate.update(
                """
                INSERT INTO platform_roles (id, code, name, is_system, is_active)
                VALUES (?, 'platform_root', 'Platform Root', true, true)
                """.trimIndent(),
                newRoleId,
            )
        }
        jdbcTemplate.update(
            "UPDATE platform_roles SET is_active = true WHERE id = ?",
            roleId,
        )
        jdbcTemplate.update(
            "DELETE FROM platform_user_roles WHERE platform_role_id = ?",
            roleId,
        )
        return roleId
    }

    private fun insertPlatformUserRole(platformUserId: UUID, platformRoleId: UUID, assignedBy: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO platform_user_roles (platform_user_id, platform_role_id, assigned_by)
            VALUES (?, ?, ?)
            """.trimIndent(),
            platformUserId,
            platformRoleId,
            assignedBy,
        )
    }

    private fun platformRoleName(platformRoleId: UUID): String? {
        return jdbcTemplate.queryForObject(
            "SELECT name FROM platform_roles WHERE id = ?",
            String::class.java,
            platformRoleId,
        )
    }

    private fun platformRoleActive(platformRoleId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            "SELECT is_active FROM platform_roles WHERE id = ?",
            Boolean::class.java,
            platformRoleId,
        ) == true
    }

    private fun platformUserFullName(platformUserId: UUID): String? {
        return jdbcTemplate.queryForObject(
            "SELECT full_name FROM platform_users WHERE id = ?",
            String::class.java,
            platformUserId,
        )
    }

    private fun platformUserStatus(platformUserId: UUID): String? {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM platform_users WHERE id = ?",
            String::class.java,
            platformUserId,
        )
    }

    private fun platformUserRoleAssignmentCount(platformUserId: UUID, platformRoleId: UUID): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM platform_user_roles
                WHERE platform_user_id = ?
                  AND platform_role_id = ?
                """.trimIndent(),
                Int::class.java,
                platformUserId,
                platformRoleId,
            ),
        )
    }

    private fun insertPlatformIdentityLink(platformUserId: UUID): UUID {
        val identityLinkId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO identity_links (
                id,
                identity_mode,
                provider,
                issuer,
                subject,
                platform_user_id,
                email
            )
            VALUES (?, 'platform', 'oidc', ?, ?, ?, ?)
            """.trimIndent(),
            identityLinkId,
            "https://keycloak.example.com/realms/peak",
            "platform-subject-${identityLinkId.toString().take(8)}",
            platformUserId,
            "platform-link-${identityLinkId.toString().take(8)}@example.com",
        )
        return identityLinkId
    }

    private fun activePlatformIdentityLinkCount(platformUserId: UUID): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM identity_links
                WHERE identity_mode = 'platform'
                  AND platform_user_id = ?
                  AND revoked_at IS NULL
                """.trimIndent(),
                Int::class.java,
                platformUserId,
            ),
        )
    }

    private fun identityLinkRevokedAt(identityLinkId: UUID): java.sql.Timestamp? {
        return jdbcTemplate.queryForObject(
            "SELECT revoked_at FROM identity_links WHERE id = ?",
            java.sql.Timestamp::class.java,
            identityLinkId,
        )
    }

    private fun insertTenantForProvisioning(): UUID {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId,
            "Tenant Provisioning Plan $planId",
            "tenant-provisioning-$planId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, schema_name, plan_id, status)
            VALUES (?, ?, ?, ?, ?, 'trial')
            """.trimIndent(),
            tenantId,
            "Provisioned Tenant $tenantId",
            "provisioned-$tenantId",
            "tenant_${tenantId.toString().replace("-", "")}",
            planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_profiles (
                tenant_id,
                legal_name,
                entity_type,
                business_phone,
                business_email
            )
            VALUES (?, ?, 'limited_company', '+255712345678', ?)
            """.trimIndent(),
            tenantId,
            "Provisioned Tenant Limited",
            "business-$tenantId@example.com",
        )
        return tenantId
    }

    private fun insertActiveBreakGlassGrant(
        platformUserId: UUID,
        tenantId: UUID,
        actionCode: String,
    ) {
        val approverId = insertPlatformUser()
        jdbcTemplate.update(
            """
            INSERT INTO platform_break_glass_access (
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
            )
            VALUES (?, ?, ?, 'Regression test support access', 'active', ?, now(), now(), now() - interval '1 minute', now() + interval '1 hour')
            """.trimIndent(),
            platformUserId,
            tenantId,
            actionCode,
            approverId,
        )
    }

    private fun assertPlatformAuditAndOutboxWereRecorded(platformUserId: UUID) {
        val auditCount = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM platform_audit_logs
            WHERE entity_id = ?
              AND action LIKE 'platform.users.%'
            """.trimIndent(),
            Int::class.java,
            platformUserId,
        )
        val outboxCount = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM outbox_events
            WHERE aggregate_id = ?
              AND event_type LIKE 'platform.users.%'
            """.trimIndent(),
            Int::class.java,
            platformUserId,
        )
        check((auditCount ?: 0) > 0) {
            "Expected platform audit events for platform user changes"
        }
        check((outboxCount ?: 0) > 0) {
            "Expected platform outbox events for platform user changes"
        }
    }

    private fun assertPlatformAdministratorAuditAndOutboxWereRecorded(platformUserId: UUID) {
        val auditCount = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM platform_audit_logs
            WHERE entity_id = ?
              AND action IN ('platform.administrator.assigned', 'platform.administrator.revoked')
            """.trimIndent(),
            Int::class.java,
            platformUserId,
        )
        val outboxCount = jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM outbox_events
            WHERE aggregate_id = ?
              AND event_type IN ('platform.administrator.assigned', 'platform.administrator.revoked')
            """.trimIndent(),
            Int::class.java,
            platformUserId,
        )
        check((auditCount ?: 0) == 2) {
            "Expected platform administrator assignment and revocation audit events"
        }
        check((outboxCount ?: 0) == 2) {
            "Expected platform administrator assignment and revocation outbox events"
        }
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.platform(
        platformUserId: UUID,
        correlationId: String,
        idempotencyKey: String? = null,
    ): org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder {
        header(PeakRequestHeaders.PLATFORM_USER_ID, platformUserId.toString())
        header(PeakRequestHeaders.CORRELATION_ID, correlationId)
        idempotencyKey?.let { header(PeakRequestHeaders.IDEMPOTENCY_KEY, it) }
        return this
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.support(
        platformUserId: UUID,
        supportTenantId: UUID,
        supportSessionId: UUID,
        correlationId: String,
        idempotencyKey: String? = null,
    ): org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder {
        header(PeakRequestHeaders.PLATFORM_USER_ID, platformUserId.toString())
        header(PeakRequestHeaders.SUPPORT_TENANT_ID, supportTenantId.toString())
        header(PeakRequestHeaders.SUPPORT_SESSION_ID, supportSessionId.toString())
        header(PeakRequestHeaders.CORRELATION_ID, correlationId)
        idempotencyKey?.let { header(PeakRequestHeaders.IDEMPOTENCY_KEY, it) }
        return this
    }
}
