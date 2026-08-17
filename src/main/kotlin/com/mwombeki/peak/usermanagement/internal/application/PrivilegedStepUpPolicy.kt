package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.shared.context.AssuranceLevel
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.security.StepUpProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Component

/**
 * The single decision point for whether the current request carries a strong
 * enough, recent enough authentication ceremony to change privileged state.
 *
 * This exists as one component rather than a method on each service because
 * privileged support access and emergency administration previously enforced
 * step-up separately. Two implementations of the same rule drift, and the
 * drift is invisible: each looks correct in isolation while the pair behaves
 * differently under identical configuration. Whichever one a reader inspects
 * becomes the one they believe.
 */
@Component
class PrivilegedStepUpPolicy(
    private val requestContextHolder: RequestContextHolder,
    private val stepUpProperties: StepUpProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Verifies the ceremony behind this request and returns the level achieved.
     *
     * @param required minimum assurance the operation demands
     * @param maxAge how recently the ceremony must have happened
     * @param onFailure builds the domain exception a caller wants to surface,
     *   so each service keeps its own error contract while sharing the rule
     */
    fun require(
        required: AssuranceLevel,
        maxAge: Duration,
        onFailure: (String) -> RuntimeException,
    ): AssuranceLevel {
        if (isCeremonyEvidenceUnavailable()) {
            return required
        }

        val evidence = requestContextHolder.current().authentication

        if (evidence.issuer.isNullOrBlank() || evidence.subject.isNullOrBlank()) {
            throw onFailure("Privileged operations require a validated platform token")
        }
        if (evidence.level == AssuranceLevel.NONE) {
            throw onFailure("Privileged operations require proven multi-factor authentication")
        }
        if (!evidence.level.satisfies(required)) {
            throw onFailure("Privileged operations require phishing-resistant authentication")
        }
        if (!evidence.isFreshWithin(maxAge, Instant.now(clock))) {
            throw onFailure("Privileged operations require a recent step-up authentication")
        }
        return evidence.level
    }

    /**
     * True when the runtime cannot produce ceremony evidence at all.
     *
     * A runtime without an identity provider carries no token, so a strict gate would make
     * privileged operations impossible locally. Safe only because the runtime has declared
     * itself non-production: `ProductionReadinessValidator` refuses to start `prod` with this
     * enabled.
     *
     * This was previously inferred from `allow-header-identity`, which meant one boolean
     * governed two unrelated questions — may a caller assert its own identity, and is step-up
     * enforced. The cost was not theoretical: every integration test runs with header identity
     * on, so every step-up gate was skipped, and no integration test anywhere could assert a
     * step-up denial. The policy's own unit tests proved the rule in isolation; nothing proved
     * it was wired into the services that depend on it.
     *
     * Separating them lets an integration test turn enforcement on and check the wiring, which
     * is the only way to find out that a gate was never called.
     */
    fun isCeremonyEvidenceUnavailable(): Boolean = stepUpProperties.assumeUnavailable
}
