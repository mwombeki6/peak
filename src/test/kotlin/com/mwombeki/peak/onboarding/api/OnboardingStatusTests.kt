package com.mwombeki.peak.onboarding.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingStatusTests {

    @Test
    fun theHappyPathIsAllLegal() {
        val path = listOf(
            OnboardingStatus.DRAFT,
            OnboardingStatus.PHONE_VERIFIED,
            OnboardingStatus.IN_PROGRESS,
            OnboardingStatus.SUBMITTED,
            OnboardingStatus.UNDER_REVIEW,
            OnboardingStatus.VERIFIED,
            OnboardingStatus.APPROVED,
            OnboardingStatus.TENANT_PROVISIONED,
        )
        path.zipWithNext().forEach { (from, to) ->
            assertTrue(from.canTransitionTo(to), "expected $from -> $to to be legal")
        }
    }

    @Test
    fun theInformationRequestedLoopIsLegal() {
        assertTrue(OnboardingStatus.UNDER_REVIEW.canTransitionTo(OnboardingStatus.INFORMATION_REQUIRED))
        assertTrue(OnboardingStatus.INFORMATION_REQUIRED.canTransitionTo(OnboardingStatus.RESUBMITTED))
        assertTrue(OnboardingStatus.RESUBMITTED.canTransitionTo(OnboardingStatus.UNDER_REVIEW))
    }

    @Test
    fun rejectionIsLegalFromUnderReviewAndTerminal() {
        assertTrue(OnboardingStatus.UNDER_REVIEW.canTransitionTo(OnboardingStatus.REJECTED))
        OnboardingStatus.entries.forEach { candidate ->
            assertFalse(
                OnboardingStatus.REJECTED.canTransitionTo(candidate),
                "REJECTED must be terminal, but allows a transition to $candidate",
            )
        }
    }

    @Test
    fun tenantProvisionedIsTerminal() {
        OnboardingStatus.entries.forEach { candidate ->
            assertFalse(
                OnboardingStatus.TENANT_PROVISIONED.canTransitionTo(candidate),
                "TENANT_PROVISIONED must be terminal, but allows a transition to $candidate",
            )
        }
    }

    @Test
    fun anApprovedApplicationCannotBeReplayedBackThroughReview() {
        assertFalse(OnboardingStatus.APPROVED.canTransitionTo(OnboardingStatus.UNDER_REVIEW))
        assertFalse(OnboardingStatus.APPROVED.canTransitionTo(OnboardingStatus.REJECTED))
    }

    @Test
    fun draftCannotSkipPhoneVerification() {
        assertFalse(OnboardingStatus.DRAFT.canTransitionTo(OnboardingStatus.IN_PROGRESS))
        assertFalse(OnboardingStatus.DRAFT.canTransitionTo(OnboardingStatus.SUBMITTED))
    }

    @Test
    fun aSubmittedApplicationCannotBeEditedBackToInProgress() {
        assertFalse(OnboardingStatus.SUBMITTED.canTransitionTo(OnboardingStatus.IN_PROGRESS))
        assertFalse(OnboardingStatus.UNDER_REVIEW.canTransitionTo(OnboardingStatus.IN_PROGRESS))
    }

    @Test
    fun everyNonTerminalStatusHasAtLeastOneLegalNextStatus() {
        val terminal = setOf(OnboardingStatus.REJECTED, OnboardingStatus.TENANT_PROVISIONED)
        OnboardingStatus.entries.filterNot { it in terminal }.forEach { status ->
            assertTrue(
                OnboardingStatus.entries.any { status.canTransitionTo(it) },
                "$status is not terminal but has no legal transitions — it's a dead end",
            )
        }
    }
}
