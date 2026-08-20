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

@NamedInterface("api")
data class UpdateOnboardingProfileCommand(
    val applicationId: UUID,
    val legalName: String,
    val businessEmail: String,
)

@NamedInterface("api")
class OnboardingProvisioningException(message: String) : RuntimeException(message)

/**
 * FBC's review queue row: an application joined to its most recent verification case, if it has
 * one yet. [caseId] is null for an application that hasn't created a case (still on the phone
 * step); [caseStatus] is the field the queue actually filters and sorts on, not
 * [applicationStatus] — onboarding_applications.status tracks the phone/provisioning
 * bookends, the case tracks the review lifecycle in between.
 */
@NamedInterface("api")
data class OnboardingApplicationQueueItem(
    val applicationId: UUID,
    val representativeFullName: String,
    val representativePhone: String,
    val businessName: String?,
    val applicationStatus: String,
    val caseId: UUID?,
    val caseStatus: String?,
    val caseSubmittedAt: Instant?,
    val createdAt: Instant,
)

@NamedInterface("api")
data class OnboardingApplicationDetail(
    val applicationId: UUID,
    val representativeFullName: String,
    val representativePhone: String,
    val businessName: String?,
    val legalName: String?,
    val businessEmail: String?,
    val countryCode: String,
    val status: String,
    val tenantId: UUID?,
    val createdAt: Instant,
)

@NamedInterface("api")
class OnboardingApplicationNotFoundException(message: String) : RuntimeException(message)
