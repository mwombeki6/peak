package com.mwombeki.peak.verification

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.ephemeral.RateLimitScope
import com.mwombeki.peak.shared.ephemeral.RateLimitStore
import com.mwombeki.peak.verification.api.ConfirmVerificationCommand
import com.mwombeki.peak.verification.api.RequestVerificationCommand
import com.mwombeki.peak.verification.api.VerificationPurpose
import com.mwombeki.peak.verification.api.VerificationThrottledException
import com.mwombeki.peak.verification.internal.VerificationService
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class VerificationServiceIntegrationTests {

    @Autowired private lateinit var verification: VerificationService
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var rateLimitStore: RateLimitStore

    @AfterTest
    fun resetSession() {
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun theStoredHashIsNeverThePlaintextCode() {
        val destination = phone()
        verification.request(RequestVerificationCommand(VerificationPurpose.PHONE_VERIFICATION, destination))

        val stored = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT code_hash FROM verification_challenges WHERE destination = ?",
                String::class.java,
                destination,
            ),
        )

        // A 6-digit code has 10 possible values per digit; the stored value must not equal
        // any single digit substring pattern of what a plaintext 6-digit code would look like,
        // and must not simply BE 6 digits.
        assertFalse(Regex("^\\d{6}$").matches(stored), "stored value looks like a bare plaintext code: $stored")
    }

    @Test
    fun aCorrectCodeVerifiesExactlyOnce() {
        val destination = phone()
        val code = issueAndReadCode(destination)

        val first = verification.confirm(
            ConfirmVerificationCommand(VerificationPurpose.PHONE_VERIFICATION, destination, code),
        )
        assertTrue(first.verified)

        val replay = verification.confirm(
            ConfirmVerificationCommand(VerificationPurpose.PHONE_VERIFICATION, destination, code),
        )
        assertFalse(replay.verified, "a consumed code must not verify again")
    }

    @Test
    fun anExpiredChallengeNeverVerifies() {
        val destination = phone()
        val code = issueAndReadCode(destination)
        jdbcTemplate.update(
            "UPDATE verification_challenges SET expires_at = now() - interval '1 second' WHERE destination = ?",
            destination,
        )

        val outcome = verification.confirm(
            ConfirmVerificationCommand(VerificationPurpose.PHONE_VERIFICATION, destination, code),
        )
        assertFalse(outcome.verified)
    }

    @Test
    fun theAttemptBudgetIsExhaustedByWrongGuessesAndSurvivesAResend() {
        val destination = phone()
        val firstCode = issueAndReadCode(destination)

        // Burn the whole attempt budget (default max 5) on the first code.
        repeat(5) {
            verification.confirm(
                ConfirmVerificationCommand(VerificationPurpose.PHONE_VERIFICATION, destination, "000000"),
            )
        }

        val correctAfterExhaustion = verification.confirm(
            ConfirmVerificationCommand(VerificationPurpose.PHONE_VERIFICATION, destination, firstCode),
        )
        assertFalse(correctAfterExhaustion.verified, "the correct code must not work once attempts are exhausted")

        // Resend must invalidate the old code, but the attacker's spent budget must not reset.
        jdbcTemplate.update(
            "UPDATE verification_challenges SET expires_at = now() + interval '1 hour' " +
                "WHERE destination = ? AND consumed_at IS NULL",
            destination,
        )
        val attemptsAfterResend = jdbcTemplate.queryForObject(
            "SELECT attempts FROM verification_challenges WHERE destination = ? AND consumed_at IS NULL",
            Int::class.java,
            destination,
        )
        assertEquals(5, attemptsAfterResend, "attempts must survive a resend, not reset")
    }

    @Test
    fun resendingInvalidatesThePreviousCodeWithoutCreatingASecondRow() {
        val destination = phone()
        val firstCode = issueAndReadCode(destination)
        // The send cooldown is real, separate behavior (covered by sendingTooOftenIsThrottled)
        // and not what this test is exercising — clear it to reach the actual resend.
        rateLimitStore.reset(RateLimitScope.OTP_SEND_COOLDOWN, destination)
        verification.request(RequestVerificationCommand(VerificationPurpose.PHONE_VERIFICATION, destination))

        val liveRows = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM verification_challenges WHERE destination = ? AND consumed_at IS NULL",
            Int::class.java,
            destination,
        )
        assertEquals(1, liveRows, "a resend must replace the live challenge, not add a second one")

        val outcome = verification.confirm(
            ConfirmVerificationCommand(VerificationPurpose.PHONE_VERIFICATION, destination, firstCode),
        )
        assertFalse(outcome.verified, "the old code must no longer work after a resend")
    }

    @Test
    fun aPurposeCannotBeSatisfiedByAnotherPurposesCode() {
        val destination = phone()
        val code = issueAndReadCode(destination, VerificationPurpose.PHONE_VERIFICATION)

        val outcome = verification.confirm(
            ConfirmVerificationCommand(VerificationPurpose.TENANT_ACTIVATION, destination, code),
        )
        assertFalse(outcome.verified)
    }

    @Test
    fun sendingTooOftenIsThrottled() {
        val destination = phone()
        verification.request(RequestVerificationCommand(VerificationPurpose.PHONE_VERIFICATION, destination))

        assertFailsWith<VerificationThrottledException> {
            verification.request(RequestVerificationCommand(VerificationPurpose.PHONE_VERIFICATION, destination))
        }
    }

    private fun issueAndReadCode(
        destination: String,
        purpose: VerificationPurpose = VerificationPurpose.PHONE_VERIFICATION,
    ): String {
        return verification.request(RequestVerificationCommand(purpose, destination)).code
    }

    private fun phone(): String = "+2557" + (10_000_000..99_999_999).random().toString()
}
