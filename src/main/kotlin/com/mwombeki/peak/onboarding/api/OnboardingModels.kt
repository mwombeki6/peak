package com.mwombeki.peak.onboarding.api

import java.time.Instant
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
data class RequestAccessCommand(
    val representativeFullName: String,
    val representativePhone: String,
    val businessName: String?,
    val countryCode: String = "TZ",
)

@NamedInterface("api")
data class RequestAccessReceipt(
    val applicationId: UUID,
)

@NamedInterface("api")
data class VerifyOnboardingPhoneCommand(
    val applicationId: UUID,
    val code: String,
)

/** [token] is returned once, at issuance — never stored or returned again after this. */
@NamedInterface("api")
data class OnboardingSessionReceipt(
    val token: String,
    val expiresAt: Instant,
)

@NamedInterface("api")
class OnboardingVerificationFailedException(message: String) : RuntimeException(message)
