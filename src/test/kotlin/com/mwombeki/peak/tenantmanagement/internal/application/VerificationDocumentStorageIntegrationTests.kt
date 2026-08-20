package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.tenantmanagement.api.AddVerificationDocumentCommand
import com.mwombeki.peak.tenantmanagement.api.CreateVerificationCaseCommand
import com.mwombeki.peak.tenantmanagement.api.PlatformControlConflictException
import com.mwombeki.peak.tenantmanagement.api.RequestVerificationDocumentUploadCommand
import com.mwombeki.peak.tenantmanagement.api.TenantTrustControlPort
import com.mwombeki.peak.tenantmanagement.api.VerificationSubjectRef
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * `addVerificationDocument` must refuse a storage key that was never actually uploaded — this
 * is the gap the mission called out explicitly: a `storage_object_key` used to be trusted on
 * the caller's word alone. These tests run against a real MinIO container, not a mock, because
 * "the port compiles" and "the bytes are really there" are different claims.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.testcontainers.minio.enabled=true",
        "peak.security.request-context.allow-header-identity=true",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class VerificationDocumentStorageIntegrationTests {

    @Autowired private lateinit var trust: TenantTrustControlPort
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var requestContextHolder: RequestContextHolder

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun kycStorageProperties(registry: DynamicPropertyRegistry) {
            val container = TestcontainersConfiguration.sharedMinioContainer
            // registry.add's suppliers are evaluated lazily, but the mapped port doesn't exist
            // until the container is actually running, and nothing else in this wiring path
            // starts it — start() is idempotent, so this is safe even if the bean lifecycle
            // also starts it.
            container.start()
            registry.add("peak.verification.storage.enabled") { "true" }
            registry.add("peak.verification.storage.endpoint") { container.s3URL }
            registry.add("peak.verification.storage.access-key") { container.userName }
            registry.add("peak.verification.storage.secret-key") { container.password }
        }
    }

    private val httpClient: HttpClient = HttpClient.newHttpClient()

    @Test
    fun aRealUploadedObjectIsAcceptedAndAFakeKeyIsRefused() {
        val fixture = seedTenant()
        val subject = VerificationSubjectRef.Tenant(fixture.tenantId)

        val case = tenant(fixture) {
            trust.createVerificationCase(
                CreateVerificationCaseCommand(subject, "initial_onboarding", "standard"),
            )
        }

        val authorization = tenant(fixture) {
            trust.requestVerificationDocumentUpload(
                RequestVerificationDocumentUploadCommand(subject, case.caseId, "application/pdf"),
            )
        }
        assertEquals(true, authorization.objectKey.startsWith("kyc/${case.caseId}/"))

        val bytes = "not a real PDF, just test bytes".toByteArray()
        val putResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(authorization.uploadUrl))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(200, putResponse.statusCode())

        val document = tenant(fixture) {
            trust.addVerificationDocument(
                AddVerificationDocumentCommand(
                    subject, case.caseId, "business_registration", "***1234",
                    authorization.objectKey, bytes.sha256Hex(), "application/pdf", null, null,
                ),
            )
        }
        assertEquals(authorization.objectKey, document.storageObjectKey)
        assertEquals("submitted", document.status)

        // A key nobody ever uploaded to must not become a document, no matter how
        // plausible-looking the string is.
        val neverUploadedKey = "kyc/${case.caseId}/${UUID.randomUUID()}"
        assertFailsWith<PlatformControlConflictException> {
            tenant(fixture) {
                trust.addVerificationDocument(
                    AddVerificationDocumentCommand(
                        subject, case.caseId, "business_registration", "***1234",
                        neverUploadedKey, bytes.sha256Hex(), "application/pdf", null, null,
                    ),
                )
            }
        }
    }

    @Test
    fun aKeyIssuedForAnotherCaseIsRefused() {
        val fixture = seedTenant()
        val subject = VerificationSubjectRef.Tenant(fixture.tenantId)
        val case = tenant(fixture) {
            trust.createVerificationCase(
                CreateVerificationCaseCommand(subject, "initial_onboarding", "standard"),
            )
        }
        val foreignKey = "kyc/${UUID.randomUUID()}/${UUID.randomUUID()}"

        assertFailsWith<IllegalArgumentException> {
            tenant(fixture) {
                trust.addVerificationDocument(
                    AddVerificationDocumentCommand(
                        subject, case.caseId, "business_registration", "***1234",
                        foreignKey, "a".repeat(64), "application/pdf", null, null,
                    ),
                )
            }
        }
    }

    private fun ByteArray.sha256Hex(): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this))

    private fun <T> tenant(fixture: Fixture, block: () -> T): T {
        val token = UUID.randomUUID().toString()
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Tenant(fixture.tenantId, fixture.userId, "corr-$token"),
                correlationId = "corr-$token",
                idempotencyKey = "idem-$token",
                httpMethod = "POST",
                requestPath = "/api/v1/tenants/${fixture.tenantId}/verification-cases",
            ),
        )
        try {
            return block()
        } finally {
            requestContextHolder.clear()
        }
    }

    private data class Fixture(val tenantId: UUID, val userId: UUID)

    private fun seedTenant(): Fixture {
        val fixture = Fixture(tenantId = UUID.randomUUID(), userId = UUID.randomUUID())
        val planId = UUID.randomUUID()
        val roleId = UUID.randomUUID()
        transactionTemplate.execute {
            jdbcTemplate.update(
                "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
                planId, "Plan $planId", "plan-$planId",
            )
            jdbcTemplate.update(
                "INSERT INTO tenants (id, name, slug, schema_name, plan_id) VALUES (?, ?, ?, ?, ?)",
                fixture.tenantId, "Tenant ${fixture.tenantId}", "tenant-${fixture.tenantId}",
                "tenant_${fixture.tenantId}".replace("-", "_"), planId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO users (id, tenant_id, full_name, email, status)
                VALUES (?, ?, 'Manager', ?, 'active')
                """.trimIndent(),
                fixture.userId, fixture.tenantId, "manager-${fixture.userId}@example.test",
            )
            jdbcTemplate.update(
                """
                INSERT INTO tenant_roles (id, tenant_id, name, code, is_system)
                VALUES (?, ?, 'Manager', 'manager', true)
                """.trimIndent(),
                roleId, fixture.tenantId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO permissions (tenant_id, code, description)
                SELECT ?, code, description FROM permission_catalog WHERE code = 'tenant.profile.manage'
                ON CONFLICT (tenant_id, code) DO NOTHING
                """.trimIndent(),
                fixture.tenantId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
                SELECT ?, id FROM permissions WHERE tenant_id = ? AND code = 'tenant.profile.manage'
                """.trimIndent(),
                roleId, fixture.tenantId,
            )
            jdbcTemplate.update(
                "INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id) VALUES (?, ?, ?)",
                fixture.userId, fixture.tenantId, roleId,
            )
        }
        return fixture
    }
}
