package com.mwombeki.peak.shared.context

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("context")
data class ExternalIdentityPrincipal(
    val provider: String = "oidc",
    val issuer: String,
    val subject: String,
    val email: String? = null,
)

@NamedInterface("context")
sealed interface ResolvedExternalIdentity {
    @NamedInterface("context")
    data class Tenant(
        val tenantId: UUID,
        val tenantUserId: UUID,
    ) : ResolvedExternalIdentity

    @NamedInterface("context")
    data class Platform(
        val platformUserId: UUID,
    ) : ResolvedExternalIdentity
}

@NamedInterface("context")
fun interface ExternalIdentityResolver {
    fun resolve(principal: ExternalIdentityPrincipal): ResolvedExternalIdentity?
}
