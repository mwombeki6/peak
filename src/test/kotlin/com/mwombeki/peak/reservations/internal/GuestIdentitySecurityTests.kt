package com.mwombeki.peak.reservations.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.springframework.mock.env.MockEnvironment

class GuestIdentitySecurityTests {

    @Test
    fun createsStableKeyedFingerprintAndMaskedDisplayValue() {
        val hasher = GuestIdentityNumberHasher(
            GuestIdentityProperties(hashKey = "test-secret-key-that-is-long-enough"),
        )

        val first = hasher.fingerprint("nida", "1990-0101-1234")
        val second = hasher.fingerprint("nida", "1990 0101 1234")

        assertEquals(first, second)
        assertNotEquals("199001011234", first)
        assertEquals("***1234", hasher.masked("1990-0101-1234"))
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun exposesCurrentAndPreviousFingerprintsDuringKeyRotation() {
        val hasher = GuestIdentityNumberHasher(
            GuestIdentityProperties(
                hashKey = "current-test-secret-key-that-is-long-enough",
                hashKeyVersion = "v2",
                previousHashKey = "previous-test-secret-key-that-is-long-enough",
                previousHashKeyVersion = "v1",
            ),
        )

        val candidates = hasher.candidateFingerprints("nida", "199001011234")

        assertEquals(2, candidates.size)
        assertNotEquals(candidates[0], candidates[1])
    }

    @Test
    fun productionRequiresStrongNonPlaceholderHashKey() {
        val environment = MockEnvironment()
            .withProperty("peak.runtime.mode", "api")
            .apply { setActiveProfiles("prod") }

        assertFailsWith<IllegalArgumentException> {
            GuestIdentityReadinessValidator(
                environment,
                GuestIdentityProperties(hashKey = "local-development-key"),
            ).afterSingletonsInstantiated()
        }

        GuestIdentityReadinessValidator(
            environment,
            GuestIdentityProperties(
                hashKey = "A-real-production-key-with-more-than-32-characters",
                hashKeyVersion = "v1",
            ),
        ).afterSingletonsInstantiated()
    }
}
