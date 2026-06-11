package com.mwombeki.peak.shared.context

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RawRequestIdentityTests {

    @Test
    fun acceptsTenantIdentity() {
        val tenantId = UUID.randomUUID()
        val tenantUserId = UUID.randomUUID()

        val identity = RawRequestIdentity(
            tenantId = tenantId,
            tenantUserId = tenantUserId,
        ).validate()

        assertEquals(RequestIdentity.Tenant(tenantId, tenantUserId), identity)
    }

    @Test
    fun rejectsMixedTenantAndPlatformIdentity() {
        val error = assertFailsWith<IllegalArgumentException> {
            RawRequestIdentity(
                tenantId = UUID.randomUUID(),
                tenantUserId = UUID.randomUUID(),
                platformUserId = UUID.randomUUID(),
            ).validate()
        }

        assertEquals("Mixed tenant and platform context is not allowed", error.message)
    }

    @Test
    fun rejectsTenantUserWithoutTenant() {
        val error = assertFailsWith<IllegalArgumentException> {
            RawRequestIdentity(
                tenantUserId = UUID.randomUUID(),
            ).validate()
        }

        assertEquals("Tenant user context requires tenant context", error.message)
    }

    @Test
    fun acceptsSupportIdentityWithExplicitTargetTenant() {
        val platformUserId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val supportSessionId = UUID.randomUUID()

        val identity = RawRequestIdentity(
            platformUserId = platformUserId,
            supportSessionId = supportSessionId,
            supportTenantId = tenantId,
        ).validate()

        assertEquals(
            RequestIdentity.Support(platformUserId, tenantId, supportSessionId),
            identity,
        )
    }

    @Test
    fun rejectsPublicScopeCombinedWithStaffIdentity() {
        val error = assertFailsWith<IllegalArgumentException> {
            RawRequestIdentity(
                tenantId = UUID.randomUUID(),
                tenantUserId = UUID.randomUUID(),
                publicTenantId = UUID.randomUUID(),
            ).validate()
        }

        assertEquals(
            "Public request scope cannot be combined with staff or platform identity",
            error.message,
        )
    }
}
