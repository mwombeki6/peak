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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
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
    fun expiresAndReusesAnIdempotencyKey() {
        val platformUserId = UUID.randomUUID()
        val idempotencyKey = "idem-${UUID.randomUUID()}"
        val first = transactionTemplate.execute {
            requestContextHolder.set(platformContext(platformUserId, idempotencyKey))
            idempotencyPort.reserve(command("expired"))
        }
        assertTrue(first is IdempotencyReservation.Started)
        jdbcTemplate.update(
            "UPDATE idempotency_keys SET expires_at = now() - interval '1 second' WHERE id = ?",
            first.recordId,
        )

        val replacement = transactionTemplate.execute {
            requestContextHolder.set(platformContext(platformUserId, idempotencyKey))
            idempotencyPort.reserve(command("expired"))
        }

        assertTrue(replacement is IdempotencyReservation.Started)
        assertTrue(replacement.recordId != first.recordId)
        assertEquals(
            listOf("expired", "processing"),
            jdbcTemplate.queryForList(
                """
                SELECT status
                FROM idempotency_keys
                WHERE tenant_id IS NULL AND idempotency_key = ?
                ORDER BY created_at, id
                """.trimIndent(),
                String::class.java,
                idempotencyKey,
            ),
        )
    }

    @Test
    fun concurrentReservationsForSameKeyProduceSingleStartedRecord() {
        val platformUserId = UUID.randomUUID()
        val idempotencyKey = "idem-${UUID.randomUUID()}"
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        try {
            val futures = (1..2).map {
                executor.submit<IdempotencyReservation?> {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    try {
                        requireNotNull(
                            transactionTemplate.execute {
                                requestContextHolder.set(platformContext(platformUserId, idempotencyKey))
                                idempotencyPort.reserve(command("parallel"))
                            },
                        )
                    } catch (ex: DataAccessException) {
                        null
                    }
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { future ->
                try {
                    future.get(10, TimeUnit.SECONDS)
                } catch (ex: ExecutionException) {
                    throw ex.cause ?: ex
                }
            }.filterNotNull()

            assertEquals(1, results.count { it is IdempotencyReservation.Started })
            assertEquals(
                1,
                jdbcTemplate.queryForObject(
                    """
                    SELECT count(*)
                    FROM idempotency_keys
                    WHERE tenant_id IS NULL
                      AND idempotency_key = ?
                    """.trimIndent(),
                    Int::class.java,
                    idempotencyKey,
                ),
            )
        } finally {
            executor.shutdownNow()
        }
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
    fun twoOnboardingApplicantsReusingTheSameKeyAreCompletelyIndependent() {
        val applicationA = UUID.randomUUID()
        val applicationB = UUID.randomUUID()
        val sharedKey = "idem-${UUID.randomUUID()}"

        val startedA = transactionTemplate.execute {
            requestContextHolder.set(onboardingApplicantContext(applicationA, sharedKey))
            idempotencyPort.reserve(command("applicant-a"))
        }
        val startedB = transactionTemplate.execute {
            requestContextHolder.set(onboardingApplicantContext(applicationB, sharedKey))
            idempotencyPort.reserve(command("applicant-b"))
        }

        assertTrue(startedA is IdempotencyReservation.Started)
        assertTrue(startedB is IdempotencyReservation.Started)
        assertTrue(startedA.recordId != startedB.recordId)

        transactionTemplate.executeWithoutResult {
            requestContextHolder.set(onboardingApplicantContext(applicationA, sharedKey))
            idempotencyPort.markSucceeded(startedA.recordId, 201, mapOf("id" to "case-a"), null)
        }

        // B's own key is still untouched — no replay of A's response, no conflict either.
        val stillA = transactionTemplate.execute {
            requestContextHolder.set(onboardingApplicantContext(applicationA, sharedKey))
            idempotencyPort.reserve(command("applicant-a"))
        }
        assertTrue(stillA is IdempotencyReservation.Replay)
        assertTrue(stillA.responseBody.orEmpty().contains("case-a"))

        val stillB = transactionTemplate.execute {
            requestContextHolder.set(onboardingApplicantContext(applicationB, sharedKey))
            idempotencyPort.reserve(command("applicant-b"))
        }
        assertTrue(stillB is IdempotencyReservation.InProgress)
        assertEquals(startedB.recordId, stillB.recordId)
    }

    @Test
    fun twoPlatformActorsReusingTheSameKeyAreIndependent() {
        val platformUserA = UUID.randomUUID()
        val platformUserB = UUID.randomUUID()
        val sharedKey = "idem-${UUID.randomUUID()}"

        val startedA = transactionTemplate.execute {
            requestContextHolder.set(platformContext(platformUserA, sharedKey))
            idempotencyPort.reserve(command("platform-a"))
        }
        val startedB = transactionTemplate.execute {
            requestContextHolder.set(platformContext(platformUserB, sharedKey))
            idempotencyPort.reserve(command("platform-b"))
        }

        assertTrue(startedA is IdempotencyReservation.Started)
        assertTrue(startedB is IdempotencyReservation.Started)
        assertTrue(startedA.recordId != startedB.recordId)
    }

    @Test
    fun twoTenantsReusingTheSameKeyAreIsolated() {
        val planId = UUID.randomUUID()
        val tenantA = UUID.randomUUID()
        val tenantB = UUID.randomUUID()
        val sharedKey = "idem-${UUID.randomUUID()}"

        val startedA = transactionTemplate.execute {
            insertPlan(planId)
            insertTenant(tenantA, planId)
            insertTenant(tenantB, planId)
            requestContextHolder.set(tenantContext(tenantA, UUID.randomUUID(), sharedKey))
            idempotencyPort.reserve(command("tenant-a"))
        }
        val startedB = transactionTemplate.execute {
            requestContextHolder.set(tenantContext(tenantB, UUID.randomUUID(), sharedKey))
            idempotencyPort.reserve(command("tenant-b"))
        }

        assertTrue(startedA is IdempotencyReservation.Started)
        assertTrue(startedB is IdempotencyReservation.Started)
        assertTrue(startedA.recordId != startedB.recordId)
    }

    @Test
    fun twoUsersInTheSameTenantReusingTheSameKeyDoNotReplayEachOther() {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val sharedKey = "idem-${UUID.randomUUID()}"

        val startedA = transactionTemplate.execute {
            insertPlan(planId)
            insertTenant(tenantId, planId)
            requestContextHolder.set(tenantContext(tenantId, userA, sharedKey))
            idempotencyPort.reserve(command("user-a"))
        }
        transactionTemplate.executeWithoutResult {
            requestContextHolder.set(tenantContext(tenantId, userA, sharedKey))
            idempotencyPort.markSucceeded(
                requireNotNull(startedA).recordId, 201, mapOf("id" to "user-a-result"), null,
            )
        }

        // Same tenant, same key, a different tenant user — must not see user A's cached result.
        val startedB = transactionTemplate.execute {
            requestContextHolder.set(tenantContext(tenantId, userB, sharedKey))
            idempotencyPort.reserve(command("user-a"))
        }
        assertTrue(startedB is IdempotencyReservation.Started)
        assertTrue(startedB.recordId != requireNotNull(startedA).recordId)
    }

    @Test
    fun concurrentDifferentActorsSharingAKeyRemainIndependent() {
        val platformUserA = UUID.randomUUID()
        val platformUserB = UUID.randomUUID()
        val sharedKey = "idem-${UUID.randomUUID()}"
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        try {
            val futures = listOf(platformUserA, platformUserB).map { userId ->
                executor.submit<IdempotencyReservation?> {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    transactionTemplate.execute {
                        requestContextHolder.set(platformContext(userId, sharedKey))
                        idempotencyPort.reserve(command("actor-$userId"))
                    }
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(2, results.count { it is IdempotencyReservation.Started })
            assertEquals(
                2,
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?",
                    Int::class.java,
                    sharedKey,
                ),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun guestsInDifferentTenantsReusingTheSameKeyAreIndependentNotGloballyShared() {
        val planId = UUID.randomUUID()
        val tenantA = UUID.randomUUID()
        val tenantB = UUID.randomUUID()
        val propertyA = UUID.randomUUID()
        val propertyB = UUID.randomUUID()
        val sharedKey = "idem-${UUID.randomUUID()}"

        val startedA = transactionTemplate.execute {
            insertPlan(planId)
            insertTenant(tenantA, planId)
            insertTenant(tenantB, planId)
            insertProperty(propertyA, tenantA)
            insertProperty(propertyB, tenantB)
            requestContextHolder.set(guestContext(tenantA, propertyA, sharedKey))
            idempotencyPort.reserve(command("guest-a"))
        }
        val startedB = transactionTemplate.execute {
            requestContextHolder.set(guestContext(tenantB, propertyB, sharedKey))
            idempotencyPort.reserve(command("guest-b"))
        }

        assertTrue(startedA is IdempotencyReservation.Started)
        assertTrue(startedB is IdempotencyReservation.Started)
        assertTrue(startedA.recordId != startedB.recordId)
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

    private fun tenantContext(
        tenantId: UUID,
        tenantUserId: UUID,
        idempotencyKey: String?,
    ): RequestContext {
        return RequestContext(
            identity = RequestIdentity.Tenant(
                tenantId = tenantId,
                tenantUserId = tenantUserId,
                correlationId = "corr-idem",
            ),
            correlationId = "corr-idem",
            idempotencyKey = idempotencyKey,
            httpMethod = "POST",
            requestPath = "/api/v1/tenants/$tenantId/users/invitations",
        )
    }

    private fun onboardingApplicantContext(
        applicationId: UUID,
        idempotencyKey: String?,
    ): RequestContext {
        return RequestContext(
            identity = RequestIdentity.OnboardingApplicant(
                applicationId = applicationId,
                correlationId = "corr-idem",
            ),
            correlationId = "corr-idem",
            idempotencyKey = idempotencyKey,
            httpMethod = "POST",
            requestPath = "/api/v1/onboarding/me/verification-cases",
        )
    }

    private fun guestContext(
        tenantId: UUID,
        propertyId: UUID,
        idempotencyKey: String?,
    ): RequestContext {
        return RequestContext(
            identity = RequestIdentity.Public(
                tenantId = tenantId,
                propertyId = propertyId,
                correlationId = "corr-idem",
            ),
            correlationId = "corr-idem",
            idempotencyKey = idempotencyKey,
            httpMethod = "POST",
            requestPath = "/api/v1/public/properties/$propertyId/reservations",
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

    private fun insertProperty(id: UUID, tenantId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO properties (id, tenant_id, name, code) VALUES (?, ?, ?, ?)",
            id,
            tenantId,
            "Property $id",
            "P${id.toString().take(8)}",
        )
    }
}
