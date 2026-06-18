package com.mwombeki.peak.shared.context

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("context")
enum class RequestIdentityMode {
    TENANT,
    PLATFORM,
    PUBLIC,
    SUPPORT,
}

@NamedInterface("context")
sealed interface RequestIdentity {
    val mode: RequestIdentityMode
    val correlationId: String?

    @NamedInterface("context")
    data class Tenant(
        val tenantId: UUID,
        val tenantUserId: UUID,
        override val correlationId: String? = null,
    ) : RequestIdentity {
        override val mode = RequestIdentityMode.TENANT
    }

    @NamedInterface("context")
    data class Platform(
        val platformUserId: UUID,
        override val correlationId: String? = null,
    ) : RequestIdentity {
        override val mode = RequestIdentityMode.PLATFORM
    }

    @NamedInterface("context")
    data class Public(
        val tenantId: UUID? = null,
        val propertyId: UUID? = null,
        override val correlationId: String? = null,
    ) : RequestIdentity {
        override val mode = RequestIdentityMode.PUBLIC
    }

    @NamedInterface("context")
    data class Support(
        val platformUserId: UUID,
        val tenantId: UUID,
        val supportSessionId: UUID,
        override val correlationId: String? = null,
    ) : RequestIdentity {
        override val mode = RequestIdentityMode.SUPPORT
    }
}
