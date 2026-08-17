package com.mwombeki.peak.shared.context

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a session was established, which is a different question from how strongly it
 * authenticated.
 *
 * The obvious move is to put an `OPERATIONAL` level on [AssuranceLevel] and be done. It does
 * not work, and the reason is worth keeping: that ladder is `NONE < MFA < PHISHING_RESISTANT`
 * read from the token's `acr`/`amr`, so **a manager who signs into Keycloak with a password and
 * no second factor sits at `NONE`**. Ranking a waiter's six-digit PIN above that manager would
 * be false. Worse, deny-by-default on that ladder would refuse every existing user on the day
 * it shipped, because almost nobody has an MFA claim.
 *
 * So there are two ladders and they are independent. A password-only Keycloak session is
 * `STRONG` class and `NONE` assurance at the same time, and both statements are true.
 *
 * This codebase has already paid three times for giving two concepts one name — `posted` as
 * both a provider outcome and a database state, `clientId` as both merchant and payer,
 * `allow-header-identity` as both identity policy and step-up policy. This is the same shape,
 * caught before rather than after.
 */
class SessionClassTests {

    @Test
    fun aStrongSessionSatisfiesAnOperationalRequirement() {
        assertTrue(SessionClass.STRONG.satisfies(SessionClass.OPERATIONAL))
    }

    @Test
    fun anOperationalSessionDoesNotSatisfyAStrongRequirement() {
        assertFalse(SessionClass.OPERATIONAL.satisfies(SessionClass.STRONG))
    }

    @Test
    fun eachClassSatisfiesItself() {
        SessionClass.entries.forEach { assertTrue(it.satisfies(it)) }
    }

    /**
     * A typo in a policy row must not open a gate. Silently treating an unrecognised value as
     * the weakest requirement is how a misconfiguration becomes an authorization bypass.
     */
    @Test
    fun anUnknownRequirementIsRejectedRatherThanDefaulted() {
        listOf("moderate", "", "STRONGISH", "operational ish").forEach { value ->
            assertFailsWith<IllegalArgumentException>("'$value' must be rejected") {
                SessionClass.fromPolicy(value)
            }
        }
    }

    /** Config is written by hand, so surrounding whitespace and casing must not decide access. */
    @Test
    fun theTwoRealValuesParseRegardlessOfCasingAndPadding() {
        assertEquals(SessionClass.OPERATIONAL, SessionClass.fromPolicy(" Operational "))
        assertEquals(SessionClass.STRONG, SessionClass.fromPolicy("STRONG"))
    }

    /**
     * Every context built today is a Keycloak session, so the default must be `STRONG` — an
     * operational session is the thing that opts down. A default of `OPERATIONAL` would
     * silently downgrade every existing call site in the codebase at once.
     */
    @Test
    fun aContextIsStrongUnlessItSaysOtherwise() {
        val context = RequestContext(
            identity = RequestIdentity.Tenant(UUID.randomUUID(), UUID.randomUUID()),
            correlationId = "corr-default",
            idempotencyKey = null,
            httpMethod = "GET",
            requestPath = "/api/v1/session",
        )

        assertEquals(SessionClass.STRONG, context.sessionClass)
    }

    /** Every stored requirement must be one this code can parse. */
    @Test
    fun theEnumCoversExactlyWhatTheDatabaseAllows() {
        assertEquals(
            setOf("operational", "strong"),
            SessionClass.entries.map { it.name.lowercase() }.toSet(),
            "chk_permission_catalog_session_class allows exactly these two; a third value in " +
                "either place is a requirement the other side cannot express",
        )
    }

    /**
     * The two ladders are independent, and this is the case that proves it: a password-only
     * Keycloak login is a strong *class* of session that has performed no MFA *ceremony*.
     * Collapsing them would have made this state unrepresentable.
     */
    @Test
    fun aSessionCanBeStrongClassAndHaveNoCeremony() {
        val context = RequestContext(
            identity = RequestIdentity.Tenant(UUID.randomUUID(), UUID.randomUUID()),
            correlationId = "corr-no-mfa",
            idempotencyKey = null,
            httpMethod = "GET",
            requestPath = "/api/v1/session",
            authentication = AuthenticationAssurance.UNAUTHENTICATED,
        )

        assertEquals(SessionClass.STRONG, context.sessionClass)
        assertEquals(AssuranceLevel.NONE, context.authentication.level)
    }
}
