package com.mwombeki.peak.usermanagement.api

import java.time.Instant
import java.util.UUID

enum class TenantUserLifecycleAction(val databaseValue: String) {
    DISABLE("disable"),
    REACTIVATE("reactivate"),
    LOCK("lock"),
    UNLOCK("unlock"),
}

data class TenantUserLifecycleCommand(
    val tenantId: UUID,
    val userId: UUID,
    val action: TenantUserLifecycleAction,
)

data class RevokeTenantUserIdentityLinkCommand(
    val tenantId: UUID,
    val userId: UUID,
    val identityLinkId: UUID,
)

data class TenantUserLifecycleReceipt(
    val tenantId: UUID,
    val userId: UUID,
    val action: TenantUserLifecycleAction,
    val status: String,
    val isActive: Boolean,
    val lockedUntil: Instant?,
    val changed: Boolean,
    val replayed: Boolean,
)

data class TenantUserIdentityLinkRevocationReceipt(
    val tenantId: UUID,
    val userId: UUID,
    val identityLinkId: UUID,
    val revokedAt: Instant?,
    val changed: Boolean,
    val replayed: Boolean,
)

sealed class TenantUserLifecycleException(message: String) : RuntimeException(message)

class TenantUserLifecycleNotFoundException(
    message: String,
) : TenantUserLifecycleException(message)

class TenantUserLifecycleConflictException(
    message: String,
) : TenantUserLifecycleException(message)

class TenantUserLifecycleInProgressException(
    message: String,
) : TenantUserLifecycleException(message)
