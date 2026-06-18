package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID

internal data class PublicRequestScope(
    val tenantId: UUID,
    val propertyId: UUID,
)

internal fun RequestContext.requirePublicScope(): PublicRequestScope {
    val publicIdentity = identity as? RequestIdentity.Public
        ?: throw IllegalArgumentException("Public tenant/property context is required")

    return PublicRequestScope(
        tenantId = publicIdentity.tenantId
            ?: throw IllegalArgumentException("Public tenant context is required"),
        propertyId = publicIdentity.propertyId
            ?: throw IllegalArgumentException("Public property context is required"),
    )
}
