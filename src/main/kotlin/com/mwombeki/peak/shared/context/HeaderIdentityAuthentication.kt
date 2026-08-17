package com.mwombeki.peak.shared.context

import java.util.UUID
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority

/**
 * Minimal authentication produced from identity headers for WebSocket
 * handshakes. Never carries privileges: the STOMP channel interceptor
 * re-derives the tenant identity from the handshake request context and the
 * subscription authorizer enforces destination access. It deliberately does
 * not count as a real authenticated principal in [RequestContextResolver],
 * because the identity it carries IS the header identity.
 */
class HeaderIdentityAuthentication(
    val tenantId: UUID,
    val tenantUserId: UUID,
) : Authentication {

    private val grantedAuthorities: MutableCollection<GrantedAuthority> =
        java.util.Collections.emptyList()

    override fun getAuthorities(): MutableCollection<out GrantedAuthority> = grantedAuthorities
    override fun getCredentials(): Any = Unit
    override fun getDetails(): Any = Unit
    override fun getPrincipal(): Any = this
    override fun isAuthenticated(): Boolean = true
    override fun setAuthenticated(isAuthenticated: Boolean) = Unit
    override fun getName(): String = tenantUserId.toString()
}