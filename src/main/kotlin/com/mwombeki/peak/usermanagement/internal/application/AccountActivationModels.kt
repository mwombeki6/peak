package com.mwombeki.peak.usermanagement.internal.application

import java.time.Instant

data class InvitationDetails(
    val inviteeName: String,
    val maskedEmail: String?,
    val maskedPhone: String? = null,
    val organisationName: String,
    val propertyName: String?,
    val expiresAt: Instant,
    val status: String,
    val allowedCredentials: List<String>,
)

data class CodeDispatch(
    val maskedEmail: String?,
    val resendAvailableInSeconds: Int,
    val expiresInSeconds: Int,
    val debugCode: String? = null,
)

data class SetupGrant(
    val setupGrant: String,
    val expiresInSeconds: Int,
)

data class CredentialAccepted(
    val signedIn: Boolean,
    val redirectTo: String?,
)
