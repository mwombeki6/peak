package com.mwombeki.peak.realtime.internal.config

import com.mwombeki.peak.shared.context.ExternalIdentityResolver
import com.mwombeki.peak.shared.context.RequestContextProperties
import com.mwombeki.peak.shared.context.RequestContextResolver
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.context.ResolvedExternalIdentity
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.http.HttpHeaders
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

class WebSocketHandshakeContextResolverTests {
    private val tenantId = UUID.randomUUID()
    private val tenantUserId = UUID.randomUUID()
    private val meterRegistry = SimpleMeterRegistry()

    @AfterTest
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `resolves linked tenant identity from authenticated upgrade request`() {
        val resolver = handshakeResolver(
            ResolvedExternalIdentity.Tenant(tenantId, tenantUserId),
        )

        val context = resolver.resolve(authenticatedRequest())
        val identity = context?.identity as? RequestIdentity.Tenant

        assertEquals(tenantId, identity?.tenantId)
        assertEquals(tenantUserId, identity?.tenantUserId)
    }

    @Test
    fun `rejects unlinked authenticated identity`() {
        val resolver = handshakeResolver(null)

        val context = resolver.resolve(authenticatedRequest())

        assertNull(context)
        assertTrue(
            meterRegistry.counter(
                "realtime.websocket.handshakes.rejected",
                "reason",
                "tenant_identity_required",
            ).count() == 1.0,
        )
    }

    private fun handshakeResolver(
        resolvedIdentity: ResolvedExternalIdentity?,
    ): WebSocketHandshakeContextResolver {
        val contextResolver = RequestContextResolver(
            RequestContextProperties(),
            ExternalIdentityResolver { resolvedIdentity },
        )
        return WebSocketHandshakeContextResolver(contextResolver, meterRegistry)
    }

    private fun authenticatedRequest(): ServletServerHttpRequest {
        val jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .issuer("https://identity.example.test/realms/peak")
            .subject("tenant-subject")
            .build()
        val authentication = JwtAuthenticationToken(
            jwt,
            listOf(SimpleGrantedAuthority("SCOPE_peak")),
        )
        SecurityContextHolder.getContext().authentication = authentication
        val servletRequest = MockHttpServletRequest("GET", "/ws-connect").apply {
            addHeader(HttpHeaders.UPGRADE, "websocket")
            addHeader(HttpHeaders.CONNECTION, "Upgrade")
            setUserPrincipal(authentication)
        }
        return ServletServerHttpRequest(servletRequest)
    }
}
