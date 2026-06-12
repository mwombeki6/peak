package com.mwombeki.peak.usermanagement.api

import java.time.Duration
import java.time.Instant
import java.util.UUID

data class InviteTenantUserCommand(
    val tenantId: UUID,
    val email: String,
    val tenantRoleId: UUID,
    val fullName: String? = null,
    val expiresIn: Duration = Duration.ofHours(72),
    val metadata: Map<String, Any?> = emptyMap(),
) {
    init {
        require(email.isNotBlank()) {
            "Invitation email is required"
        }
        require(expiresIn.toSeconds() > 0) {
            "Invitation expiry must be positive"
        }
    }
}

data class TenantUserInvitationReceipt(
    val invitationId: UUID,
    val tenantId: UUID,
    val email: String,
    val tenantRoleId: UUID,
    val expiresAt: Instant,
    val invitationToken: String?,
    val replayed: Boolean,
)

sealed class TenantUserInvitationException(message: String) : RuntimeException(message)

class TenantUserInvitationConflictException(
    message: String,
) : TenantUserInvitationException(message)

class TenantUserInvitationInProgressException(
    message: String,
) : TenantUserInvitationException(message)
