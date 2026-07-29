package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.shared.context.AssuranceLevel
import com.mwombeki.peak.shared.context.AuthenticationAssurance
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestContextProperties
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
    fun `carve-out is unreachable when header identity is disabled`() {
        val policy = policy(allowHeaderIdentity = false)

        assertFalse(
            policy.isCeremonyEvidenceUnavailable(),
            "production posture must never skip step-up verification",
        )
    }

    @Test
    fun `production posture rejects a request with no ceremony evidence`() {
        val policy = policy(allowHeaderIdentity = false)
        bind(AuthenticationAssurance.UNAUTHENTICATED)

        val failure = assertFailsWith<IllegalStateException> {
            policy.require(AssuranceLevel.MFA, Duration.ofMinutes(5)) { IllegalStateException(it) }
        }
        assertTrue(failure.message!!.contains("validated platform token"))
    }

    @Test
    fun `production posture rejects a second factor for a phishing resistant operation`() {
        val policy = policy(allowHeaderIdentity = false)
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
        val policy = policy(allowHeaderIdentity = false)
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
        val policy = policy(allowHeaderIdentity = false)
        bind(assurance(AssuranceLevel.PHISHING_RESISTANT, Instant.now()))

        assertEquals(
            AssuranceLevel.PHISHING_RESISTANT,
            policy.require(
                AssuranceLevel.PHISHING_RESISTANT,
                Duration.ofMinutes(5),
            ) { IllegalStateException(it) },
        )
    }

    // --------------------------------------------- non-production posture

    /**
     * The relaxation is bounded to runtimes that cannot produce evidence at all,
     * and it is the enabling of header identity that marks such a runtime.
     */
    @Test
    fun `carve-out applies only where header identity is enabled`() {
        val policy = policy(allowHeaderIdentity = true)
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
        val policy = policy(allowHeaderIdentity = false)
        bind(AuthenticationAssurance.UNAUTHENTICATED)

        assertFailsWith<IllegalArgumentException> {
            policy.require(AssuranceLevel.MFA, Duration.ofMinutes(5)) {
                IllegalArgumentException(it)
            }
        }
    }

    private fun policy(allowHeaderIdentity: Boolean) = PrivilegedStepUpPolicy(
        requestContextHolder = holder,
        requestContextProperties = RequestContextProperties(
            allowHeaderIdentity = allowHeaderIdentity,
        ),
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
