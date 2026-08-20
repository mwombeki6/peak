package com.mwombeki.peak.usermanagement.api

import java.time.Duration
import java.time.Instant
import java.util.UUID

data class InviteTenantUserCommand(
    val tenantId: UUID,
    val email: String? = null,
    val phoneNumber: String? = null,
    val tenantRoleId: UUID,
    val fullName: String? = null,
    val expiresIn: Duration = Duration.ofHours(72),
    val metadata: Map<String, Any?> = emptyMap(),
) {
    init {
        require(email != null || phoneNumber != null) {
            "An invitation needs a phone number or an email"
        }
        require(expiresIn.toSeconds() > 0) {
            "Invitation expiry must be positive"
        }
    }
}

data class TenantUserInvitationReceipt(
    val invitationId: UUID,
    val tenantId: UUID,
    val email: String?,
    val phoneNumber: String? = null,
    val tenantRoleId: UUID,
    val expiresAt: Instant,
    val invitationToken: String?,
    val replayed: Boolean,
)

data class AcceptTenantUserInvitationCommand(
    val invitationToken: String,
    val issuer: String,
    val subject: String,
    val email: String? = null,
    val fullName: String? = null,
) {
    init {
        require(invitationToken.isNotBlank()) {
            "Invitation token is required"
        }
        require(issuer.isNotBlank()) {
            "OIDC issuer is required"
        }
        require(subject.isNotBlank()) {
            "OIDC subject is required"
        }
    }
}

data class TenantUserInvitationAcceptanceReceipt(
    val invitationId: UUID,
    val tenantId: UUID,
    val userId: UUID,
    val tenantRoleId: UUID,
    val email: String,
    val identityLinkId: UUID,
    val replayed: Boolean,
)

sealed class TenantUserInvitationException(message: String) : RuntimeException(message)

class TenantUserInvitationConflictException(
    message: String,
) : TenantUserInvitationException(message)

class TenantUserInvitationInProgressException(
    message: String,
) : TenantUserInvitationException(message)

class TenantUserInvitationAcceptanceRejectedException(
    message: String,
) : TenantUserInvitationException(message)
