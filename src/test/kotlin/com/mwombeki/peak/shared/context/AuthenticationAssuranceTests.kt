package com.mwombeki.peak.shared.context

import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Authentication assurance must come from the validated token and nothing else.
 *
 * The defect these cover: `assuranceLevel` was accepted from the request body
 * and corroborated only by `platform_users.mfa_enabled`, a column recording
 * that an operator once enrolled a second factor. Neither proves anything about
 * the ceremony behind the current request.
 */
class AuthenticationAssuranceTests {

    private val properties = RequestContextProperties(
        mfaAcrValues = setOf("mfa"),
        phishingResistantAcrValues = setOf("phishing-resistant"),
        phishingResistantAmrValues = setOf("hwk", "swk", "webauthn"),
    )

    private val resolver = RequestContextResolver(
        properties = properties,
        externalIdentityResolver = { null },
    )

    // ------------------------------------------------------- level ordering

    @Test
    fun `stronger assurance satisfies a weaker requirement`() {
        assertTrue(AssuranceLevel.PHISHING_RESISTANT.satisfies(AssuranceLevel.MFA))
        assertTrue(AssuranceLevel.MFA.satisfies(AssuranceLevel.MFA))
    }

    @Test
    fun `a second factor alone cannot satisfy a phishing resistant requirement`() {
        assertFalse(AssuranceLevel.MFA.satisfies(AssuranceLevel.PHISHING_RESISTANT))
        assertFalse(AssuranceLevel.NONE.satisfies(AssuranceLevel.MFA))
    }

    @Test
    fun `an unrecognised policy level is rejected rather than downgraded`() {
        assertThrows<IllegalArgumentException> { AssuranceLevel.fromPolicy("strong-ish") }
    }

    // ----------------------------------------------------------- freshness

    @Test
    fun `absent authentication time is never fresh`() {
        val evidence = AuthenticationAssurance(level = AssuranceLevel.PHISHING_RESISTANT)

        assertFalse(evidence.isFreshWithin(Duration.ofMinutes(5), Instant.now()))
    }

    @Test
    fun `stale authentication is not fresh`() {
        val now = Instant.now()
        val evidence = AuthenticationAssurance(
            level = AssuranceLevel.PHISHING_RESISTANT,
            authTime = now.minus(Duration.ofMinutes(30)),
        )

        assertFalse(evidence.isFreshWithin(Duration.ofMinutes(5), now))
    }

    @Test
    fun `recent authentication is fresh`() {
        val now = Instant.now()
        val evidence = AuthenticationAssurance(
            level = AssuranceLevel.PHISHING_RESISTANT,
            authTime = now.minus(Duration.ofMinutes(1)),
        )

        assertTrue(evidence.isFreshWithin(Duration.ofMinutes(5), now))
    }

    @Test
    fun `a future authentication time is malformed and not fresh`() {
        val now = Instant.now()
        val evidence = AuthenticationAssurance(
            level = AssuranceLevel.PHISHING_RESISTANT,
            authTime = now.plus(Duration.ofMinutes(10)),
        )

        assertFalse(
            evidence.isFreshWithin(Duration.ofMinutes(5), now),
            "a token claiming future authentication must not pass a freshness gate",
        )
    }

    // ------------------------------------------------------ token derivation

    @Test
    fun `a request without a token carries no assurance`() {
        val context = resolver.resolve(MockHttpServletRequest(), null)

        assertEquals(AssuranceLevel.NONE, context.authentication.level)
    }

    @Test
    fun `request body claims cannot manufacture assurance`() {
        // A caller asserting phishing resistance in content, with a token that
        // proves nothing, must still resolve to NONE.
        val request = MockHttpServletRequest()
        request.setContent("""{"assuranceLevel":"phishing_resistant"}""".toByteArray())
        request.addHeader("X-Assurance-Level", "phishing_resistant")

        val context = resolver.resolve(request, authenticationWith(mapOf("sub" to "operator")))

        assertEquals(AssuranceLevel.NONE, context.authentication.level)
    }

    @Test
    fun `an acr for a second factor resolves to mfa only`() {
        val context = resolver.resolve(
            MockHttpServletRequest(),
            authenticationWith(mapOf("sub" to "operator", "acr" to "mfa")),
        )

        assertEquals(AssuranceLevel.MFA, context.authentication.level)
        assertFalse(
            context.authentication.level.satisfies(AssuranceLevel.PHISHING_RESISTANT),
            "TOTP-grade authentication must not satisfy a phishing-resistant policy",
        )
    }

    @Test
    fun `a webauthn amr resolves to phishing resistant`() {
        val context = resolver.resolve(
            MockHttpServletRequest(),
            authenticationWith(
                mapOf("sub" to "operator", "acr" to "mfa", "amr" to listOf("pwd", "hwk")),
            ),
        )

        assertEquals(AssuranceLevel.PHISHING_RESISTANT, context.authentication.level)
    }

    @Test
    fun `a space delimited amr string is understood`() {
        val context = resolver.resolve(
            MockHttpServletRequest(),
            authenticationWith(mapOf("sub" to "operator", "amr" to "pwd webauthn")),
        )

        assertEquals(
            AssuranceLevel.PHISHING_RESISTANT,
            context.authentication.level,
            "assurance must not depend on how the provider encodes amr",
        )
    }

    @Test
    fun `issuer subject and authentication time are retained`() {
        val authTime = Instant.now().minusSeconds(30).epochSecond
        val context = resolver.resolve(
            MockHttpServletRequest(),
            authenticationWith(
                mapOf(
                    "sub" to "operator-1",
                    "iss" to "https://keycloak.example.test/realms/peak-platform",
                    "acr" to "mfa",
                    "auth_time" to authTime,
                ),
            ),
        )

        assertEquals("operator-1", context.authentication.subject)
        assertEquals(
            "https://keycloak.example.test/realms/peak-platform",
            context.authentication.issuer,
        )
        assertEquals(Instant.ofEpochSecond(authTime), context.authentication.authTime)
    }

    @Test
    fun `an unparseable authentication time is treated as absent`() {
        val context = resolver.resolve(
            MockHttpServletRequest(),
            authenticationWith(
                mapOf("sub" to "operator", "acr" to "mfa", "auth_time" to "not-a-time"),
            ),
        )

        assertEquals(null, context.authentication.authTime)
        assertFalse(context.authentication.isFreshWithin(Duration.ofMinutes(5), Instant.now()))
    }

    /**
     * Identity resolution requires `iss` and `sub`, so every token carries them
     * unless a case overrides them deliberately.
     */
    private fun authenticationWith(claims: Map<String, Any>): JwtAuthenticationToken {
        val builder = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .issuedAt(Instant.now().minusSeconds(60))
            .expiresAt(Instant.now().plusSeconds(600))
        val complete = mapOf("iss" to DEFAULT_ISSUER, "sub" to "operator") + claims
        complete.forEach { (name, value) -> builder.claim(name, value) }
        return JwtAuthenticationToken(builder.build(), emptyList())
    }

    private companion object {
        const val DEFAULT_ISSUER = "https://keycloak.example.test/realms/peak-platform"
    }
}
