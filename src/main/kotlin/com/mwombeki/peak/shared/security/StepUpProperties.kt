package com.mwombeki.peak.shared.security

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.modulith.NamedInterface

/**
 * Whether this runtime can produce authentication-ceremony evidence at all.
 *
 * A runtime with no identity provider carries no token, so a strict step-up gate would make
 * privileged operations impossible locally. This is its own switch rather than a consequence
 * of `peak.security.request-context.allow-header-identity`, which is what it used to be.
 *
 * One boolean answered two unrelated questions — may a caller assert its own identity, and is
 * step-up enforced — and the cost was concrete: every integration test runs with header
 * identity on, so every step-up gate was skipped, and no integration test could assert a
 * step-up denial. The policy's unit tests proved the rule in isolation while nothing proved it
 * was wired into the services depending on it.
 *
 * Lives in `shared` rather than beside the policy because `ProductionReadinessValidator` reads
 * it, and `shared` must not depend on a module's internals.
 *
 * Refused in production by [com.mwombeki.peak.shared.config.ProductionReadinessValidator].
 */
@NamedInterface("security")
@ConfigurationProperties(prefix = "peak.security.step-up")
data class StepUpProperties(
    val assumeUnavailable: Boolean = false,
)
