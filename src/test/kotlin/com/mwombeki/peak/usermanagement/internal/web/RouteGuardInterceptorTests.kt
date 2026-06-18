package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.security.SecurityProblemWriter
import com.mwombeki.peak.usermanagement.api.AuthorizationDecision
import com.mwombeki.peak.usermanagement.api.AuthorizationPort
import com.mwombeki.peak.usermanagement.api.GuardMode
import com.mwombeki.peak.usermanagement.api.RouteAuthorizationRequest
import com.mwombeki.peak.usermanagement.api.RouteScope
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

class RouteGuardInterceptorTests {

    private val holder = RequestContextHolder()
    private val authorizationPort = RecordingAuthorizationPort()

    @AfterTest
    fun clearContext() {
        holder.clear()
    }

    @Test
    fun allowsRegisteredRouteWhenAuthorizationAllows() {
        val tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val userId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        holder.set(requestContext(RequestIdentity.Tenant(tenantId, userId)))
        authorizationPort.decision = AuthorizationDecision.allowed()

        val response = MockHttpServletResponse()
        val shouldContinue = interceptor(
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
        ).preHandle(
            MockHttpServletRequest("PATCH", "/api/v1/tenants/$tenantId/profile"),
            response,
            Any(),
        )

        assertTrue(shouldContinue)
        assertEquals(200, response.status)
        assertEquals("tenant.profile.manage", authorizationPort.lastRequest?.permissionCode)
    }

    @Test
    fun deniesUnregisteredApiRouteByDefault() {
        holder.set(requestContext(RequestIdentity.Public()))

        val response = MockHttpServletResponse()
        val shouldContinue = interceptor(rules = emptyList())
            .preHandle(
                MockHttpServletRequest("GET", "/api/v1/unregistered"),
                response,
                Any(),
            )

        assertFalse(shouldContinue)
        assertEquals(403, response.status)
        assertEquals("application/problem+json", response.contentType)
        assertTrue(response.contentAsString.contains("Route is not registered"))
    }

    @Test
    fun returnsBadRequestForInvalidUuidRouteParameter() {
        holder.set(requestContext(RequestIdentity.Public()))

        val response = MockHttpServletResponse()
        val shouldContinue = interceptor(
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
        ).preHandle(
            MockHttpServletRequest("GET", "/api/v1/tenants/not-a-uuid/profile"),
            response,
            Any(),
        )

        assertFalse(shouldContinue)
        assertEquals(400, response.status)
        assertTrue(response.contentAsString.contains("Invalid route parameters"))
    }

    @Test
    fun deniesWhenAuthorizationDenies() {
        val tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val userId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        holder.set(requestContext(RequestIdentity.Tenant(tenantId, userId)))
        authorizationPort.decision = AuthorizationDecision.denied("Tenant user lacks required module permission")

        val response = MockHttpServletResponse()
        val shouldContinue = interceptor(
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
        ).preHandle(
            MockHttpServletRequest("PATCH", "/api/v1/tenants/$tenantId/profile"),
            response,
            Any(),
        )

        assertFalse(shouldContinue)
        assertEquals(403, response.status)
        assertTrue(response.contentAsString.contains("Tenant user lacks required module permission"))
    }

    @Test
    fun reportsMissingRequestContextAsServerMisconfiguration() {
        val response = MockHttpServletResponse()

        val shouldContinue = interceptor(rules = emptyList())
            .preHandle(
                MockHttpServletRequest("GET", "/api/v1/tenants"),
                response,
                Any(),
            )

        assertFalse(shouldContinue)
        assertEquals(500, response.status)
        assertTrue(response.contentAsString.contains("Request context is not bound"))
    }

    private fun interceptor(
        rules: List<RouteAccessRule>,
    ): RouteGuardInterceptor {
        return RouteGuardInterceptor(
            properties = RouteGuardProperties(),
            ruleRepository = StaticRouteAccessRuleRepository(rules),
            routeAccessMatcher = RouteAccessMatcher(),
            authorizationPort = authorizationPort,
            requestContextHolder = holder,
            transactionTemplate = TransactionTemplate(NoopTransactionManager()),
            problemWriter = SecurityProblemWriter(),
        )
    }

    private fun requestContext(identity: RequestIdentity): RequestContext {
        return RequestContext(
            identity = identity,
            correlationId = "corr-route-guard",
            idempotencyKey = null,
            httpMethod = "GET",
            requestPath = "/test",
        )
    }

    private class StaticRouteAccessRuleRepository(
        private val rules: List<RouteAccessRule>,
    ) : RouteAccessRuleRepository {
        override fun findEnabledRules(): List<RouteAccessRule> = rules
    }

    private class RecordingAuthorizationPort : AuthorizationPort {
        var decision: AuthorizationDecision = AuthorizationDecision.allowed()
        var lastRequest: RouteAuthorizationRequest? = null

        override fun authorize(request: RouteAuthorizationRequest): AuthorizationDecision {
            lastRequest = request
            return decision
        }
    }

    private class NoopTransactionManager : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()

        override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit

        override fun doCommit(status: DefaultTransactionStatus) = Unit

        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }
}
