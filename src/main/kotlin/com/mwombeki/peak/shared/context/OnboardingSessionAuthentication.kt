package com.mwombeki.peak.shared.context

import java.util.UUID
import org.springframework.modulith.NamedInterface
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority

/**
 * A pre-tenant onboarding applicant session. The bearer is minted by Peak once a phone
 * number is verified against a draft application, never a JWT — like
 * [OperationalSessionAuthentication], [org.springframework.security.oauth2.jwt.JwtDecoder]
 * must not see one. It carries no tenant or platform authority, only the single
 * applicationId it was issued for; [RequestContextResolver] maps it to
 * [RequestIdentity.OnboardingApplicant].
 */
@NamedInterface("context")
class OnboardingSessionAuthentication(
    val sessionId: UUID,
    val applicationId: UUID,
) : Authentication {

    private val grantedAuthorities: MutableCollection<GrantedAuthority> =
        java.util.Collections.emptyList()

    override fun getAuthorities(): MutableCollection<out GrantedAuthority> = grantedAuthorities
    override fun getCredentials(): Any = Unit
    override fun getDetails(): Any = Unit
    override fun getPrincipal(): Any = this
    override fun isAuthenticated(): Boolean = true
    override fun setAuthenticated(isAuthenticated: Boolean) = Unit
    override fun getName(): String = applicationId.toString()

    companion object {
        const val TOKEN_PREFIX = "onb_"
    }
}

@NamedInterface("context")
data class OnboardingSessionPrincipal(
    val sessionId: UUID,
    val applicationId: UUID,
)

@NamedInterface("context")
fun interface OnboardingSessionLookup {
    fun findActive(bearerToken: String): OnboardingSessionPrincipal?
}
