package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.shared.context.AssuranceLevel
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestContextProperties
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
    private val requestContextProperties: RequestContextProperties,
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
     * Trusted header identity carries no token, so a strict gate would make
     * privileged operations impossible in local and controlled runtimes. This
     * is safe only because a runtime enabling header identity has already
     * declared itself non-production: `ProductionReadinessValidator` fails
     * startup when `peak.security.request-context.allow-header-identity` is
     * true under `prod`.
     *
     * That coupling is asserted by `PrivilegedStepUpPolicyTests` together with
     * the production validator's own rejection test, so relaxing the production
     * rule breaks a test that names this dependency rather than silently
     * opening a bypass.
     */
    fun isCeremonyEvidenceUnavailable(): Boolean =
        requestContextProperties.allowHeaderIdentity
}
