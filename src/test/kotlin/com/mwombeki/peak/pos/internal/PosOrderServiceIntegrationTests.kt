package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.pos.api.AddPosOrderItemRequest
import com.mwombeki.peak.pos.api.ApprovePosVarianceRequest
import com.mwombeki.peak.pos.api.ClosePosSessionRequest
import com.mwombeki.peak.pos.api.CreatePosOrderRequest
import com.mwombeki.peak.pos.api.OpenPosSessionRequest
import com.mwombeki.peak.pos.api.SettlePosOrderRequest
import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxStatus
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PosOrderServiceIntegrationTests {
    @Autowired
    private lateinit var posOrderService: PosOrderService

    @Autowired
    private lateinit var posSessionService: PosSessionService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @Autowired
    private lateinit var posPaymentOutboxHandler: PosPaymentOutboxHandler

    private val createdTenantIds = mutableSetOf<UUID>()

    @AfterTest
    fun cleanOutboxAndContext() {
        createdTenantIds.forEach { tenantId ->
            jdbcTemplate.update("DELETE FROM outbox_events WHERE tenant_id = ?", tenantId)
        }
        createdTenantIds.clear()
        requestContextHolder.clear()
    }

    @Test
    fun `settles server-priced cash order and closes balanced session`() {
        val fixture = insertFixture()
        bind(fixture, "pos-open")
        val session = posSessionService.openSession(
            fixture.propertyId,
            OpenPosSessionRequest(
                outletId = fixture.outletId,
                openingFloat = BigDecimal("100.00"),
            ),
        )

        bind(fixture, "pos-order")
        val order = posOrderService.createOrder(
            fixture.propertyId,
            CreatePosOrderRequest(
                sessionId = session.id,
                orderType = "dine_in",
                tableNumber = "A1",
            ),
        )

        bind(fixture, "pos-item")
        val pricedOrder = posOrderService.addItem(
            fixture.propertyId,
            order.id,
            AddPosOrderItemRequest(
                menuItemId = fixture.menuItemId,
                quantity = BigDecimal("2"),
            ),
        )
        assertEquals(BigDecimal("20.00"), pricedOrder.subtotal)
        assertEquals(BigDecimal("3.60"), pricedOrder.taxAmount)
        assertEquals(BigDecimal("23.60"), pricedOrder.totalAmount)

        bind(fixture, "pos-settle")
        val settled = posOrderService.settleOrder(
            fixture.propertyId,
            order.id,
            SettlePosOrderRequest(paymentMethod = "cash"),
        )
        assertEquals("closed", settled.status)
        assertEquals("confirmed", settled.settlementStatus)
        assertNotNull(settled.paymentTransactionId)

        val transactionTarget = jdbcTemplate.queryForObject(
            "SELECT pos_order_id FROM payment_transactions WHERE tenant_id = ? AND id = ?",
            UUID::class.java,
            fixture.tenantId,
            settled.paymentTransactionId,
        )
        assertEquals(order.id, transactionTarget)

        bind(fixture, "pos-close")
        val closed = posSessionService.closeSession(
            fixture.propertyId,
            session.id,
            ClosePosSessionRequest(actualCash = BigDecimal("123.60")),
        )
        assertEquals("closed", closed.status)
        assertEquals(BigDecimal("123.60"), closed.expectedCash)
        assertEquals(BigDecimal("0.00"), closed.variance)

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "UPDATE pos_orders SET payment_transaction_id = ? WHERE tenant_id = ? AND id = ?",
                UUID.randomUUID(),
                fixture.tenantId,
                order.id,
            )
        }
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "UPDATE pos_sessions SET expected_cash = 0 WHERE tenant_id = ? AND id = ?",
                fixture.tenantId,
                session.id,
            )
        }
        val movementId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM cash_float_movements
            WHERE tenant_id = ? AND session_id = ?
            ORDER BY created_at
            LIMIT 1
            """.trimIndent(),
            UUID::class.java,
            fixture.tenantId,
            session.id,
        )
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "UPDATE cash_float_movements SET amount = 0 WHERE tenant_id = ? AND id = ?",
                fixture.tenantId,
                movementId,
            )
        }
    }

    @Test
    fun `replays order creation without duplicating order`() {
        val fixture = insertFixture()
        bind(fixture, "replay-open")
        val session = posSessionService.openSession(
            fixture.propertyId,
            OpenPosSessionRequest(fixture.outletId),
        )
        val request = CreatePosOrderRequest(session.id, "takeaway")

        bind(fixture, "replay-order")
        val first = posOrderService.createOrder(fixture.propertyId, request)
        bind(fixture, "replay-order")
        val replay = posOrderService.createOrder(fixture.propertyId, request)

        assertEquals(first.id, replay.id)
        assertTrue(replay.replayed)
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pos_orders WHERE tenant_id = ? AND id = ?",
                Int::class.java,
                fixture.tenantId,
                first.id,
            ),
        )
    }

    @Test
    fun `requires independent variance approval`() {
        val fixture = insertFixture()
        bind(fixture, "variance-open")
        val session = posSessionService.openSession(
            fixture.propertyId,
            OpenPosSessionRequest(
                outletId = fixture.outletId,
                openingFloat = BigDecimal("50.00"),
            ),
        )
        bind(fixture, "variance-close")
        val pending = posSessionService.closeSession(
            fixture.propertyId,
            session.id,
            ClosePosSessionRequest(actualCash = BigDecimal("45.00")),
        )
        assertEquals("pending_variance_approval", pending.status)

        bind(fixture, "variance-self")
        assertFailsWith<IllegalArgumentException> {
            posSessionService.approveVariance(
                fixture.propertyId,
                session.id,
                ApprovePosVarianceRequest("Confirmed cashier counting discrepancy"),
            )
        }

        val supervisorId = insertUser(fixture.tenantId)
        bind(fixture.copy(userId = supervisorId), "variance-supervisor")
        val closed = posSessionService.approveVariance(
            fixture.propertyId,
            session.id,
            ApprovePosVarianceRequest("Supervisor independently recounted and approved"),
        )
        assertEquals("closed", closed.status)
        assertEquals(supervisorId, closed.varianceApprovedBy)
    }

    @Test
    fun `closes pending order only after posted payment event`() {
        val fixture = insertFixture()
        bind(fixture, "mobile-open")
        val session = posSessionService.openSession(
            fixture.propertyId,
            OpenPosSessionRequest(fixture.outletId),
        )
        bind(fixture, "mobile-order")
        val order = posOrderService.createOrder(
            fixture.propertyId,
            CreatePosOrderRequest(session.id, "takeaway"),
        )
        bind(fixture, "mobile-item")
        val pricedOrder = posOrderService.addItem(
            fixture.propertyId,
            order.id,
            AddPosOrderItemRequest(fixture.menuItemId),
        )
        val transactionId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO payment_transactions (
                id, tenant_id, property_id, pos_order_id, initiated_by,
                transaction_direction, transaction_type, internal_reference,
                amount, currency, status, posted_at, confirmed_at
            )
            VALUES (?, ?, ?, ?, ?, 'inbound', 'collection', ?, ?, 'TZS',
                    'posted', now(), now())
            """.trimIndent(),
            transactionId,
            fixture.tenantId,
            fixture.propertyId,
            order.id,
            fixture.userId,
            "PAY-$transactionId",
            pricedOrder.totalAmount,
        )
        jdbcTemplate.update(
            """
            UPDATE pos_orders
            SET settlement_status = 'pending',
                settlement_method = 'mobile_money',
                payment_transaction_id = ?
            WHERE tenant_id = ? AND property_id = ? AND id = ?
            """.trimIndent(),
            transactionId,
            fixture.tenantId,
            fixture.propertyId,
            order.id,
        )

        val now = Instant.now()
        runBlocking {
            posPaymentOutboxHandler.handle(
                ClaimedOutboxEvent(
                    id = UUID.randomUUID(),
                    tenantId = fixture.tenantId,
                    propertyId = fixture.propertyId,
                    aggregateType = "payment_transactions",
                    aggregateId = transactionId,
                    eventType = "payment.transaction.posted",
                    destination = OutboxDestination.POS,
                    payload = "{}",
                    headers = "{}",
                    correlationId = "corr-mobile-posted",
                    idempotencyKeyId = null,
                    status = OutboxStatus.LOCKED,
                    priority = 1,
                    attemptCount = 1,
                    maxAttempts = 10,
                    nextAttemptAt = now,
                    lockedBy = "test-worker",
                    lockedAt = now,
                    deliveredAt = null,
                    failedAt = null,
                    errorMessage = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        bind(fixture, "mobile-read")
        val settled = posOrderService.getOrder(fixture.propertyId, order.id)
        assertEquals("closed", settled.status)
        assertEquals("confirmed", settled.settlementStatus)
        assertEquals(transactionId, settled.paymentTransactionId)
    }

    @Test
    fun `serializes concurrent item additions without losing totals`() {
        val fixture = insertFixture()
        bind(fixture, "parallel-open")
        val session = posSessionService.openSession(
            fixture.propertyId,
            OpenPosSessionRequest(fixture.outletId),
        )
        bind(fixture, "parallel-order")
        val order = posOrderService.createOrder(
            fixture.propertyId,
            CreatePosOrderRequest(session.id, "takeaway"),
        )

        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { index ->
                executor.submit {
                    bind(fixture, "parallel-item-$index")
                    check(start.await(10, TimeUnit.SECONDS))
                    posOrderService.addItem(
                        fixture.propertyId,
                        order.id,
                        AddPosOrderItemRequest(fixture.menuItemId),
                    )
                }
            }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        bind(fixture, "parallel-read")
        val result = posOrderService.getOrder(fixture.propertyId, order.id)
        assertEquals(2, result.items.size)
        assertEquals(BigDecimal("20.00"), result.subtotal)
        assertEquals(BigDecimal("3.60"), result.taxAmount)
        assertEquals(BigDecimal("23.60"), result.totalAmount)
    }

    private fun insertFixture(): PosFixture {
        val fixture = PosFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            outletId = UUID.randomUUID(),
            categoryId = UUID.randomUUID(),
            menuItemId = UUID.randomUUID(),
            taxRateId = UUID.randomUUID(),
        )
        createdTenantIds += fixture.tenantId
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            fixture.planId,
            "POS Plan ${fixture.planId}",
            "pos-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            fixture.tenantId,
            "POS Tenant ${fixture.tenantId}",
            "pos-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        insertUser(fixture.tenantId, fixture.userId)
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, status, is_active, total_rooms)
            VALUES (?, ?, 'POS Property', 'active', true, 0)
            """.trimIndent(),
            fixture.propertyId,
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO outlets (id, tenant_id, property_id, name, type, is_active)
            VALUES (?, ?, ?, 'Restaurant', 'RESTAURANT', true)
            """.trimIndent(),
            fixture.outletId,
            fixture.tenantId,
            fixture.propertyId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO menu_categories (id, tenant_id, outlet_id, name)
            VALUES (?, ?, ?, 'Food')
            """.trimIndent(),
            fixture.categoryId,
            fixture.tenantId,
            fixture.outletId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tax_rates (
                id, tenant_id, name, code, rate, tax_type, applies_to,
                is_inclusive, is_active
            )
            VALUES (?, ?, 'VAT', ?, 0.18, 'vat', ARRAY['food'], false, true)
            """.trimIndent(),
            fixture.taxRateId,
            fixture.tenantId,
            "VAT-${fixture.taxRateId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO menu_items (
                id, tenant_id, category_id, name, price, vat_rate,
                is_available, tax_rate_id
            )
            VALUES (?, ?, ?, 'Lunch', 10.00, 18.00, true, ?)
            """.trimIndent(),
            fixture.menuItemId,
            fixture.tenantId,
            fixture.categoryId,
            fixture.taxRateId,
        )
        return fixture
    }

    private fun insertUser(tenantId: UUID, userId: UUID = UUID.randomUUID()): UUID {
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'POS Operator', ?, 'active', true)
            """.trimIndent(),
            userId,
            tenantId,
            "pos-$userId@example.com",
        )
        return userId
    }

    private fun bind(fixture: PosFixture, idempotencyKey: String) {
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Tenant(fixture.tenantId, fixture.userId),
                correlationId = "corr-$idempotencyKey",
                idempotencyKey = idempotencyKey,
                httpMethod = "POST",
                requestPath = "/api/v1/properties/${fixture.propertyId}/pos",
            ),
        )
    }

    private data class PosFixture(
        val planId: UUID,
        val tenantId: UUID,
        val userId: UUID,
        val propertyId: UUID,
        val outletId: UUID,
        val categoryId: UUID,
        val menuItemId: UUID,
        val taxRateId: UUID,
    )
}
