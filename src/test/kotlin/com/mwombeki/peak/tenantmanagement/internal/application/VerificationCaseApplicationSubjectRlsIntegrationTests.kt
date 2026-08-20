package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.onboarding.api.RequestAccessCommand
import com.mwombeki.peak.onboarding.internal.OnboardingApplicationService
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.tenantmanagement.api.CreateVerificationCaseCommand
import com.mwombeki.peak.tenantmanagement.api.TenantTrustControlPort
import com.mwombeki.peak.tenantmanagement.api.VerificationSubjectRef
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * `TenantTrustControlService`'s Application-subject methods run under the ordinary,
 * privileged test datasource role, which bypasses row-level security entirely — an INSERT
 * that's missing the `databaseSessionContext.bind()` needed to satisfy
 * `tenant_verification_cases`'s WITH CHECK clause would still silently succeed under that
 * role, hiding the exact gap this exists to catch. Wrapping the real service call in
 * `SET LOCAL ROLE pms_app`, joining the same transaction Spring's `PROPAGATION_REQUIRED`
 * gives the nested `TransactionTemplate.execute` inside `mutate`, is what makes this a real
 * production-shaped assertion rather than a check against already-privileged data.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class VerificationCaseApplicationSubjectRlsIntegrationTests {

    @Autowired private lateinit var onboarding: OnboardingApplicationService
    @Autowired private lateinit var trust: TenantTrustControlPort
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var requestContextHolder: RequestContextHolder

    @Test
    fun creatingACaseAsAnApplicantSatisfiesRlsUnderTheRealConstrainedRole() {
        val applicationId = applicant()

        val case = requireNotNull(
            transactionTemplate.execute {
                jdbcTemplate.execute("SET LOCAL ROLE pms_app")
                withApplicant(applicationId) {
                    trust.createVerificationCase(
                        CreateVerificationCaseCommand(
                            VerificationSubjectRef.Application(applicationId),
                            "initial_onboarding",
                            "standard",
                        ),
                    )
                }
            },
        )

        assertEquals(applicationId, case.onboardingApplicationId)
    }

    @Test
    fun aVerificationCaseIsInvisibleUnderRlsToAnotherApplicantOrAnUnboundSession() {
        val ours = applicant()
        val theirs = applicant()

        val case = withApplicant(ours) {
            trust.createVerificationCase(
                CreateVerificationCaseCommand(
                    VerificationSubjectRef.Application(ours), "initial_onboarding", "standard",
                ),
            )
        }

        assertEquals(1, caseCountUnderRls(case.caseId, boundTo = ours))
        assertEquals(0, caseCountUnderRls(case.caseId, boundTo = theirs))
        assertEquals(0, caseCountUnderRls(case.caseId, boundTo = null))
    }

    private fun caseCountUnderRls(caseId: UUID, boundTo: UUID?): Int? = transactionTemplate.execute {
        jdbcTemplate.execute("SET LOCAL ROLE pms_app")
        if (boundTo != null) {
            jdbcTemplate.queryForObject(
                "SELECT set_config('app.current_onboarding_application_id', ?, true)",
                String::class.java,
                boundTo.toString(),
            )
        }
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM tenant_verification_cases WHERE id = ?",
            Int::class.java,
            caseId,
        )
    }

    private fun applicant(): UUID {
        val phone = "+2557" + (10_000_000..99_999_999).random().toString()
        return onboarding.requestAccess(
            RequestAccessCommand("Test Applicant", phone, "Test Business"),
        ).applicationId
    }

    private fun <T> withApplicant(applicationId: UUID, block: () -> T): T {
        val token = UUID.randomUUID().toString()
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.OnboardingApplicant(applicationId, "corr-$token"),
                correlationId = "corr-$token",
                idempotencyKey = "idem-$token",
                httpMethod = "POST",
                requestPath = "/api/v1/onboarding/me/verification-cases",
            ),
        )
        try {
            return block()
        } finally {
            requestContextHolder.clear()
        }
    }
}
