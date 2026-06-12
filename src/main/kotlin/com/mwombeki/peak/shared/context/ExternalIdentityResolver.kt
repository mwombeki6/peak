package com.mwombeki.peak.shared.context

import java.util.UUID

data class ExternalIdentityPrincipal(
    val provider: String = "oidc",
    val issuer: String,
    val subject: String,
    val email: String? = null,
)

sealed interface ResolvedExternalIdentity {
    data class Tenant(
        val tenantId: UUID,
        val tenantUserId: UUID,
    ) : ResolvedExternalIdentity

    data class Platform(
        val platformUserId: UUID,
    ) : ResolvedExternalIdentity
}

fun interface ExternalIdentityResolver {
    fun resolve(principal: ExternalIdentityPrincipal): ResolvedExternalIdentity?
}
