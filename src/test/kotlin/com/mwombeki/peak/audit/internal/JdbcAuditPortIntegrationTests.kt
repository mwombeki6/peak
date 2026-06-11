package com.mwombeki.peak.audit.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.audit.api.AuditOutcome
import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.PlatformAuditEvent
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class JdbcAuditPortIntegrationTests {

    @Autowired
    private lateinit var auditPort: AuditPort

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
    }

    @Test
    fun recordsPlatformAuditEventInsideTransaction() {
        val platformUserId = UUID.randomUUID()
        val resourceId = UUID.randomUUID()

        transactionTemplate.executeWithoutResult {
            insertPlatformUser(platformUserId)
            requestContextHolder.set(
                requestContext(
                    RequestIdentity.Platform(platformUserId, "corr-platform-audit"),
                    "corr-platform-audit",
                ),
            )

            auditPort.recordPlatformEvent(
                PlatformAuditEvent(
                    action = "platform.tenants.create",
                    resource = AuditResource("tenants", resourceId),
                    outcome = AuditOutcome.SUCCESS,
                    after = mapOf(
                        "slug" to "peak-test",
                        "apiToken" to "sensitive-token",
                    ),
                ),
            )
        }

        val row = jdbcTemplate.queryForMap(
            """
            SELECT platform_user_id, action, entity_type, entity_id,
                   correlation_id, outcome, new_values::text AS new_values
            FROM platform_audit_logs
            WHERE correlation_id = ?
            """.trimIndent(),
            "corr-platform-audit",
        )

        assertEquals(platformUserId, row["platform_user_id"])
        assertEquals("platform.tenants.create", row["action"])
        assertEquals("tenants", row["entity_type"])
        assertEquals(resourceId, row["entity_id"])
        assertEquals("success", row["outcome"])
        assertTrue(row["new_values"].toString().contains("[REDACTED]"))
    }

    @Test
    fun recordsTenantAuditEventInsideTransaction() {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val tenantUserId = UUID.randomUUID()
        val resourceId = UUID.randomUUID()

        transactionTemplate.executeWithoutResult {
            insertPlan(planId)
            insertTenant(tenantId, planId)
            insertTenantUser(tenantId, tenantUserId)
            requestContextHolder.set(
                requestContext(
                    RequestIdentity.Tenant(tenantId, tenantUserId, "corr-tenant-audit"),
                    "corr-tenant-audit",
                ),
            )

            auditPort.recordTenantEvent(
                TenantAuditEvent(
                    tenantId = tenantId,
                    action = "tenant.profile.update",
                    resource = AuditResource("tenant_profiles", resourceId),
                    outcome = AuditOutcome.SUCCESS,
                    before = mapOf("name" to "Old Peak"),
                    after = mapOf("name" to "Peak", "password" to "sensitive"),
                ),
            )
        }

        val row = jdbcTemplate.queryForMap(
            """
            SELECT tenant_id, user_id, action, entity_type, entity_id,
                   correlation_id, outcome, new_values::text AS new_values
            FROM audit_logs
            WHERE correlation_id = ?
            """.trimIndent(),
            "corr-tenant-audit",
        )

        assertEquals(tenantId, row["tenant_id"])
        assertEquals(tenantUserId, row["user_id"])
        assertEquals("tenant.profile.update", row["action"])
        assertEquals("tenant_profiles", row["entity_type"])
        assertEquals(resourceId, row["entity_id"])
        assertEquals("success", row["outcome"])
        assertTrue(row["new_values"].toString().contains("[REDACTED]"))
    }

    @Test
    fun rejectsAuditWriteOutsideTransaction() {
        val error = assertFailsWith<IllegalArgumentException> {
            auditPort.recordPlatformEvent(
                PlatformAuditEvent(
                    action = "platform.test",
                    resource = AuditResource("tests"),
                ),
            )
        }

        assertEquals(
            "Audit events must be recorded inside an active transaction",
            error.message,
        )
    }

    @Test
    fun databaseRejectsAuditMutation() {
        val platformUserId = UUID.randomUUID()

        transactionTemplate.executeWithoutResult {
            insertPlatformUser(platformUserId)
            requestContextHolder.set(
                requestContext(
                    RequestIdentity.Platform(platformUserId, "corr-append-only"),
                    "corr-append-only",
                ),
            )
            auditPort.recordPlatformEvent(
                PlatformAuditEvent(
                    action = "platform.audit.append_only",
                    resource = AuditResource("platform_audit_logs"),
                ),
            )
        }

        val error = assertFailsWith<UncategorizedSQLException> {
            jdbcTemplate.update(
                """
                UPDATE platform_audit_logs
                SET action = 'platform.audit.mutated'
                WHERE correlation_id = ?
                """.trimIndent(),
                "corr-append-only",
            )
        }

        assertTrue(error.message.orEmpty().contains("Audit records are append-only"))
    }

    private fun requestContext(
        identity: RequestIdentity,
        correlationId: String,
    ): RequestContext {
        return RequestContext(
            identity = identity,
            correlationId = correlationId,
            idempotencyKey = null,
            httpMethod = "POST",
            requestPath = "/test",
        )
    }

    private fun insertPlatformUser(id: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, full_name, email, status)
            VALUES (?, ?, ?, 'active')
            """.trimIndent(),
            id,
            "Platform User $id",
            "platform-$id@example.com",
        )
    }

    private fun insertPlan(id: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO plans (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            id,
            "Plan $id",
            "plan-$id",
        )
    }

    private fun insertTenant(id: UUID, planId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO tenants (
                id,
                name,
                slug,
                schema_name,
                plan_id
            )
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            "Tenant $id",
            "tenant-$id",
            "tenant_$id".replace("-", "_"),
            planId,
        )
    }

    private fun insertTenantUser(tenantId: UUID, userId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status)
            VALUES (?, ?, ?, ?, 'active')
            """.trimIndent(),
            userId,
            tenantId,
            "Tenant User $userId",
            "tenant-user-$userId@example.com",
        )
    }
}
