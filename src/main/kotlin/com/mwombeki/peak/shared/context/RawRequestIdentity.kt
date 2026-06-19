package com.mwombeki.peak.shared.context

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("context")
data class RawRequestIdentity(
    val tenantId: UUID? = null,
    val tenantUserId: UUID? = null,
    val platformUserId: UUID? = null,
    val supportSessionId: UUID? = null,
    val supportTenantId: UUID? = null,
    val correlationId: String? = null,
) {
    fun validate(): RequestIdentity {
        val hasTenantIdentity = tenantId != null || tenantUserId != null
        val hasPlatformIdentity = platformUserId != null

        require(!(hasTenantIdentity && hasPlatformIdentity)) {
            "Mixed tenant and platform context is not allowed"
        }
        require(!(tenantUserId != null && tenantId == null)) {
            "Tenant user context requires tenant context"
        }
        require(!(tenantId != null && tenantUserId == null)) {
            "Tenant context requires tenant user context"
        }
        require(!(supportSessionId != null && platformUserId == null)) {
            "Support context requires platform identity"
        }
        require(!(supportTenantId != null && supportSessionId == null)) {
            "Support target tenant requires support context"
        }

        return when {
            supportSessionId != null && platformUserId != null -> RequestIdentity.Support(
                platformUserId = platformUserId,
                tenantId = requireNotNull(supportTenantId) {
                    "Support context requires target tenant"
                },
                supportSessionId = supportSessionId,
                correlationId = correlationId,
            )

            platformUserId != null -> RequestIdentity.Platform(
                platformUserId = platformUserId,
                correlationId = correlationId,
            )

            tenantId != null && tenantUserId != null -> RequestIdentity.Tenant(
                tenantId = tenantId,
                tenantUserId = tenantUserId,
                correlationId = correlationId,
            )

            else -> RequestIdentity.Public(
                correlationId = correlationId,
            )
        }
    }
}
