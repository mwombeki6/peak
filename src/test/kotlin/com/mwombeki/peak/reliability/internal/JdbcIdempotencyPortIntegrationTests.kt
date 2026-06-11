package com.mwombeki.peak.reliability.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.IdempotencyStatus
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextException
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
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class JdbcIdempotencyPortIntegrationTests {

    @Autowired
    private lateinit var idempotencyPort: IdempotencyPort

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
    fun reservesAndReplaysPlatformCommand() {
        val platformUserId = UUID.randomUUID()
        val idempotencyKey = "idem-${UUID.randomUUID()}"

        val first = transactionTemplate.execute {
            requestContextHolder.set(platformContext(platformUserId, idempotencyKey))
            idempotencyPort.reserve(command("peak"))
        }

        assertTrue(first is IdempotencyReservation.Started)

        transactionTemplate.executeWithoutResult {
            requestContextHolder.set(platformContext(platformUserId, idempotencyKey))
            idempotencyPort.markSucceeded(
                recordId = first.recordId,
                responseCode = 201,
                responseBody = mapOf("id" to "tenant-1"),
                resourceId = UUID.randomUUID(),
            )
        }

        val replay = transactionTemplate.execute {
            requestContextHolder.set(platformContext(platformUserId, idempotencyKey))
            idempotencyPort.reserve(command("peak"))
        }

        assertTrue(replay is IdempotencyReservation.Replay)
        assertEquals(201, replay.responseCode)
        assertEquals(IdempotencyStatus.SUCCEEDED, replay.status)
        assertTrue(replay.responseBody.orEmpty().contains("tenant-1"))
    }

    @Test
    fun returnsConflictForSameKeyWithDifferentPayload() {
        val platformUserId = UUID.randomUUID()
        val idempotencyKey = "idem-${UUID.randomUUID()}"

        val first = transactionTemplate.execute {
            requestContextHolder.set(platformContext(platformUserId, idempotencyKey))
            idempotencyPort.reserve(command("first"))
        }

        assertTrue(first is IdempotencyReservation.Started)

        val conflict = transactionTemplate.execute {
            requestContextHolder.set(platformContext(platformUserId, idempotencyKey))
            idempotencyPort.reserve(command("second"))
        }

        assertTrue(conflict is IdempotencyReservation.Conflict)
        assertEquals(first.recordId, conflict.recordId)
    }

    @Test
    fun returnsInProgressForSameKeyBeforeCompletion() {
        val platformUserId = UUID.randomUUID()
        val idempotencyKey = "idem-${UUID.randomUUID()}"

        val first = transactionTemplate.execute {
            requestContextHolder.set(platformContext(platformUserId, idempotencyKey))
            idempotencyPort.reserve(command("pending"))
        }

        val second = transactionTemplate.execute {
            requestContextHolder.set(platformContext(platformUserId, idempotencyKey))
            idempotencyPort.reserve(command("pending"))
        }

        assertTrue(first is IdempotencyReservation.Started)
        assertTrue(second is IdempotencyReservation.InProgress)
        assertEquals(first.recordId, second.recordId)
    }

    @Test
    fun requiresIdempotencyKeyHeader() {
        val platformUserId = UUID.randomUUID()

        val error = transactionTemplate.execute {
            requestContextHolder.set(platformContext(platformUserId, idempotencyKey = null))
            assertFailsWith<RequestContextException> {
                idempotencyPort.reserve(command("missing"))
            }
        }

        assertEquals("Idempotency-Key header is required", error.message)
    }

    @Test
    fun rejectsReserveOutsideTransaction() {
        requestContextHolder.set(
            platformContext(UUID.randomUUID(), "idem-${UUID.randomUUID()}"),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            idempotencyPort.reserve(command("outside-transaction"))
        }

        assertEquals(
            "Idempotency operations must run inside an active transaction",
            error.message,
        )
    }

    @Test
    fun reservesTenantScopedCommand() {
        val tenantId = UUID.randomUUID()
        val tenantUserId = UUID.randomUUID()
        val planId = UUID.randomUUID()
        val idempotencyKey = "idem-${UUID.randomUUID()}"

        val reservation = transactionTemplate.execute {
            insertPlan(planId)
            insertTenant(tenantId, planId)
            requestContextHolder.set(
                RequestContext(
                    identity = RequestIdentity.Tenant(
                        tenantId = tenantId,
                        tenantUserId = tenantUserId,
                        correlationId = "corr-tenant-idem",
                    ),
                    correlationId = "corr-tenant-idem",
                    idempotencyKey = idempotencyKey,
                    httpMethod = "POST",
                    requestPath = "/api/v1/tenants/$tenantId/users/invitations",
                ),
            )

            idempotencyPort.reserve(command("tenant-scope"))
        }

        assertTrue(reservation is IdempotencyReservation.Started)

        val tenantIdInDatabase = jdbcTemplate.queryForObject(
            "SELECT tenant_id FROM idempotency_keys WHERE id = ?",
            UUID::class.java,
            reservation.recordId,
        )

        assertEquals(tenantId, tenantIdInDatabase)
    }

    private fun platformContext(
        platformUserId: UUID,
        idempotencyKey: String?,
    ): RequestContext {
        return RequestContext(
            identity = RequestIdentity.Platform(
                platformUserId = platformUserId,
                correlationId = "corr-idem",
            ),
            correlationId = "corr-idem",
            idempotencyKey = idempotencyKey,
            httpMethod = "POST",
            requestPath = "/api/v1/platform/tenants",
        )
    }

    private fun command(
        slug: String,
    ): IdempotencyCommand {
        return IdempotencyCommand(
            operationType = "platform.tenant.create",
            requestPayload = mapOf("slug" to slug),
            resourceType = "tenants",
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
}
