package com.mwombeki.peak.shared.context

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("context")
enum class RequestIdentityMode {
    TENANT,
    PLATFORM,
    PUBLIC,
    SUPPORT,
    ONBOARDING_APPLICANT,
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

    /**
     * A prospect, not a tenant. Bound to exactly one [applicationId] and nothing else — no
     * tenant_id, no property, no staff permission. This is deliberately not [Public] with an
     * optional tenant id: [Public] means "no strong identity yet, treat carefully"; this means
     * "a specific, narrow, already-authenticated scope that must never widen to tenant
     * authority," which `assert_no_mixed_context()` also enforces at the database.
     */
    @NamedInterface("context")
    data class OnboardingApplicant(
        val applicationId: UUID,
        override val correlationId: String? = null,
    ) : RequestIdentity {
        override val mode = RequestIdentityMode.ONBOARDING_APPLICANT
    }
}
