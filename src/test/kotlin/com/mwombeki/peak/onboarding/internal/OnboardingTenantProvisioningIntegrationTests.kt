package com.mwombeki.peak.onboarding.internal

import com.mwombeki.peak.FakeClamAvServer
import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.onboarding.api.OnboardingProvisioningException
import com.mwombeki.peak.onboarding.api.RequestAccessCommand
import com.mwombeki.peak.onboarding.api.UpdateOnboardingProfileCommand
import com.mwombeki.peak.onboarding.api.VerifyOnboardingPhoneCommand
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.internal.OutboxWorkerProcessor
import com.mwombeki.peak.shared.context.AuthenticationAssurance
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.ephemeral.RateLimitScope
import com.mwombeki.peak.shared.ephemeral.RateLimitStore
import com.mwombeki.peak.tenantmanagement.api.AddVerificationDocumentCommand
import com.mwombeki.peak.tenantmanagement.api.CreateVerificationCaseCommand
import com.mwombeki.peak.tenantmanagement.api.PlatformTenantActivationPort
import com.mwombeki.peak.tenantmanagement.api.RequestVerificationDocumentUploadCommand
import com.mwombeki.peak.tenantmanagement.api.ReviewVerificationCaseCommand
import com.mwombeki.peak.tenantmanagement.api.TenantTrustControlPort
import com.mwombeki.peak.tenantmanagement.api.VerificationCaseSummary
import com.mwombeki.peak.tenantmanagement.api.VerificationReviewAction
import com.mwombeki.peak.tenantmanagement.api.VerificationSubjectRef
import com.mwombeki.peak.verification.api.RequestVerificationCommand
import com.mwombeki.peak.verification.api.VerificationPort
import com.mwombeki.peak.verification.api.VerificationPurpose
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The explicit APPROVED -> TENANT_PROVISIONED step: reuses TenantOnboardingService wholesale
 * rather than duplicating tenant creation, so this exercises the real seam between the
 * pre-tenant KYB engine and the existing tenant-registration path, not a parallel one.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["peak.testcontainers.minio.enabled=true"])
@Testcontainers(disabledWithoutDocker = true)
class OnboardingTenantProvisioningIntegrationTests {

    @Autowired private lateinit var onboarding: OnboardingApplicationService
    @Autowired private lateinit var trust: TenantTrustControlPort
    @Autowired private lateinit var tenantActivation: PlatformTenantActivationPort
    @Autowired private lateinit var verification: VerificationPort
    @Autowired private lateinit var rateLimitStore: RateLimitStore
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var contextHolder: RequestContextHolder
    @Autowired private lateinit var outboxWorkerProcessor: OutboxWorkerProcessor
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    companion object {
        private val fakeClamAv = FakeClamAvServer()

        @JvmStatic
        @DynamicPropertySource
        fun kycStorageProperties(registry: DynamicPropertyRegistry) {
            val container = TestcontainersConfiguration.sharedMinioContainer
            container.start()
            registry.add("peak.verification.storage.enabled") { "true" }
            registry.add("peak.verification.storage.endpoint") { container.s3URL }
            registry.add("peak.verification.storage.access-key") { container.userName }
            registry.add("peak.verification.storage.secret-key") { container.password }
            registry.add("peak.verification.malware-scan.enabled") { "true" }
            registry.add("peak.verification.malware-scan.host") { "localhost" }
            registry.add("peak.verification.malware-scan.port") { fakeClamAv.port }
        }
    }

    /** Drains the async malware-scan queue so a just-uploaded document reaches scan_status=clean. */
    private fun processDocumentScans() {
        outboxWorkerProcessor.processBatchBlocking(OutboxDestination.DOCUMENT_SCAN)
    }

    @Test
    fun anApprovedApplicationWithAProfileProvisionsExactlyOneTenant() {
        val applicationId = createPhoneVerifiedApplication()
        val reviewer = insertPlatformOperator()

        val case = submittedCase(applicationId)

        platform(reviewer)
        trust.reviewVerificationCase(
            ReviewVerificationCaseCommand(
                VerificationSubjectRef.Application(applicationId), case.caseId,
                VerificationReviewAction.START_REVIEW, null, "low", null,
            ),
        )
        platform(reviewer)
        val approved = trust.reviewVerificationCase(
            ReviewVerificationCaseCommand(
                VerificationSubjectRef.Application(applicationId), case.caseId,
                VerificationReviewAction.APPROVE, "Evidence verified", "low", null,
            ),
        )
        assertEquals("approved", approved.status)

        applicant(applicationId)
        onboarding.updateProfile(
            UpdateOnboardingProfileCommand(
                applicationId, "Zanzibar Beach Lodge Limited", "owner@zanzibarbeachlodge.test",
            ),
        )

        platform(reviewer)
        val tenant = onboarding.provisionTenant(applicationId)
        assertNotNull(tenant.tenantNumber)
        assertEquals("owner@zanzibarbeachlodge.test", tenant.businessEmail)

        val row = jdbc.queryForMap(
            "SELECT status, tenant_id FROM onboarding_applications WHERE id = ?",
            applicationId,
        )
        assertEquals("TENANT_PROVISIONED", row["status"])
        assertEquals(tenant.id, row["tenant_id"])

        // Phase 4F: the case and its document are re-pointed onto the tenant, not re-verified.
        val caseRow = jdbc.queryForMap(
            "SELECT tenant_id, onboarding_application_id, status FROM tenant_verification_cases WHERE id = ?",
            case.caseId,
        )
        assertEquals(tenant.id, caseRow["tenant_id"])
        assertEquals(null, caseRow["onboarding_application_id"])
        assertEquals("approved", caseRow["status"])

        val documentCount = jdbc.queryForObject(
            """
            SELECT count(*) FROM tenant_verification_documents
            WHERE verification_case_id = ? AND tenant_id = ? AND onboarding_application_id IS NULL
            """.trimIndent(),
            Int::class.java,
            case.caseId,
            tenant.id,
        )
        assertEquals(1, documentCount)

        val profileRow = jdbc.queryForMap(
            """
            SELECT verification_status, verified_at, verified_by_platform_user_id
            FROM tenant_profiles WHERE tenant_id = ?
            """.trimIndent(),
            tenant.id,
        )
        assertEquals("verified", profileRow["verification_status"])
        assertNotNull(profileRow["verified_at"])
        assertEquals(reviewer, profileRow["verified_by_platform_user_id"])

        // The carried-forward evidence is enough on its own to satisfy the tenant's own
        // activation readiness gate — no second KYB review is required.
        platform(reviewer)
        val readiness = tenantActivation.readiness(tenant.id)
        val verificationGate = readiness.gates.single { it.code == "business_verified" }
        assertTrue(verificationGate.satisfied, "business verification gate: ${verificationGate.detail}")

        // Idempotent: provisioning an already-provisioned application returns the same
        // tenant rather than erroring or creating a second one.
        platform(reviewer)
        val second = onboarding.provisionTenant(applicationId)
        assertEquals(tenant.id, second.id)
    }

    @Test
    fun provisioningBeforeApprovalIsRefused() {
        val applicationId = createPhoneVerifiedApplication()
        applicant(applicationId)
        trust.createVerificationCase(
            CreateVerificationCaseCommand(
                VerificationSubjectRef.Application(applicationId), "initial_onboarding", "standard",
            ),
        )

        assertFailsWith<OnboardingProvisioningException> { onboarding.provisionTenant(applicationId) }
    }

    @Test
    fun provisioningWithoutABusinessProfileIsRefused() {
        val applicationId = createPhoneVerifiedApplication()
        val reviewer = insertPlatformOperator()

        val case = submittedCase(applicationId)
        platform(reviewer)
        trust.reviewVerificationCase(
            ReviewVerificationCaseCommand(
                VerificationSubjectRef.Application(applicationId), case.caseId,
                VerificationReviewAction.START_REVIEW, null, "low", null,
            ),
        )
        platform(reviewer)
        trust.reviewVerificationCase(
            ReviewVerificationCaseCommand(
                VerificationSubjectRef.Application(applicationId), case.caseId,
                VerificationReviewAction.APPROVE, "Evidence verified", "low", null,
            ),
        )

        val failure = assertFailsWith<OnboardingProvisioningException> {
            onboarding.provisionTenant(applicationId)
        }
        assertTrue(failure.message!!.contains("Legal name"))
    }

    /** Creates a case, uploads a real document to it, and submits it — ready for review. */
    private fun submittedCase(applicationId: UUID): VerificationCaseSummary {
        val subject = VerificationSubjectRef.Application(applicationId)
        applicant(applicationId)
        val case = trust.createVerificationCase(
            CreateVerificationCaseCommand(subject, "initial_onboarding", "standard"),
        )

        applicant(applicationId)
        val authorization = trust.requestVerificationDocumentUpload(
            RequestVerificationDocumentUploadCommand(subject, case.caseId, "application/pdf"),
        )
        val bytes = "not a real PDF, just test bytes".toByteArray()
        val putResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(authorization.uploadUrl))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(200, putResponse.statusCode())

        applicant(applicationId)
        trust.addVerificationDocument(
            AddVerificationDocumentCommand(
                subject, case.caseId, "business_registration", "***1234",
                authorization.objectKey, bytes.sha256Hex(), "application/pdf", null, null,
            ),
        )
        processDocumentScans()

        applicant(applicationId)
        return trust.submitVerificationCase(subject, case.caseId)
    }

    private fun ByteArray.sha256Hex(): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this))

    private fun createPhoneVerifiedApplication(): UUID {
        val phone = "+2557" + (10_000_000..99_999_999).random().toString()
        val receipt = onboarding.requestAccess(
            RequestAccessCommand("Amina Hassan", phone, "Zanzibar Beach Lodge"),
        )
        rateLimitStore.reset(RateLimitScope.OTP_SEND_COOLDOWN, phone)
        val code = verification.request(
            RequestVerificationCommand(VerificationPurpose.PHONE_VERIFICATION, phone),
        ).code
        onboarding.verifyPhone(VerifyOnboardingPhoneCommand(receipt.applicationId, code))
        return receipt.applicationId
    }

    private fun applicant(applicationId: UUID) {
        val token = UUID.randomUUID().toString()
        contextHolder.set(
            RequestContext(
                identity = RequestIdentity.OnboardingApplicant(applicationId, "corr-$token"),
                correlationId = "corr-$token",
                idempotencyKey = "idem-$token",
                httpMethod = "POST",
                requestPath = "/api/v1/onboarding/me/verification-cases",
            ),
        )
    }

    private fun platform(platformUserId: UUID) {
        val token = UUID.randomUUID().toString()
        contextHolder.set(
            RequestContext(
                identity = RequestIdentity.Platform(platformUserId, "corr-$token"),
                correlationId = "corr-$token",
                idempotencyKey = "idem-$token",
                httpMethod = "POST",
                requestPath = "/api/v1/platform/onboarding",
                authentication = AuthenticationAssurance.UNAUTHENTICATED,
            ),
        )
    }

    private fun insertPlatformOperator(): UUID {
        val id = UUID.randomUUID()
        val roleId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO platform_users (id, full_name, email, status, mfa_enabled)
            VALUES (?, ?, ?, 'active', true)
            """.trimIndent(),
            id, "Platform Reviewer", "reviewer-$id@example.com",
        )
        jdbc.update(
            "INSERT INTO platform_roles (id, name, code) VALUES (?, ?, ?)",
            roleId, "Reviewer", "reviewer-${id.toString().take(8)}",
        )
        jdbc.update(
            """
            INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
            SELECT ?, id FROM platform_permissions WHERE code <> 'platform.admin.all'
            """.trimIndent(),
            roleId,
        )
        jdbc.update(
            "INSERT INTO platform_user_roles (platform_user_id, platform_role_id) VALUES (?, ?)",
            id, roleId,
        )
        return id
    }
}
