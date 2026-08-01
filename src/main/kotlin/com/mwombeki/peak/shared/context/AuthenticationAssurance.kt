package com.mwombeki.peak.shared.context

import java.time.Duration
import java.time.Instant
import org.springframework.modulith.NamedInterface

/**
 * Authentication strength actually achieved by the validated token.
 *
 * Ordered weakest to strongest so policy comparison is a simple ordinal test.
 */
@NamedInterface("context")
enum class AssuranceLevel {
    /** No proven authentication ceremony. */
    NONE,

    /** A second factor was used, but it is not proven phishing resistant. */
    MFA,

    /** A phishing-resistant authenticator such as WebAuthn with user verification. */
    PHISHING_RESISTANT,
    ;

    fun satisfies(required: AssuranceLevel): Boolean = ordinal >= required.ordinal

    companion object {
        /**
         * Parses a policy requirement. Unknown values are rejected rather than
         * silently downgraded, so a typo in a policy row cannot weaken a gate.
         */
        fun fromPolicy(value: String): AssuranceLevel = when (value.trim().lowercase()) {
            "mfa" -> MFA
            "phishing_resistant" -> PHISHING_RESISTANT
            else -> throw IllegalArgumentException(
                "Unsupported required assurance level: $value",
            )
        }
    }
}

/**
 * Evidence of the authentication ceremony behind the current request, derived
 * only from the validated token.
 *
 * Nothing here may be supplied by a request body or header. A privileged
 * operation asks this object what actually happened; it never asks the caller
 * what they claim happened.
 */
@NamedInterface("context")
data class AuthenticationAssurance(
    val level: AssuranceLevel = AssuranceLevel.NONE,
    val acr: String? = null,
    val amr: List<String> = emptyList(),
    val authTime: Instant? = null,
    val issuer: String? = null,
    val subject: String? = null,
) {
    /**
     * True when the authentication ceremony happened recently enough for a
     * step-up sensitive operation. Missing evidence is never fresh.
     */
    fun isFreshWithin(maxAge: Duration, now: Instant): Boolean {
        val establishedAt = authTime ?: return false
        if (establishedAt.isAfter(now.plus(CLOCK_SKEW))) {
            // A future authentication time is malformed evidence.
            return false
        }
        return !establishedAt.isBefore(now.minus(maxAge))
    }

    companion object {
        /** Tolerance for small clock differences between Keycloak and Peak. */
        val CLOCK_SKEW: Duration = Duration.ofSeconds(30)

        val UNAUTHENTICATED = AuthenticationAssurance()
    }
}
