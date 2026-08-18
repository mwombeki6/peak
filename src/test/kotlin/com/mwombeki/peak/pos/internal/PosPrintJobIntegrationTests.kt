package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.pos.api.AddPosOrderItemRequest
import com.mwombeki.peak.pos.api.CreatePosOrderRequest
import com.mwombeki.peak.pos.api.OpenPosSessionRequest
import com.mwombeki.peak.pos.api.PosConflictException
import com.mwombeki.peak.pos.api.PosPrintJobFailureRequest
import com.mwombeki.peak.pos.api.PosPrintJobReclaimRequest
import com.mwombeki.peak.pos.api.SendPosOrderRequest
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PosPrintJobIntegrationTests {
    @Autowired private lateinit var posOrderService: PosOrderService
    @Autowired private lateinit var posSessionService: PosSessionService
    @Autowired private lateinit var posKitchenService: PosKitchenService
    @Autowired private lateinit var printJobs: PosPrintJobService
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var requestContextHolder: RequestContextHolder

    @AfterTest
    fun clear() {
        requestContextHolder.clear()
    }

    @Test
    fun sendingToKitchenCreatesOnePendingPrintJob() {
        val fixture = seedTradingOutlet()
        val ticketId = sendKitchenTicket(fixture)
        val jobs = printJobs.listJobs(fixture.propertyId, "pending")
        assertEquals(1, jobs.size)
        assertEquals("pending", jobs.single().status)
        assertEquals("kitchen_ticket", jobs.single().jobType)
        assertEquals(ticketId, jobs.single().sourceId)
        assertEquals("kitchen_ticket", jobs.single().document["kind"])
        assertTrue((jobs.single().document["lines"] as List<*>).isNotEmpty())
    }

    @Test
    fun concurrentClaimsLeaveExactlyOneWinner() {
        val fixture = seedTradingOutlet()
        sendKitchenTicket(fixture)
        val jobId = printJobs.listJobs(fixture.propertyId, "pending").single().id
        val first = insertTill(fixture)
        val second = insertTill(fixture)

        val start = CountDownLatch(1)
        val ready = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val outcomes = listOf(first, second).map { till ->
                executor.submit<String> {
                    bind(fixture, "claim-${till.sessionId}", till.sessionId)
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    try {
                        printJobs.claim(fixture.propertyId, jobId).status
                    } catch (ex: PosConflictException) {
                        "conflict"
                    }
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = outcomes.map { it.get(30, TimeUnit.SECONDS) }.sorted()
            assertEquals(listOf("claimed", "conflict"), results)
        } finally {
            executor.shutdownNow()
        }

        bind(fixture, "claim-read", first.sessionId)
        val claimed = printJobs.listJobs(fixture.propertyId, "claimed").single()
        assertEquals(1, claimed.attempts)
        assertTrue(claimed.claimedByDeviceId == first.deviceId || claimed.claimedByDeviceId == second.deviceId)
    }

    @Test
    fun reclaimRequiresAReasonAndPrinterFailureDoesNotTouchTheTicket() {
        val fixture = seedTradingOutlet()
        val ticketId = sendKitchenTicket(fixture)
        val till = insertTill(fixture)
        bind(fixture, "claim-fail", till.sessionId)
        val jobId = printJobs.listJobs(fixture.propertyId, "pending").single().id
        printJobs.claim(fixture.propertyId, jobId)

        bind(fixture, "reclaim-blank", till.sessionId)
        val refused = assertFailsWith<IllegalArgumentException> {
            printJobs.reclaim(
                fixture.propertyId,
                jobId,
                PosPrintJobReclaimRequest(reason = "no"),
            )
        }
        assertTrue(refused.message!!.contains("Reclaim reason"), refused.message)

        bind(fixture, "fail-ack", till.sessionId)
        val failed = printJobs.failed(
            fixture.propertyId,
            jobId,
            PosPrintJobFailureRequest(error = "No kitchen printer is configured"),
        )
        assertEquals("failed", failed.status)

        val ticketStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM kitchen_tickets WHERE id = ?",
            String::class.java,
            ticketId,
        )
        val orderStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM pos_orders WHERE id = ?",
            String::class.java,
            fixture.orderId,
        )
        assertEquals("pending", ticketStatus)
        assertEquals("open", orderStatus)

        bind(fixture, "reprint", till.sessionId)
        val reprint = printJobs.reprint(fixture.propertyId, jobId)
        assertEquals("pending", reprint.status)
        assertTrue(reprint.reprint)
        assertEquals(jobId, reprint.reprintedFromJobId)
        assertNotEquals(jobId, reprint.id)
        assertEquals("failed", printJobs.listJobs(fixture.propertyId, "failed").single().status)
    }

    private fun sendKitchenTicket(fixture: TradingFixture): UUID {
        bind(fixture, "session")
        val session = posSessionService.openSession(
            fixture.propertyId,
            OpenPosSessionRequest(outletId = fixture.outletId, openingFloat = BigDecimal.ZERO),
        )
        bind(fixture, "create")
        val order = posOrderService.createOrder(
            fixture.propertyId,
            CreatePosOrderRequest(
                sessionId = session.id,
                orderType = "dine_in",
                tableNumber = "12",
                clientOperationId = "print-create-${fixture.tenantId}",
            ),
        )
        fixture.orderId = order.id
        bind(fixture, "item")
        posOrderService.addItem(
            fixture.propertyId,
            order.id,
            AddPosOrderItemRequest(
                menuItemId = fixture.menuItemId,
                quantity = BigDecimal.ONE,
                clientOperationId = "print-item-${fixture.tenantId}",
            ),
        )
        bind(fixture, "send")
        return posKitchenService.send(
            fixture.propertyId,
            order.id,
            SendPosOrderRequest(clientOperationId = "print-send-${fixture.tenantId}"),
        ).id
    }

    private fun seedTradingOutlet(): TradingFixture {
        val fixture = TradingFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            outletId = UUID.randomUUID(),
            menuItemId = UUID.randomUUID(),
        )
        val categoryId = UUID.randomUUID()
        val taxRateId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            fixture.planId, "Plan ${fixture.planId}", "plan-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            fixture.tenantId, "Tenant ${fixture.tenantId}", "tenant-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"), fixture.planId,
        )
        val platformUserId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO platform_users (id, full_name, email) VALUES (?, 'Test Verifier', ?)",
            platformUserId,
            "verifier-$platformUserId@example.test",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_profiles (
                tenant_id, legal_name, entity_type, business_phone, business_email,
                verification_status, verified_at, verified_by_platform_user_id
            ) VALUES (?, 'POS Test Business', 'limited_company', '+255700000000', ?,
                      'verified', now(), ?)
            """.trimIndent(),
            fixture.tenantId,
            "business-${fixture.tenantId}@example.test",
            platformUserId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Printer', ?, 'active', true)
            """.trimIndent(),
            fixture.userId, fixture.tenantId, "print-${fixture.userId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, status, is_active, total_rooms)
            VALUES (?, ?, 'Print Hotel', 'active', true, 0)
            """.trimIndent(),
            fixture.propertyId, fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO outlets (id, tenant_id, property_id, name, type, is_active)
            VALUES (?, ?, ?, 'Kitchen', 'RESTAURANT', true)
            """.trimIndent(),
            fixture.outletId, fixture.tenantId, fixture.propertyId,
        )
        jdbcTemplate.update(
            "INSERT INTO menu_categories (id, tenant_id, outlet_id, name) VALUES (?, ?, ?, 'Food')",
            categoryId, fixture.tenantId, fixture.outletId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tax_rates (
                id, tenant_id, name, code, rate, tax_type, applies_to, is_inclusive, is_active
            ) VALUES (?, ?, 'VAT', ?, 0.18, 'vat', ARRAY['food'], false, true)
            """.trimIndent(),
            taxRateId, fixture.tenantId, "VAT-$taxRateId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO menu_items (
                id, tenant_id, category_id, name, price, vat_rate, is_available, tax_rate_id
            ) VALUES (?, ?, ?, 'Stew', 10.00, 18.00, true, ?)
            """.trimIndent(),
            fixture.menuItemId, fixture.tenantId, categoryId, taxRateId,
        )
        return fixture
    }

    private fun insertTill(fixture: TradingFixture): Till {
        val deviceId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO paired_devices (
                id, tenant_id, property_id, outlet_id, device_code, public_key, key_fingerprint,
                terminal_name, mode, status, paired_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'Kitchen till', 'POS', 'active', ?)
            """.trimIndent(),
            deviceId, fixture.tenantId, fixture.propertyId, fixture.outletId,
            "dev_${deviceId.toString().take(8)}",
            "pk_$deviceId",
            "fp_${deviceId.toString().take(8)}",
            fixture.userId,
        )
        val tokenHash = sessionId.toString().replace("-", "") + "0".repeat(32)
        jdbcTemplate.update(
            """
            INSERT INTO operational_sessions (
                id, tenant_id, user_id, device_id, property_id, token_hash, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, now() + interval '1 hour')
            """.trimIndent(),
            sessionId, fixture.tenantId, fixture.userId, deviceId, fixture.propertyId,
            tokenHash.take(64),
        )
        return Till(deviceId, sessionId)
    }

    private fun bind(fixture: TradingFixture, idempotencyKey: String, sessionId: UUID? = null) {
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Tenant(fixture.tenantId, fixture.userId),
                correlationId = "corr-$idempotencyKey",
                idempotencyKey = idempotencyKey,
                httpMethod = "POST",
                requestPath = "/api/v1/properties/${fixture.propertyId}/pos-print-jobs",
                boundPropertyId = fixture.propertyId,
                boundOutletId = fixture.outletId,
                boundSessionId = sessionId,
            ),
        )
    }

    private data class TradingFixture(
        val planId: UUID,
        val tenantId: UUID,
        val userId: UUID,
        val propertyId: UUID,
        val outletId: UUID,
        val menuItemId: UUID,
        var orderId: UUID = UUID.randomUUID(),
    )

    private data class Till(val deviceId: UUID, val sessionId: UUID)
}
