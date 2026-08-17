package com.mwombeki.peak.shared.context

import java.util.UUID
import org.springframework.modulith.NamedInterface
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority

/**
 * A device-bound PIN session. The bearer is minted by Peak, not Keycloak, and
 * is never a JWT — [org.springframework.security.oauth2.jwt.JwtDecoder] must
 * not see an `ops_` token.
 *
 * Unlike [HeaderIdentityAuthentication], this *is* a real authenticated
 * principal: the identity it carries was proven by a registered device
 * signature plus a staff PIN, and [RequestContextResolver] maps it to a
 * tenant identity with [SessionClass.OPERATIONAL].
 */
@NamedInterface("context")
class OperationalSessionAuthentication(
    val sessionId: UUID,
    val tenantId: UUID,
    val tenantUserId: UUID,
    val deviceId: UUID,
    val propertyId: UUID,
    val outletId: UUID? = null,
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

    companion object {
        const val TOKEN_PREFIX = "ops_"
    }
}

@NamedInterface("context")
data class OperationalSessionPrincipal(
    val sessionId: UUID,
    val tenantId: UUID,
    val tenantUserId: UUID,
    val deviceId: UUID,
    val propertyId: UUID,
    val outletId: UUID? = null,
)

@NamedInterface("context")
fun interface OperationalSessionLookup {
    fun findActive(bearerToken: String): OperationalSessionPrincipal?
}
