package com.mwombeki.peak.shared.context

import org.springframework.modulith.NamedInterface

/**
 * How a session was established, which is not the same question as how strongly it
 * authenticated.
 *
 * [AuthenticationAssurance] answers "was an MFA ceremony performed, and how recently", read
 * from the token's `acr`/`amr`. A manager who signs into Keycloak with a password and no second
 * factor sits at [AssuranceLevel.NONE] there — entirely normal, and no reason to refuse them a
 * rate change.
 *
 * This answers something else: did the credential come from Keycloak, or from a staff PIN typed
 * on a registered device. The distinction matters because a six-digit PIN is acceptable only
 * inside a device context and only for operational work, while the same person over Keycloak
 * may do considerably more.
 *
 * Reusing the assurance ladder for this was the obvious idea and it does not work. Placing
 * `OPERATIONAL` above `NONE` would rank a waiter's PIN above that manager's password, which is
 * false; and deny-by-default on that ladder would refuse every existing user on the day it
 * shipped, since almost none of them carry an MFA claim. The two are independent, and a
 * password-only session being `STRONG` class with `NONE` assurance is a state that has to
 * remain representable.
 */
@NamedInterface("context")
enum class SessionClass {
    /**
     * A staff PIN presented on a registered device. Bounded to operational work by
     * `permission_catalog.minimum_session_class`, so the blast radius of a compromised PIN is
     * set by role rather than by hoping six digits is hard to guess.
     */
    OPERATIONAL,

    /** Established through Keycloak. Every session Peak has issued until now. */
    STRONG,
    ;

    fun satisfies(required: SessionClass): Boolean = ordinal >= required.ordinal

    companion object {
        /**
         * Parses a stored requirement. An unrecognised value is rejected rather than treated as
         * the weakest requirement — silently downgrading is how a typo in a policy row becomes
         * an authorization bypass that nothing reports.
         */
        fun fromPolicy(value: String): SessionClass = when (value.trim().lowercase()) {
            "operational" -> OPERATIONAL
            "strong" -> STRONG
            else -> throw IllegalArgumentException(
                "Unsupported minimum session class: '$value'",
            )
        }
    }
}
