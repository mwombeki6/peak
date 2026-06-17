package com.mwombeki.peak.shared.context

import java.util.UUID

enum class RequestIdentityMode {
    TENANT,
    PLATFORM,
    PUBLIC,
    SUPPORT,
}

sealed interface RequestIdentity {
    val mode: RequestIdentityMode
    val correlationId: String?

    data class Tenant(
        val tenantId: UUID,
        val tenantUserId: UUID,
        override val correlationId: String? = null,
    ) : RequestIdentity {
        override val mode = RequestIdentityMode.TENANT
    }

    data class Platform(
        val platformUserId: UUID,
        override val correlationId: String? = null,
    ) : RequestIdentity {
        override val mode = RequestIdentityMode.PLATFORM
    }

    data class Public(
        val tenantId: UUID? = null,
        val propertyId: UUID? = null,
        override val correlationId: String? = null,
    ) : RequestIdentity {
        override val mode = RequestIdentityMode.PUBLIC
    }

    data class Support(
        val platformUserId: UUID,
        val tenantId: UUID,
        val supportSessionId: UUID,
        override val correlationId: String? = null,
    ) : RequestIdentity {
        override val mode = RequestIdentityMode.SUPPORT
    }
}
