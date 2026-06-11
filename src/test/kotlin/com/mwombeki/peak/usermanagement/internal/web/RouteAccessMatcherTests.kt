package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.GuardMode
import com.mwombeki.peak.usermanagement.api.RouteScope
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouteAccessMatcherTests {

    private val matcher = RouteAccessMatcher()

    @Test
    fun matchesVersionedTenantRouteAgainstUnversionedMatrixPattern() {
        val tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val userId = UUID.fromString("22222222-2222-2222-2222-222222222222")

        val request = matcher.match(
            httpMethod = "PATCH",
            requestPath = "/api/v1/tenants/$tenantId/profile",
            identity = RequestIdentity.Tenant(tenantId, userId),
            rules = listOf(
                RouteAccessRule(
                    moduleId = "tenant_admin",
                    httpMethod = "PATCH",
                    apiPattern = "/api/tenants/:tenantId/profile",
                    permissionCode = "tenant.profile.manage",
                    routeScope = RouteScope.TENANT,
                    guardMode = GuardMode.STAFF_PERMISSION,
                ),
            ),
        )

        requireNotNull(request)
        assertEquals("tenant_admin", request.moduleId)
        assertEquals("tenant.profile.manage", request.permissionCode)
        assertEquals(tenantId, request.tenantId)
        assertEquals(null, request.propertyId)
    }

    @Test
    fun fillsTenantFromIdentityAndPropertyFromRouteForPropertyRoutes() {
        val tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val userId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val propertyId = UUID.fromString("33333333-3333-3333-3333-333333333333")

        val request = matcher.match(
            httpMethod = "POST",
            requestPath = "/api/v1/properties/$propertyId/booking-engine/settings",
            identity = RequestIdentity.Tenant(tenantId, userId),
            rules = listOf(
                RouteAccessRule(
                    moduleId = "booking_engine",
                    httpMethod = "ANY",
                    apiPattern = "/api/properties/:propertyId/booking-engine*",
                    permissionCode = "booking_engine.manage",
                    routeScope = RouteScope.PROPERTY,
                    guardMode = GuardMode.STAFF_PERMISSION,
                ),
            ),
        )

        requireNotNull(request)
        assertEquals(tenantId, request.tenantId)
        assertEquals(propertyId, request.propertyId)
    }

    @Test
    fun choosesExactMethodRuleBeforeAnyRule() {
        val tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val userId = UUID.fromString("22222222-2222-2222-2222-222222222222")

        val request = matcher.match(
            httpMethod = "GET",
            requestPath = "/api/v1/tenants/$tenantId/contacts",
            identity = RequestIdentity.Tenant(tenantId, userId),
            rules = listOf(
                RouteAccessRule(
                    moduleId = "tenant_admin",
                    httpMethod = "ANY",
                    apiPattern = "/api/tenants/:tenantId/contacts*",
                    permissionCode = "tenant.contacts.manage",
                    routeScope = RouteScope.TENANT,
                    guardMode = GuardMode.STAFF_PERMISSION,
                ),
                RouteAccessRule(
                    moduleId = "tenant_admin",
                    httpMethod = "GET",
                    apiPattern = "/api/tenants/:tenantId/contacts*",
                    permissionCode = "tenant.contacts.view",
                    routeScope = RouteScope.TENANT,
                    guardMode = GuardMode.STAFF_PERMISSION,
                ),
            ),
        )

        requireNotNull(request)
        assertEquals("tenant.contacts.view", request.permissionCode)
    }

    @Test
    fun returnsNullForUnregisteredRoute() {
        val request = matcher.match(
            httpMethod = "GET",
            requestPath = "/api/v1/unregistered",
            identity = RequestIdentity.Public(),
            rules = listOf(
                RouteAccessRule(
                    moduleId = "tenant_admin",
                    httpMethod = "GET",
                    apiPattern = "/api/tenants/:tenantId/profile",
                    permissionCode = "tenant.profile.view",
                    routeScope = RouteScope.TENANT,
                    guardMode = GuardMode.STAFF_PERMISSION,
                ),
            ),
        )

        assertNull(request)
    }
}
