package com.mwombeki.peak.onboarding.api

import org.springframework.modulith.NamedInterface

/**
 * An applicant's progress toward becoming a tenant. Illegal transitions are rejected here, not
 * just conventionally avoided by callers — a decided application (REJECTED,
 * TENANT_PROVISIONED) is terminal, and nothing walks it backward through review once a
 * document has been submitted.
 */
@NamedInterface("api")
enum class OnboardingStatus {
    DRAFT,
    PHONE_VERIFIED,
    IN_PROGRESS,
    SUBMITTED,
    UNDER_REVIEW,
    INFORMATION_REQUIRED,
    RESUBMITTED,
    VERIFIED,
    REJECTED,
    APPROVED,
    TENANT_PROVISIONED,
    ;

    fun canTransitionTo(next: OnboardingStatus): Boolean = next in (ALLOWED_TRANSITIONS[this] ?: emptySet())

    private companion object {
        val ALLOWED_TRANSITIONS: Map<OnboardingStatus, Set<OnboardingStatus>> = mapOf(
            DRAFT to setOf(PHONE_VERIFIED),
            PHONE_VERIFIED to setOf(IN_PROGRESS),
            IN_PROGRESS to setOf(SUBMITTED),
            SUBMITTED to setOf(UNDER_REVIEW),
            UNDER_REVIEW to setOf(VERIFIED, REJECTED, INFORMATION_REQUIRED),
            INFORMATION_REQUIRED to setOf(RESUBMITTED),
            RESUBMITTED to setOf(UNDER_REVIEW),
            VERIFIED to setOf(APPROVED),
            APPROVED to setOf(TENANT_PROVISIONED),
            REJECTED to emptySet(),
            TENANT_PROVISIONED to emptySet(),
        )
    }
}
