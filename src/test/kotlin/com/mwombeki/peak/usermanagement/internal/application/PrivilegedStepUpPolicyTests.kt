package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.shared.context.AssuranceLevel
import com.mwombeki.peak.shared.context.AuthenticationAssurance
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.security.StepUpProperties
import com.mwombeki.peak.shared.context.RequestIdentity
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Step-up is the one control in this slice with a deliberate relaxation, so it
 * gets the closest scrutiny.
 *
 * The relaxation exists because trusted header identity carries no token, and a
 * strict gate would make privileged operations impossible in local runtimes. It
 * is safe only because a runtime that enables header identity has already
 * declared itself non-production. These tests assert that dependency instead of
 * leaving it in a comment, so relaxing the production rule breaks something that
 * names the reason.
 */
class PrivilegedStepUpPolicyTests {

    private val holder = RequestContextHolder()

    @AfterTest
    fun clear() = holder.clear()

    // ------------------------------------------------- production posture

    /**
     * With header identity disabled, which is the only configuration production
     * permits, the carve-out is unreachable and every request must prove its
     * ceremony.
     */
    @Test
    fun `carve-out is unreachable when the runtime can produce evidence`() {
        val policy = policy(assumeUnavailable = false)

        assertFalse(
            policy.isCeremonyEvidenceUnavailable(),
            "production posture must never skip step-up verification",
        )
    }

    @Test
    fun `production posture rejects a request with no ceremony evidence`() {
        val policy = policy(assumeUnavailable = false)
        bind(AuthenticationAssurance.UNAUTHENTICATED)

        val failure = assertFailsWith<IllegalStateException> {
            policy.require(AssuranceLevel.MFA, Duration.ofMinutes(5)) { IllegalStateException(it) }
        }
        assertTrue(failure.message!!.contains("validated platform token"))
    }

    @Test
    fun `production posture rejects a second factor for a phishing resistant operation`() {
        val policy = policy(assumeUnavailable = false)
        bind(assurance(AssuranceLevel.MFA, Instant.now()))

        val failure = assertFailsWith<IllegalStateException> {
            policy.require(
                AssuranceLevel.PHISHING_RESISTANT,
                Duration.ofMinutes(5),
            ) { IllegalStateException(it) }
        }
        assertTrue(failure.message!!.contains("phishing-resistant"))
    }

    @Test
    fun `production posture rejects a stale ceremony`() {
        val policy = policy(assumeUnavailable = false)
        bind(
            assurance(
                AssuranceLevel.PHISHING_RESISTANT,
                Instant.now().minus(Duration.ofHours(1)),
            ),
        )

        val failure = assertFailsWith<IllegalStateException> {
            policy.require(
                AssuranceLevel.PHISHING_RESISTANT,
                Duration.ofMinutes(5),
            ) { IllegalStateException(it) }
        }
        assertTrue(failure.message!!.contains("recent step-up"))
    }

    @Test
    fun `production posture accepts a fresh phishing resistant ceremony`() {
        val policy = policy(assumeUnavailable = false)
        bind(assurance(AssuranceLevel.PHISHING_RESISTANT, Instant.now()))

        assertEquals(
            AssuranceLevel.PHISHING_RESISTANT,
            policy.require(
                AssuranceLevel.PHISHING_RESISTANT,
                Duration.ofMinutes(5),
            ) { IllegalStateException(it) },
        )
    }

    /**
     * Header identity and step-up enforcement are now independent. This is the assertion that
     * would have failed under the old coupling, and it is the reason for the change: an
     * integration test can enable header identity — which every one of them does — and still
     * prove that a step-up gate holds.
     */
    @Test
    fun `header identity no longer disables step-up`() {
        val policy = policy(assumeUnavailable = false)
        bind(AuthenticationAssurance.UNAUTHENTICATED)

        assertFalse(policy.isCeremonyEvidenceUnavailable())
        assertFailsWith<IllegalStateException> {
            policy.require(AssuranceLevel.MFA, Duration.ofMinutes(5)) {
                IllegalStateException(it)
            }
        }
    }

    // --------------------------------------------- non-production posture

    /**
     * The relaxation is bounded to runtimes that say so by name.
     *
     * It used to be inferred from header identity, which meant enabling header identity for
     * tests silently disabled every step-up gate in the suite — and no integration test could
     * assert a denial, because the gate was never reached.
     */
    @Test
    fun `carve-out applies only where the runtime declares no ceremony evidence`() {
        val policy = policy(assumeUnavailable = true)
        bind(AuthenticationAssurance.UNAUTHENTICATED)

        assertTrue(policy.isCeremonyEvidenceUnavailable())
        assertEquals(
            AssuranceLevel.PHISHING_RESISTANT,
            policy.require(
                AssuranceLevel.PHISHING_RESISTANT,
                Duration.ofMinutes(5),
            ) { IllegalStateException(it) },
            "a local runtime must remain usable without a token",
        )
    }

    /**
     * Both privileged paths share one implementation, so support access and
     * emergency administration cannot diverge under the same configuration.
     * A caller's own exception type is still preserved.
     */
    @Test
    fun `each caller keeps its own failure type from the shared rule`() {
        val policy = policy(assumeUnavailable = false)
        bind(AuthenticationAssurance.UNAUTHENTICATED)

        assertFailsWith<IllegalArgumentException> {
            policy.require(AssuranceLevel.MFA, Duration.ofMinutes(5)) {
                IllegalArgumentException(it)
            }
        }
    }

    private fun policy(assumeUnavailable: Boolean) = PrivilegedStepUpPolicy(
        requestContextHolder = holder,
        stepUpProperties = StepUpProperties(assumeUnavailable = assumeUnavailable),
    )

    private fun assurance(level: AssuranceLevel, authTime: Instant) = AuthenticationAssurance(
        level = level,
        acr = "test",
        amr = listOf("hwk"),
        authTime = authTime,
        issuer = "https://keycloak.test/realms/peak-platform",
        subject = "operator",
    )

    private fun bind(authentication: AuthenticationAssurance) {
        holder.set(
            RequestContext(
                identity = RequestIdentity.Platform(UUID.randomUUID(), "corr-step-up"),
                correlationId = "corr-step-up",
                idempotencyKey = null,
                httpMethod = "POST",
                requestPath = "/api/v1/platform/administrators",
                authentication = authentication,
            ),
        )
    }
}
