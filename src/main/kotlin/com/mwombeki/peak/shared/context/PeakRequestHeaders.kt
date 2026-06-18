package com.mwombeki.peak.shared.context

import org.springframework.modulith.NamedInterface

@NamedInterface("context")
object PeakRequestHeaders {
    const val CORRELATION_ID = "X-Correlation-Id"
    const val IDEMPOTENCY_KEY = "Idempotency-Key"

    const val TENANT_ID = "X-Peak-Tenant-Id"
    const val TENANT_USER_ID = "X-Peak-Tenant-User-Id"
    const val PLATFORM_USER_ID = "X-Peak-Platform-User-Id"
    const val SUPPORT_SESSION_ID = "X-Peak-Support-Session-Id"
    const val SUPPORT_TENANT_ID = "X-Peak-Support-Tenant-Id"
    const val PUBLIC_TENANT_ID = "X-Peak-Public-Tenant-Id"
    const val PUBLIC_PROPERTY_ID = "X-Peak-Public-Property-Id"

    val IDENTITY_HEADERS = setOf(
        TENANT_ID,
        TENANT_USER_ID,
        PLATFORM_USER_ID,
        SUPPORT_SESSION_ID,
        SUPPORT_TENANT_ID,
        PUBLIC_TENANT_ID,
        PUBLIC_PROPERTY_ID,
    )
}
