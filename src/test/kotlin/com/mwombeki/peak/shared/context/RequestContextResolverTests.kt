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

        val context = resolver(allowTrustedJwtIdentityClaims = true).resolve(
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

        val context = resolver(allowTrustedJwtIdentityClaims = true).resolve(
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

        val context = resolver(allowTrustedJwtIdentityClaims = true).resolve(
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
    fun resolvesTenantOidcJwtIdentity() {
        val tenantId = UUID.randomUUID()
        val tenantUserId = UUID.randomUUID()
        val request = MockHttpServletRequest("GET", "/api/v1/tenants/$tenantId/profile")
        request.addHeader(PeakRequestHeaders.CORRELATION_ID, "corr-oidc-tenant")

        val context = resolver(
            externalIdentityResolver = ExternalIdentityResolver { principal ->
                assertEquals("oidc", principal.provider)
                assertEquals("https://issuer.example.com/realms/peak", principal.issuer)
                assertEquals("tenant-subject", principal.subject)
                assertEquals("tenant@example.com", principal.email)

                ResolvedExternalIdentity.Tenant(
                    tenantId = tenantId,
                    tenantUserId = tenantUserId,
                )
            },
        ).resolve(
            request,
            jwtAuthentication(
                "iss" to "https://issuer.example.com/realms/peak",
                "sub" to "tenant-subject",
                "email" to "tenant@example.com",
                "email_verified" to "true",
            ),
        )

        assertEquals(
            RequestIdentity.Tenant(tenantId, tenantUserId, "corr-oidc-tenant"),
            context.identity,
        )
    }

    @Test
    fun resolvesPlatformOidcJwtIdentity() {
        val platformUserId = UUID.randomUUID()
        val request = MockHttpServletRequest("GET", "/api/v1/platform/tenants")
        request.addHeader(PeakRequestHeaders.CORRELATION_ID, "corr-oidc-platform")

        val context = resolver(
            externalIdentityResolver = ExternalIdentityResolver { principal ->
                assertEquals("https://issuer.example.com/realms/peak", principal.issuer)
                assertEquals("platform-subject", principal.subject)

                ResolvedExternalIdentity.Platform(platformUserId)
            },
        ).resolve(
            request,
            jwtAuthentication(
                "iss" to "https://issuer.example.com/realms/peak",
                "sub" to "platform-subject",
            ),
        )

        assertEquals(
            RequestIdentity.Platform(platformUserId, "corr-oidc-platform"),
            context.identity,
        )
    }

    @Test
    fun resolvesUnlinkedOidcJwtIdentityAsPublic() {
        val request = MockHttpServletRequest("GET", "/api/v1/tenants")
        request.addHeader(PeakRequestHeaders.CORRELATION_ID, "corr-unlinked-oidc")

        val context = resolver().resolve(
            request,
            jwtAuthentication(
                "iss" to "https://issuer.example.com/realms/peak",
                "sub" to "missing-subject",
                "email" to "missing@example.com",
                "email_verified" to "true",
            ),
        )

        assertEquals(RequestIdentity.Public(correlationId = "corr-unlinked-oidc"), context.identity)
    }

    @Test
    fun rejectsExternalOidcJwtWithUnverifiedEmail() {
        val request = MockHttpServletRequest("GET", "/api/v1/tenants")

        val error = assertFailsWith<RequestContextException> {
            resolver().resolve(
                request,
                jwtAuthentication(
                    "iss" to "https://issuer.example.com/realms/peak",
                    "sub" to "unverified-subject",
                    "email" to "unverified@example.com",
                    "email_verified" to "false",
                ),
            )
        }

        assertEquals("JWT email must be verified", error.message)
    }

    @Test
    fun rejectsTrustedJwtIdentityClaimsByDefault() {
        val tenantId = UUID.randomUUID()
        val tenantUserId = UUID.randomUUID()
        val request = MockHttpServletRequest("GET", "/api/v1/tenants/$tenantId/profile")

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
            "Trusted JWT identity claims are disabled for this runtime",
            error.message,
        )
    }

    @Test
    fun resolvesPublicPropertyIdentityFromRoute() {
        val propertyId = UUID.randomUUID()
        val request = MockHttpServletRequest(
            "POST",
            "/api/v1/public/properties/$propertyId/booking-engine/sessions",
        )
        request.addHeader(PeakRequestHeaders.CORRELATION_ID, "corr-public-property")

        val context = resolver().resolve(request, null)

        assertEquals(
            RequestIdentity.Public(
                propertyId = propertyId,
                correlationId = "corr-public-property",
            ),
            context.identity,
        )
    }

    @Test
    fun rejectsOidcJwtWithoutIssuer() {
        val request = MockHttpServletRequest("GET", "/api/v1/tenants")

        val error = assertFailsWith<RequestContextException> {
            resolver().resolve(
                request,
                jwtAuthentication("sub" to "missing-issuer"),
            )
        }

        assertEquals("JWT claim iss is required", error.message)
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
        allowTrustedJwtIdentityClaims: Boolean = false,
        externalIdentityResolver: ExternalIdentityResolver = ExternalIdentityResolver { null },
    ): RequestContextResolver {
        return RequestContextResolver(
            RequestContextProperties(
                allowHeaderIdentity = allowHeaderIdentity,
                allowTrustedJwtIdentityClaims = allowTrustedJwtIdentityClaims,
            ),
            externalIdentityResolver,
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
