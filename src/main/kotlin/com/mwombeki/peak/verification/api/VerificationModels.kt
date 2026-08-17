package com.mwombeki.peak.verification.api

import java.time.Instant
import java.util.UUID
import org.springframework.modulith.NamedInterface

/**
 * A challenge for one purpose can never satisfy another — a code sent to verify a phone number
 * during public onboarding must not double as a tenant-activation code, even for the same
 * destination.
 */
@NamedInterface("api")
enum class VerificationPurpose(val code: String) {
    PHONE_VERIFICATION("phone_verification"),
    TENANT_ACTIVATION("tenant_activation"),
    ACCOUNT_ACTIVATION("account_activation"),
    ACCOUNT_RECOVERY("account_recovery"),
    GUEST_PHONE_VERIFICATION("guest_phone_verification"),
}

@NamedInterface("api")
data class RequestVerificationCommand(
    val purpose: VerificationPurpose,
    /** Phone number (E.164) or email — whatever this purpose verifies. */
    val destination: String,
    /** Opaque, purpose-interpreted (an application id, a user id, ...). Never used by this module itself. */
    val subjectRef: String? = null,
    val tenantId: UUID? = null,
    /** Caller's source address, for [com.mwombeki.peak.shared.ephemeral.RateLimitScope.REQUESTS_PER_IP]. */
    val sourceIp: String? = null,
)

/**
 * [code] is returned once, at issuance — the same shape as this codebase's staff activation
 * secret. The caller needs the plaintext exactly once, to deliver it (SMS today), and this
 * module never returns or stores it in recoverable form again after this.
 */
@NamedInterface("api")
data class VerificationChallengeReceipt(
    val id: UUID,
    val code: String,
    val expiresAt: Instant,
)

@NamedInterface("api")
data class ConfirmVerificationCommand(
    val purpose: VerificationPurpose,
    val destination: String,
    val code: String,
)

@NamedInterface("api")
data class VerificationOutcome(
    val verified: Boolean,
    val subjectRef: String?,
)

@NamedInterface("api")
class VerificationThrottledException(message: String) : RuntimeException(message)
