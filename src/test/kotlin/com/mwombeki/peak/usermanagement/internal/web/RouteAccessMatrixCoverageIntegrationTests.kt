package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.GuardMode
import com.mwombeki.peak.usermanagement.api.RouteScope
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RouteAccessMatrixCoverageIntegrationTests {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private lateinit var handlerMapping: RequestMappingHandlerMapping

    @Autowired
    private lateinit var ruleRepository: RouteAccessRuleRepository

    @Autowired
    private lateinit var routeAccessMatcher: RouteAccessMatcher

    @Test
    fun everyApplicationApiRouteHasAnExplicitAccessMatrixContract() {
        val rules = ruleRepository.findEnabledRules()
        val violations = applicationApiRouteSamples().mapNotNull { sample ->
            val authorizationRequest = routeAccessMatcher.match(
                httpMethod = sample.httpMethod.name,
                requestPath = sample.samplePath,
                identity = RequestIdentity.Public(),
                rules = rules,
            )

            when {
                authorizationRequest == null -> {
                    "${sample.httpMethod} ${sample.pattern} is not registered in module_access_matrix"
                }

                authorizationRequest.guardMode != sample.expectedGuardMode -> {
                    "${sample.httpMethod} ${sample.pattern} resolved to guard " +
                            "${authorizationRequest.guardMode}, expected ${sample.expectedGuardMode}"
                }

                authorizationRequest.routeScope != sample.expectedRouteScope -> {
                    "${sample.httpMethod} ${sample.pattern} resolved to scope " +
                            "${authorizationRequest.routeScope}, expected ${sample.expectedRouteScope}"
                }

                else -> null
            }
        }

        assertTrue(
            violations.isEmpty(),
            violations.joinToString(
                separator = "\n",
                prefix = "API routes without correct access contracts:\n",
            ),
        )
    }

    private fun applicationApiRouteSamples(): List<RouteSample> {
        return handlerMapping.handlerMethods.flatMap { (mappingInfo, handlerMethod) ->
            if (!handlerMethod.beanType.name.startsWith("com.mwombeki.peak.")) {
                return@flatMap emptyList()
            }

            val patterns = mappingInfo.pathPatternsCondition
                ?.patterns
                ?.map { it.patternString }
                ?: emptyList()

            patterns
                .filter { it.startsWith("/api/") }
                .flatMap { pattern ->
                    val methods = mappingInfo.methodsCondition.methods
                        .takeIf { it.isNotEmpty() }
                        ?: API_METHODS

                    methods.map { method ->
                        RouteSample(
                            httpMethod = method,
                            pattern = pattern,
                            samplePath = pattern.toSamplePath(),
                            expectedGuardMode = pattern.expectedGuardMode(),
                            expectedRouteScope = pattern.expectedRouteScope(method),
                        )
                    }
                }
        }
    }

    @Test
    fun tenantWideCatalogWriteRoutesResolveToTenantScopePermissions() {
        val propertyId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val itemId = UUID.fromString("77777777-7777-7777-7777-777777777794")
        val supplierId = UUID.fromString("77777777-7777-7777-7777-777777777795")
        val identity = RequestIdentity.Tenant(
            tenantId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            tenantUserId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
        )
        val rules = ruleRepository.findEnabledRules()

        val expectations = listOf(
            RouteExpectation(
                method = "POST",
                path = "/api/v1/properties/$propertyId/inventory/items",
                permissionCode = "inventory.catalog.manage",
            ),
            RouteExpectation(
                method = "PUT",
                path = "/api/v1/properties/$propertyId/inventory/items/$itemId",
                permissionCode = "inventory.catalog.manage",
            ),
            RouteExpectation(
                method = "DELETE",
                path = "/api/v1/properties/$propertyId/inventory/items/$itemId",
                permissionCode = "inventory.catalog.manage",
            ),
            RouteExpectation(
                method = "POST",
                path = "/api/v1/properties/$propertyId/procurement/suppliers",
                permissionCode = "procurement.suppliers.manage",
            ),
            RouteExpectation(
                method = "PUT",
                path = "/api/v1/properties/$propertyId/procurement/suppliers/$supplierId",
                permissionCode = "procurement.suppliers.manage",
            ),
            RouteExpectation(
                method = "DELETE",
                path = "/api/v1/properties/$propertyId/procurement/suppliers/$supplierId",
                permissionCode = "procurement.suppliers.manage",
            ),
        )

        expectations.forEach { expectation ->
            val request = routeAccessMatcher.match(
                httpMethod = expectation.method,
                requestPath = expectation.path,
                identity = identity,
                rules = rules,
            )

            requireNotNull(request) {
                "${expectation.method} ${expectation.path} did not match module_access_matrix"
            }
            assertEquals(RouteScope.TENANT, request.routeScope, expectation.path)
            assertEquals(expectation.permissionCode, request.permissionCode, expectation.path)
        }
    }

    @Test
    fun resourceReadRoutesResolveToViewPermissionsBeforeLegacyAnyManageRows() {
        val propertyId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val locationId = UUID.fromString("77777777-7777-7777-7777-777777777796")
        val purchaseOrderId = UUID.fromString("77777777-7777-7777-7777-777777777797")
        val identity = RequestIdentity.Tenant(
            tenantId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            tenantUserId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
        )
        val rules = ruleRepository.findEnabledRules()

        val expectations = listOf(
            RouteExpectation(
                method = "GET",
                path = "/api/v1/properties/$propertyId/inventory/locations/$locationId",
                permissionCode = "inventory.view",
            ),
            RouteExpectation(
                method = "PUT",
                path = "/api/v1/properties/$propertyId/inventory/locations/$locationId",
                permissionCode = "inventory.manage",
            ),
            RouteExpectation(
                method = "GET",
                path = "/api/v1/properties/$propertyId/purchase-orders/$purchaseOrderId",
                permissionCode = "procurement.view",
            ),
            RouteExpectation(
                method = "GET",
                path = "/api/v1/properties/$propertyId/pos-config/menu-items",
                permissionCode = "pos.view",
            ),
            RouteExpectation(
                method = "GET",
                path = "/api/v1/properties/$propertyId/pos-config/menu-categories",
                permissionCode = "pos.view",
            ),
            RouteExpectation(
                method = "PUT",
                path = "/api/v1/properties/$propertyId/purchase-orders/$purchaseOrderId",
                permissionCode = "procurement.manage",
            ),
        )

        expectations.forEach { expectation ->
            val request = routeAccessMatcher.match(
                httpMethod = expectation.method,
                requestPath = expectation.path,
                identity = identity,
                rules = rules,
            )

            requireNotNull(request) {
                "${expectation.method} ${expectation.path} did not match module_access_matrix"
            }
            assertEquals(RouteScope.PROPERTY, request.routeScope, expectation.path)
            assertEquals(expectation.permissionCode, request.permissionCode, expectation.path)
        }
    }

    @Test
    fun administrativeReadRoutesResolveToViewPermissionsBeforeManageRoutes() {
        val tenantId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val propertyId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val tenantRoleId = UUID.fromString("66666666-6666-6666-6666-666666666666")
        val propertyRoleId = UUID.fromString("77777777-7777-7777-7777-777777777798")
        val userId = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val platformUserId = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val menuItemId = UUID.fromString("77777777-7777-7777-7777-777777777799")
        val tenantIdentity = RequestIdentity.Tenant(
            tenantId = tenantId,
            tenantUserId = userId,
        )
        val platformIdentity = RequestIdentity.Platform(platformUserId = platformUserId)
        val rules = ruleRepository.findEnabledRules()

        val expectations = listOf(
            ScopedRouteExpectation(
                method = "GET",
                path = "/api/v1/platform/tenants/$tenantId",
                permissionCode = "platform.tenants.view",
                routeScope = RouteScope.PLATFORM,
            ),
            ScopedRouteExpectation(
                method = "POST",
                path = "/api/v1/platform/tenants",
                permissionCode = "platform.tenants.manage",
                routeScope = RouteScope.PLATFORM,
            ),
            ScopedRouteExpectation(
                method = "POST",
                path = "/api/v1/platform/tenants/$tenantId/approve",
                permissionCode = "platform.tenants.manage",
                routeScope = RouteScope.PLATFORM,
            ),
            ScopedRouteExpectation(
                method = "POST",
                path = "/api/v1/platform/tenants/$tenantId/suspend",
                permissionCode = "platform.tenants.manage",
                routeScope = RouteScope.PLATFORM,
            ),
            ScopedRouteExpectation(
                method = "GET",
                path = "/api/v1/platform/administrators",
                permissionCode = "platform.roles.view",
                routeScope = RouteScope.PLATFORM,
            ),
            ScopedRouteExpectation(
                method = "POST",
                path = "/api/v1/platform/administrators/$platformUserId/assign",
                permissionCode = "platform.administrators.manage",
                routeScope = RouteScope.PLATFORM,
            ),
            ScopedRouteExpectation(
                method = "POST",
                path = "/api/v1/platform/administrators/$platformUserId/revoke",
                permissionCode = "platform.administrators.manage",
                routeScope = RouteScope.PLATFORM,
            ),
            ScopedRouteExpectation(
                method = "GET",
                path = "/api/v1/properties/$propertyId/inventory/recipes",
                permissionCode = "inventory.view",
                routeScope = RouteScope.PROPERTY,
            ),
            ScopedRouteExpectation(
                method = "PUT",
                path = "/api/v1/properties/$propertyId/inventory/recipes",
                permissionCode = "inventory.manage",
                routeScope = RouteScope.PROPERTY,
            ),
            ScopedRouteExpectation(
                method = "DELETE",
                path = "/api/v1/properties/$propertyId/inventory/recipes/$menuItemId",
                permissionCode = "inventory.manage",
                routeScope = RouteScope.PROPERTY,
            ),
            ScopedRouteExpectation(
                method = "POST",
                path = "/api/v1/properties/$propertyId/reports/daily_management_summary/runs",
                permissionCode = "reports.generate",
                routeScope = RouteScope.PROPERTY,
            ),
            ScopedRouteExpectation(
                method = "GET",
                path = "/api/v1/tenants/$tenantId/modules",
                permissionCode = "module.view",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "POST",
                path = "/api/v1/tenants/$tenantId/modules",
                permissionCode = "module.manage",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "GET",
                path = "/api/v1/tenants/$tenantId/roles",
                permissionCode = "tenant.roles.view",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "GET",
                path = "/api/v1/tenants/$tenantId/roles/$tenantRoleId",
                permissionCode = "tenant.roles.view",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "GET",
                path = "/api/v1/tenants/$tenantId/permissions",
                permissionCode = "tenant.roles.view",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "POST",
                path = "/api/v1/tenants/$tenantId/roles",
                permissionCode = "tenant.users.manage",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "PUT",
                path = "/api/v1/tenants/$tenantId/roles/$tenantRoleId",
                permissionCode = "tenant.users.manage",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "GET",
                path = "/api/v1/tenants/$tenantId/administrators",
                permissionCode = "tenant.roles.view",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "POST",
                path = "/api/v1/tenants/$tenantId/administrators/$userId/assign",
                permissionCode = "tenant.administrators.manage",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "POST",
                path = "/api/v1/tenants/$tenantId/administrators/$userId/revoke",
                permissionCode = "tenant.administrators.manage",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "GET",
                path = "/api/v1/tenants/$tenantId/properties/$propertyId/roles",
                permissionCode = "tenant.properties.roles.view",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "GET",
                path = "/api/v1/tenants/$tenantId/properties/$propertyId/roles/$propertyRoleId",
                permissionCode = "tenant.properties.roles.view",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "GET",
                path = "/api/v1/tenants/$tenantId/properties/$propertyId/users/$userId/roles",
                permissionCode = "tenant.properties.roles.view",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "POST",
                path = "/api/v1/tenants/$tenantId/properties/$propertyId/users/$userId/roles/$propertyRoleId/assign",
                permissionCode = "tenant.properties.manage_access",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "GET",
                path = "/api/v1/tenants/$tenantId/properties/$propertyId/administrators",
                permissionCode = "tenant.properties.roles.view",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "POST",
                path = "/api/v1/tenants/$tenantId/properties/$propertyId/administrators/$userId/assign",
                permissionCode = "tenant.properties.administrators.manage",
                routeScope = RouteScope.TENANT,
            ),
            ScopedRouteExpectation(
                method = "POST",
                path = "/api/v1/tenants/$tenantId/properties/$propertyId/administrators/$userId/revoke",
                permissionCode = "tenant.properties.administrators.manage",
                routeScope = RouteScope.TENANT,
            ),
        )

        expectations.forEach { expectation ->
            val identity = if (expectation.routeScope == RouteScope.PLATFORM) {
                platformIdentity
            } else {
                tenantIdentity
            }
            val request = routeAccessMatcher.match(
                httpMethod = expectation.method,
                requestPath = expectation.path,
                identity = identity,
                rules = rules,
            )

            requireNotNull(request) {
                "${expectation.method} ${expectation.path} did not match module_access_matrix"
            }
            assertEquals(expectation.routeScope, request.routeScope, expectation.path)
            assertEquals(expectation.permissionCode, request.permissionCode, expectation.path)
            if (expectation.path.startsWith("/api/v1/platform/tenants/$tenantId")) {
                assertEquals(tenantId, request.tenantId, expectation.path)
            }
        }
    }

    private fun String.toSamplePath(): String {
        return PATH_VARIABLE_PATTERN.replace(this) { match ->
            val variableName = match.groupValues[1].substringBefore(":")
            SAMPLE_UUIDS[variableName] ?: DEFAULT_SAMPLE_UUID
        }
    }

    private fun String.expectedGuardMode(): GuardMode {
        return when {
            this == "/api/v1/session" ||
                    this == "/api/v1/platform/session" ||
                    this == "/api/v1/staff/sessions/current" ->
                GuardMode.AUTHENTICATED_IDENTITY
            startsWith("/api/v1/platform/") -> GuardMode.PLATFORM_PERMISSION
            startsWith("/api/v1/public/properties/") -> GuardMode.MODULE_ONLY
            startsWith("/api/v1/payments/webhooks/") -> GuardMode.PUBLIC_TOKEN
            // Peak's own subscription callbacks. Separate from the payments webhooks
            // above, which settle a property's guest payments against a folio; this one
            // settles Peak's revenue into Peak's own merchant account, and the two must
            // not share a route or a credential source.
            startsWith("/api/v1/platform-billing/webhooks/") -> GuardMode.PUBLIC_TOKEN
            startsWith("/api/v1/communication/webhooks/") -> GuardMode.PUBLIC_TOKEN
            this == "/api/v1/invitations/accept" -> GuardMode.PUBLIC_TOKEN
            this == "/api/v1/staff/sessions" ||
                    this == "/api/v1/staff/credentials/activate" ||
                    startsWith("/api/v1/devices/") -> GuardMode.PUBLIC_TOKEN
            this == "/api/v1/verifications" || this == "/api/v1/verifications/confirm" ->
                GuardMode.PUBLIC_TOKEN
            this == "/api/v1/onboarding/request-access" ||
                    this == "/api/v1/onboarding/verify-phone" -> GuardMode.PUBLIC_TOKEN
            startsWith("/api/v1/onboarding/me/") -> GuardMode.AUTHENTICATED_IDENTITY
            startsWith("/api/v1/tenants/") -> GuardMode.STAFF_PERMISSION
            startsWith("/api/v1/properties") -> GuardMode.STAFF_PERMISSION
            startsWith("/api/v1/communication") -> GuardMode.STAFF_PERMISSION
            startsWith("/api/v1/realtime/") -> GuardMode.STAFF_PERMISSION
            else -> error("No expected guard mode for API route pattern $this")
        }
    }

    private fun String.expectedRouteScope(method: RequestMethod): RouteScope {
        return when {
            this == "/api/v1/session" -> RouteScope.TENANT
            this == "/api/v1/staff/sessions/current" -> RouteScope.TENANT
            this == "/api/v1/platform/session" -> RouteScope.PLATFORM
            startsWith("/api/v1/platform/") -> RouteScope.PLATFORM
            startsWith("/api/v1/public/properties/") -> RouteScope.PUBLIC_PROPERTY
            startsWith("/api/v1/payments/webhooks/") -> RouteScope.PUBLIC
            // PUBLIC, never PUBLIC_PROPERTY: authorizePublicToken requires PUBLIC exactly
            // and refuses a route carrying tenant or property variables. The wrong one
            // satisfies the check constraint and then denies every callback at runtime.
            startsWith("/api/v1/platform-billing/webhooks/") -> RouteScope.PUBLIC
            startsWith("/api/v1/communication/webhooks/") -> RouteScope.PUBLIC
            this == "/api/v1/invitations/accept" -> RouteScope.PUBLIC
            this == "/api/v1/staff/sessions" ||
                    this == "/api/v1/staff/credentials/activate" ||
                    startsWith("/api/v1/devices/") -> RouteScope.PUBLIC
            this == "/api/v1/verifications" || this == "/api/v1/verifications/confirm" ->
                RouteScope.PUBLIC
            this == "/api/v1/onboarding/request-access" ||
                    this == "/api/v1/onboarding/verify-phone" -> RouteScope.PUBLIC
            startsWith("/api/v1/onboarding/me/") -> RouteScope.ONBOARDING_APPLICATION
            startsWith("/api/v1/tenants/") -> RouteScope.TENANT
            this == "/api/v1/properties" -> RouteScope.TENANT
            // First hotel is a tenant act: no propertyId exists yet.
            this == "/api/v1/properties/bootstrap" -> RouteScope.TENANT
            startsWith("/api/v1/properties/taxes") -> RouteScope.TENANT
            isTenantWideCatalogWrite(method) -> RouteScope.TENANT
            startsWith("/api/v1/properties/") -> RouteScope.PROPERTY
            startsWith("/api/v1/communication") -> RouteScope.TENANT
            startsWith("/api/v1/realtime/") -> RouteScope.PROPERTY
            else -> error("No expected route scope for API route pattern $this")
        }
    }

    private fun String.isTenantWideCatalogWrite(method: RequestMethod): Boolean {
        return when (method) {
            RequestMethod.POST -> this == "/api/v1/properties/{propertyId}/inventory/items" ||
                    this == "/api/v1/properties/{propertyId}/procurement/suppliers"
            RequestMethod.PUT,
            RequestMethod.DELETE -> this == "/api/v1/properties/{propertyId}/inventory/items/{itemId}" ||
                    this == "/api/v1/properties/{propertyId}/procurement/suppliers/{supplierId}"
            else -> false
        }
    }

    private data class RouteSample(
        val httpMethod: RequestMethod,
        val pattern: String,
        val samplePath: String,
        val expectedGuardMode: GuardMode,
        val expectedRouteScope: RouteScope,
    )

    private data class RouteExpectation(
        val method: String,
        val path: String,
        val permissionCode: String,
    )

    private data class ScopedRouteExpectation(
        val method: String,
        val path: String,
        val permissionCode: String,
        val routeScope: RouteScope,
    )

    private companion object {
        val API_METHODS = setOf(
            RequestMethod.GET,
            RequestMethod.POST,
            RequestMethod.PUT,
            RequestMethod.PATCH,
            RequestMethod.DELETE,
        )

        val PATH_VARIABLE_PATTERN = Regex("\\{([^}]+)}")
        val DEFAULT_SAMPLE_UUID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa").toString()

        val SAMPLE_UUIDS = mapOf(
            "id" to UUID.fromString("11111111-1111-1111-1111-111111111111").toString(),
            "tenantId" to UUID.fromString("22222222-2222-2222-2222-222222222222").toString(),
            "propertyId" to UUID.fromString("33333333-3333-3333-3333-333333333333").toString(),
            "userId" to UUID.fromString("44444444-4444-4444-4444-444444444444").toString(),
            "platformUserId" to UUID.fromString("44444444-4444-4444-4444-444444444444").toString(),
            "identityLinkId" to UUID.fromString("55555555-5555-5555-5555-555555555555").toString(),
            "tenantRoleId" to UUID.fromString("66666666-6666-6666-6666-666666666666").toString(),
            "platformRoleId" to UUID.fromString("66666666-6666-6666-6666-666666666666").toString(),
            "buildingId" to UUID.fromString("77777777-7777-7777-7777-777777777771").toString(),
            "floorId" to UUID.fromString("77777777-7777-7777-7777-777777777772").toString(),
            "roomTypeId" to UUID.fromString("77777777-7777-7777-7777-777777777773").toString(),
            "roomId" to UUID.fromString("77777777-7777-7777-7777-777777777777").toString(),
            "revenueCenterId" to UUID.fromString("77777777-7777-7777-7777-777777777778").toString(),
            "departmentId" to UUID.fromString("77777777-7777-7777-7777-777777777779").toString(),
            "taxRateId" to UUID.fromString("77777777-7777-7777-7777-777777777780").toString(),
            "guestId" to UUID.fromString("77777777-7777-7777-7777-777777777781").toString(),
            "documentId" to UUID.fromString("77777777-7777-7777-7777-777777777788").toString(),
            "reservationId" to UUID.fromString("77777777-7777-7777-7777-777777777782").toString(),
            "stayId" to UUID.fromString("77777777-7777-7777-7777-777777777783").toString(),
            "folioId" to UUID.fromString("77777777-7777-7777-7777-777777777784").toString(),
            "chargeId" to UUID.fromString("77777777-7777-7777-7777-777777777785").toString(),
            "invoiceId" to UUID.fromString("77777777-7777-7777-7777-777777777786").toString(),
            "runId" to UUID.fromString("77777777-7777-7777-7777-777777777787").toString(),
            "cashSessionId" to UUID.fromString("77777777-7777-7777-7777-777777777789").toString(),
            "transactionId" to UUID.fromString("77777777-7777-7777-7777-777777777790").toString(),
            "providerAccountId" to UUID.fromString("77777777-7777-7777-7777-777777777791").toString(),
            "reconciliationId" to UUID.fromString("77777777-7777-7777-7777-777777777792").toString(),
            "receiptId" to UUID.fromString("77777777-7777-7777-7777-777777777793").toString(),
            "contactId" to UUID.fromString("88888888-8888-8888-8888-888888888887").toString(),
            "channelId" to UUID.fromString("88888888-8888-8888-8888-888888888888").toString(),
            "deliveryRequestId" to UUID.fromString("99999999-9999-9999-9999-999999999999").toString(),
            "moduleId" to "booking_engine",
        )
    }
}
