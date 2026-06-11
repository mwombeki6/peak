package com.mwombeki.peak.shared.context

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

class RequestContextResolverTests {

    @Test
    fun resolvesPublicIdentityWithoutAuthentication() {
        val request = MockHttpServletRequest("GET", "/api/v1/public/ping")
        request.addHeader(PeakRequestHeaders.CORRELATION_ID, "corr-123")

        val context = resolver().resolve(request, null)

        assertEquals(RequestIdentity.Public(correlationId = "corr-123"), context.identity)
        assertEquals("corr-123", context.correlationId)
        assertEquals("GET", context.httpMethod)
        assertEquals("/api/v1/public/ping", context.requestPath)
    }

    @Test
    fun resolvesTenantJwtIdentity() {
        val tenantId = UUID.randomUUID()
        val tenantUserId = UUID.randomUUID()
        val request = MockHttpServletRequest("POST", "/api/v1/tenants/$tenantId/users")
        request.addHeader(PeakRequestHeaders.CORRELATION_ID, "corr-tenant")
        request.addHeader(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-tenant-1")

        val context = resolver().resolve(
            request,
            jwtAuthentication(
                "peak_identity_mode" to "tenant",
                "tenant_id" to tenantId.toString(),
                "tenant_user_id" to tenantUserId.toString(),
            ),
        )

        assertEquals(
            RequestIdentity.Tenant(tenantId, tenantUserId, "corr-tenant"),
            context.identity,
        )
        assertEquals("idem-tenant-1", context.idempotencyKey)
    }

    @Test
    fun resolvesPlatformJwtIdentity() {
        val platformUserId = UUID.randomUUID()
        val request = MockHttpServletRequest("POST", "/api/v1/platform/tenants")
        request.addHeader(PeakRequestHeaders.CORRELATION_ID, "corr-platform")

        val context = resolver().resolve(
            request,
            jwtAuthentication(
                "peak_identity_mode" to "platform",
                "platform_user_id" to platformUserId.toString(),
            ),
        )

        assertEquals(
            RequestIdentity.Platform(platformUserId, "corr-platform"),
            context.identity,
        )
    }

    @Test
    fun resolvesSupportJwtIdentity() {
        val platformUserId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val supportSessionId = UUID.randomUUID()
        val request = MockHttpServletRequest("GET", "/api/v1/platform/support")
        request.addHeader(PeakRequestHeaders.CORRELATION_ID, "corr-support")

        val context = resolver().resolve(
            request,
            jwtAuthentication(
                "peak_identity_mode" to "support",
                "platform_user_id" to platformUserId.toString(),
                "support_tenant_id" to tenantId.toString(),
                "support_session_id" to supportSessionId.toString(),
            ),
        )

        assertEquals(
            RequestIdentity.Support(
                platformUserId = platformUserId,
                tenantId = tenantId,
                supportSessionId = supportSessionId,
                correlationId = "corr-support",
            ),
            context.identity,
        )
    }

    @Test
    fun rejectsIdentityHeadersByDefault() {
        val request = MockHttpServletRequest("GET", "/api/v1/platform/tenants")
        request.addHeader(PeakRequestHeaders.PLATFORM_USER_ID, UUID.randomUUID().toString())

        val error = assertFailsWith<RequestContextException> {
            resolver().resolve(request, null)
        }

        assertEquals("Identity headers are disabled for this runtime", error.message)
    }

    @Test
    fun rejectsIdentityHeadersCombinedWithJwt() {
        val tenantId = UUID.randomUUID()
        val tenantUserId = UUID.randomUUID()
        val request = MockHttpServletRequest("GET", "/api/v1/tenants/$tenantId")
        request.addHeader(PeakRequestHeaders.TENANT_ID, tenantId.toString())

        val error = assertFailsWith<RequestContextException> {
            resolver().resolve(
                request,
                jwtAuthentication(
                    "peak_identity_mode" to "tenant",
                    "tenant_id" to tenantId.toString(),
                    "tenant_user_id" to tenantUserId.toString(),
                ),
            )
        }

        assertEquals(
            "Identity headers cannot be combined with authenticated identity",
            error.message,
        )
    }

    @Test
    fun resolvesHeaderIdentityWhenExplicitlyEnabled() {
        val platformUserId = UUID.randomUUID()
        val request = MockHttpServletRequest("GET", "/api/v1/platform/tenants")
        request.addHeader(PeakRequestHeaders.CORRELATION_ID, "corr-header")
        request.addHeader(PeakRequestHeaders.PLATFORM_USER_ID, platformUserId.toString())

        val context = resolver(allowHeaderIdentity = true).resolve(request, null)

        assertEquals(
            RequestIdentity.Platform(platformUserId, "corr-header"),
            context.identity,
        )
    }

    @Test
    fun rejectsInvalidCorrelationId() {
        val request = MockHttpServletRequest("GET", "/api/v1/platform/tenants")
        request.addHeader(PeakRequestHeaders.CORRELATION_ID, "bad correlation")

        val error = assertFailsWith<RequestContextException> {
            resolver().resolve(request, null)
        }

        assertEquals("Invalid correlation ID", error.message)
    }

    @Test
    fun rejectsInvalidIdempotencyKey() {
        val request = MockHttpServletRequest("POST", "/api/v1/platform/tenants")
        request.addHeader(PeakRequestHeaders.IDEMPOTENCY_KEY, "bad key")

        val error = assertFailsWith<RequestContextException> {
            resolver().resolve(request, null)
        }

        assertEquals("Invalid idempotency key", error.message)
    }

    private fun resolver(
        allowHeaderIdentity: Boolean = false,
    ): RequestContextResolver {
        return RequestContextResolver(
            RequestContextProperties(allowHeaderIdentity = allowHeaderIdentity),
        )
    }

    private fun jwtAuthentication(
        vararg claims: Pair<String, String>,
    ): JwtAuthenticationToken {
        val jwtBuilder = Jwt.withTokenValue("token")
            .header("alg", "none")

        claims.forEach { (name, value) ->
            jwtBuilder.claim(name, value)
        }

        return JwtAuthenticationToken(
            jwtBuilder.build(),
            listOf(SimpleGrantedAuthority("SCOPE_peak")),
        )
    }
}
