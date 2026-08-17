package com.mwombeki.peak.onboarding

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.onboarding.api.OnboardingVerificationFailedException
import com.mwombeki.peak.onboarding.api.RequestAccessCommand
import com.mwombeki.peak.onboarding.api.VerifyOnboardingPhoneCommand
import com.mwombeki.peak.onboarding.internal.OnboardingApplicationService
import com.mwombeki.peak.shared.ephemeral.RateLimitScope
import com.mwombeki.peak.shared.ephemeral.RateLimitStore
import com.mwombeki.peak.verification.api.RequestVerificationCommand
import com.mwombeki.peak.verification.api.VerificationPort
import com.mwombeki.peak.verification.api.VerificationPurpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OnboardingApplicationServiceIntegrationTests {

    @Autowired private lateinit var onboarding: OnboardingApplicationService
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var verification: VerificationPort
    @Autowired private lateinit var rateLimitStore: RateLimitStore
    @Autowired private lateinit var transactionTemplate: TransactionTemplate

    @Test
    fun requestAccessCreatesADraftApplicationWithNoTenant() {
        val receipt = onboarding.requestAccess(
            RequestAccessCommand(
                representativeFullName = "Amina Hassan",
                representativePhone = phone(),
                businessName = "Zanzibar Beach Lodge",
            ),
        )

        val row = jdbcTemplate.queryForMap(
            "SELECT status, tenant_id FROM onboarding_applications WHERE id = ?",
            receipt.applicationId,
        )
        assertEquals("DRAFT", row["status"])
        assertEquals(null, row["tenant_id"])
    }

    @Test
    fun theWrongCodeDoesNotVerifyAndDoesNotIssueASession() {
        val receipt = onboarding.requestAccess(
            RequestAccessCommand("Amina Hassan", phone(), "Zanzibar Beach Lodge"),
        )

        assertFailsWith<OnboardingVerificationFailedException> {
            onboarding.verifyPhone(VerifyOnboardingPhoneCommand(receipt.applicationId, "000000"))
        }

        val status = jdbcTemplate.queryForObject(
            "SELECT status FROM onboarding_applications WHERE id = ?",
            String::class.java,
            receipt.applicationId,
        )
        assertEquals("DRAFT", status)
    }

    @Test
    fun theRealCodeVerifiesAndIssuesASession() {
        val phone = phone()
        val receipt = onboarding.requestAccess(
            RequestAccessCommand("Juma Ali", phone, "Serengeti Camp"),
        )

        // requestAccess already issued one challenge for this destination (the applicant never
        // sees the code — it only ever travels by SMS). The send cooldown is real, separate
        // behavior; clearing it lets this call reissue in place (same live row, new code) so
        // the test can observe what a real recipient would have received.
        rateLimitStore.reset(RateLimitScope.OTP_SEND_COOLDOWN, phone)
        val code = verification.request(
            RequestVerificationCommand(VerificationPurpose.PHONE_VERIFICATION, phone),
        ).code

        val session = onboarding.verifyPhone(VerifyOnboardingPhoneCommand(receipt.applicationId, code))
        assertTrue(session.token.isNotBlank())

        val status = jdbcTemplate.queryForObject(
            "SELECT status FROM onboarding_applications WHERE id = ?",
            String::class.java,
            receipt.applicationId,
        )
        assertEquals("PHONE_VERIFIED", status)

        val sessionCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM onboarding_sessions WHERE application_id = ? AND revoked_at IS NULL",
            Int::class.java,
            receipt.applicationId,
        )
        assertEquals(1, sessionCount)

        val storedHash = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT token_hash FROM onboarding_sessions WHERE application_id = ?",
                String::class.java,
                receipt.applicationId,
            ),
        )
        assertNotEquals(storedHash, session.token, "the session bearer must not equal its own stored hash")
    }

    @Test
    fun anApplicantCannotSeeAnotherApplicantsApplicationUnderRls() {
        val ours = onboarding.requestAccess(
            RequestAccessCommand("Amina Hassan", phone(), "Zanzibar Beach Lodge"),
        )
        val theirs = onboarding.requestAccess(
            RequestAccessCommand("Juma Ali", phone(), "Serengeti Camp"),
        )

        val visible = transactionTemplate.execute {
            jdbcTemplate.execute("SET LOCAL ROLE pms_app")
            jdbcTemplate.queryForObject(
                "SELECT set_config('app.current_onboarding_application_id', ?, true)",
                String::class.java,
                ours.applicationId.toString(),
            )
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM onboarding_applications WHERE id = ?",
                Int::class.java,
                theirs.applicationId,
            )
        }

        assertEquals(0, visible, "an applicant's own session must not read another applicant's application")
    }

    @Test
    fun anOrdinaryTenantSessionCannotReadOnboardingApplicationsAtAll() {
        val application = onboarding.requestAccess(
            RequestAccessCommand("Amina Hassan", phone(), "Zanzibar Beach Lodge"),
        )

        val visible = transactionTemplate.execute {
            jdbcTemplate.execute("SET LOCAL ROLE pms_app")
            jdbcTemplate.queryForObject(
                "SELECT set_config('app.current_tenant_id', ?, true)",
                String::class.java,
                java.util.UUID.randomUUID().toString(),
            )
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM onboarding_applications WHERE id = ?",
                Int::class.java,
                application.applicationId,
            )
        }

        assertEquals(0, visible, "a bound tenant session must never read a pre-tenant application")
    }

    private fun phone(): String = "+2557" + (10_000_000..99_999_999).random().toString()
}
