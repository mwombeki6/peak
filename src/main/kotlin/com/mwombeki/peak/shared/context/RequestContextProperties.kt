package com.mwombeki.peak.shared.context

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.modulith.NamedInterface

@NamedInterface("context")
@ConfigurationProperties(prefix = "peak.security.request-context")
data class RequestContextProperties(
    val allowHeaderIdentity: Boolean = false,
    val allowTrustedJwtIdentityClaims: Boolean = false,
    /**
     * Authentication-context-class values the identity provider emits for a
     * second factor. Configured rather than hard-coded because ACR values are
     * a deployment contract with Keycloak, not a property of Peak.
     */
    val mfaAcrValues: Set<String> = setOf("mfa"),
    /**
     * ACR values that prove a phishing-resistant ceremony.
     */
    val phishingResistantAcrValues: Set<String> = setOf("phishing-resistant"),
    /**
     * Authentication-method references that prove a phishing-resistant
     * authenticator. WebAuthn hardware and software keys report `hwk`/`swk`.
     */
    val phishingResistantAmrValues: Set<String> = setOf("hwk", "swk", "webauthn"),
)
