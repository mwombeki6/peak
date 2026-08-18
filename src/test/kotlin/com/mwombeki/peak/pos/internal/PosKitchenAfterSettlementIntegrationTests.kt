package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.pos.api.AddPosOrderItemRequest
import com.mwombeki.peak.pos.api.CreatePosOrderRequest
import com.mwombeki.peak.pos.api.OpenPosSessionRequest
import com.mwombeki.peak.pos.api.SendPosOrderRequest
import com.mwombeki.peak.pos.api.SettlePosOrderRequest
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.math.BigDecimal
import java.util.UUID
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
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Paying before the food arrives is normal, and the kitchen still has to finish the job.
 *
 * At a bar the drink is paid for and then poured. Takeaway is paid at the counter and collected
 * later. In both, settlement happens first and the kitchen's work happens after — so the ticket
 * must still be able to reach `ready` and `delivered`.
 *
 * `V40` froze `pos_order_items` once `settlement_status` left `('unsettled','failed')`, so that
 * a bill cannot change after money has moved. That is right for quantity, price and tax. It is
 * wrong for `service_state`, which records whether food reached the table and has no financial
 * meaning at all. The two were never distinguished, so the guard caught both and the kitchen's
 * next action threw a database exception.
 *
 * Nothing caught it because no test has ever settled an order and then advanced its ticket.
 * Every POS test settles last.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PosKitchenAfterSettlementIntegrationTests {

    @Autowired private lateinit var posOrderService: PosOrderService
    @Autowired private lateinit var posSessionService: PosSessionService
    @Autowired private lateinit var posKitchenService: PosKitchenService
    @Autowired private lateinit var requestContextHolder: RequestContextHolder
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
        jdbcTemplate.execute("RESET ALL")
    }

    /** The bar sequence: pay, then pour, then hand over. */
    @Test
    fun aPaidOrderCanStillBeMarkedReadyAndDelivered() {
        val order = orderSentToKitchen()
        settleCash(order)

        transition(order, "prepare")
        transition(order, "ready")
        transition(order, "deliver")

        assertEquals("delivered", itemServiceState(order.orderItemId))
    }

    /**
     * Mobile money leaves the order `open` with `settlement_status = 'pending'` while the guest
     * answers their handset — which is exactly when the kitchen is cooking. That state was
     * caught by the guard too, so the most common rail was the most affected.
     */
    @Test
    fun anOrderAwaitingMobileMoneyCanStillProgressInTheKitchen() {
        val order = orderSentToKitchen()
        // chk_pos_orders_settlement_state binds the whole tuple, so a 'pending' order must
        // carry the transaction the guest is being asked to approve. This is the state
        // initiatePosMobileMoney leaves behind while the handset is ringing.
        val transactionId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO payment_transactions (
                id, tenant_id, property_id, pos_order_id, initiated_by,
                transaction_direction, transaction_type, internal_reference,
                payer_identifier, amount, currency, status
            ) VALUES (?, ?, ?, ?, ?, 'inbound', 'collection', ?,
                      '+255754123456', 5.90, 'TZS', 'pending')
            """.trimIndent(),
            transactionId, order.tenantId, order.propertyId, order.orderId, order.userId,
            "PEAK-" + UUID.randomUUID().toString().replace("-", "").take(20).uppercase(),
        )
        jdbcTemplate.update(
            """
            UPDATE pos_orders
            SET settlement_status = 'pending',
                settlement_method = 'mobile_money',
                payment_transaction_id = ?
            WHERE id = ?
            """.trimIndent(),
            transactionId, order.orderId,
        )

        transition(order, "prepare")
        transition(order, "ready")

        assertEquals("ready", itemServiceState(order.orderItemId))
    }

    /**
     * The guard still has a job. Everything that decides what the guest owes stays frozen once
     * money has moved — this is the half of V40 that was always correct.
     */
    @Test
    fun aPaidOrdersPriceAndQuantityStayFrozen() {
        val order = orderSentToKitchen()
        settleCash(order)

        listOf(
            "UPDATE pos_order_items SET quantity = quantity + 1 WHERE id = ?",
            "UPDATE pos_order_items SET unit_price = unit_price + 1 WHERE id = ?",
            "UPDATE pos_order_items SET total_price = total_price + 1 WHERE id = ?",
            "UPDATE pos_order_items SET tax_amount = tax_amount + 1 WHERE id = ?",
        ).forEach { statement ->
            val refused = assertFailsWith<DataAccessException>("$statement should be refused") {
                jdbcTemplate.update(statement, order.orderItemId)
            }
            assertTrue(refused.message!!.contains("immutable"), refused.message!!)
        }
    }

    /** A settled order must not grow a new line. That was never the bug. */
    @Test
    fun aPaidOrderStillRefusesNewItems() {
        val order = orderSentToKitchen()
        settleCash(order)

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                """
                INSERT INTO pos_order_items (
                    id, tenant_id, order_id, menu_item_id, item_name, quantity,
                    unit_price, subtotal, tax_amount, total_price, client_operation_id
                ) SELECT gen_random_uuid(), tenant_id, order_id, menu_item_id, item_name,
                         quantity, unit_price, subtotal, tax_amount, total_price, 'sneaky'
                  FROM pos_order_items WHERE id = ?
                """.trimIndent(),
                order.orderItemId,
            )
        }
    }

    /** Deletion is never allowed, settled or not. */
    @Test
    fun anItemCanNeverBeDeleted() {
        val order = orderSentToKitchen()

        val refused = assertFailsWith<DataAccessException> {
            jdbcTemplate.update("DELETE FROM pos_order_items WHERE id = ?", order.orderItemId)
        }
        assertTrue(refused.message!!.contains("void the item instead"), refused.message!!)
    }

    private fun transition(order: Order, action: String) {
        bind(order, "idem-$action-${UUID.randomUUID()}")
        posKitchenService.transition(order.propertyId, order.ticketId, action, null)
    }

    private fun settleCash(order: Order) {
        bind(order, "idem-settle-${order.orderId}")
        posOrderService.settleOrder(
            order.propertyId,
            order.orderId,
            SettlePosOrderRequest(paymentMethod = "cash"),
        )
    }

    private fun itemServiceState(itemId: UUID): String? =
        jdbcTemplate.queryForObject(
            "SELECT service_state FROM pos_order_items WHERE id = ?",
            String::class.java,
            itemId,
        )

    private fun orderSentToKitchen(): Order {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val propertyId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val outletId = UUID.randomUUID()
        val categoryId = UUID.randomUUID()
        val menuItemId = UUID.randomUUID()
        val taxRateId = UUID.randomUUID()

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId, "Plan $planId", "plan-$planId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            tenantId, "Tenant $tenantId", "tenant-$tenantId",
            "tenant_$tenantId".replace("-", "_"), planId,
        )
        verifyTenantBusiness(tenantId)
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Bartender', ?, 'active', true)
            """.trimIndent(),
            userId, tenantId, "bar-$userId@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, status, is_active, total_rooms)
            VALUES (?, ?, 'Bar Hotel', 'active', true, 0)
            """.trimIndent(),
            propertyId, tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO outlets (id, tenant_id, property_id, name, type, is_active)
            VALUES (?, ?, ?, 'Bar', 'BAR', true)
            """.trimIndent(),
            outletId, tenantId, propertyId,
        )
        jdbcTemplate.update(
            "INSERT INTO menu_categories (id, tenant_id, outlet_id, name) VALUES (?, ?, ?, 'Drinks')",
            categoryId, tenantId, outletId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tax_rates (
                id, tenant_id, name, code, rate, tax_type, applies_to, is_inclusive, is_active
            ) VALUES (?, ?, 'VAT', ?, 0.18, 'vat', ARRAY['food'], false, true)
            """.trimIndent(),
            taxRateId, tenantId, "VAT-$taxRateId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO menu_items (
                id, tenant_id, category_id, name, price, vat_rate, is_available, tax_rate_id
            ) VALUES (?, ?, ?, 'Beer', 5.00, 18.00, true, ?)
            """.trimIndent(),
            menuItemId, tenantId, categoryId, taxRateId,
        )

        val stub = Order(tenantId, propertyId, userId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        bind(stub, "idem-session-$tenantId")
        val session = posSessionService.openSession(
            propertyId,
            OpenPosSessionRequest(outletId = outletId, openingFloat = BigDecimal.ZERO),
        )
        bind(stub, "idem-create-$tenantId")
        val order = posOrderService.createOrder(
            propertyId,
            CreatePosOrderRequest(
                sessionId = session.id,
                orderType = "dine_in",
                clientOperationId = "kitchen-after-settle-create",
            ),
        )
        bind(stub, "idem-item-$tenantId")
        posOrderService.addItem(
            propertyId,
            order.id,
            AddPosOrderItemRequest(
                menuItemId = menuItemId,
                quantity = BigDecimal.ONE,
                clientOperationId = "kitchen-after-settle-item",
            ),
        )
        bind(stub, "idem-send-$tenantId")
        val ticket = posKitchenService.send(
            propertyId,
            order.id,
            SendPosOrderRequest(clientOperationId = "kitchen-after-settle-send"),
        )

        val itemId = jdbcTemplate.queryForObject(
            "SELECT id FROM pos_order_items WHERE order_id = ?",
            UUID::class.java,
            order.id,
        )!!

        return stub.copy(orderId = order.id, ticketId = ticket.id, orderItemId = itemId)
    }

    /** PosCommandExecutor now requires business verification before any POS mutation. */
    private fun verifyTenantBusiness(tenantId: UUID) {
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
            tenantId,
            "business-$tenantId@example.test",
            platformUserId,
        )
    }

    private fun bind(order: Order, idempotencyKey: String) {
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Tenant(order.tenantId, order.userId),
                correlationId = "corr-$idempotencyKey",
                idempotencyKey = idempotencyKey,
                httpMethod = "POST",
                requestPath = "/api/v1/properties/${order.propertyId}/pos-orders",
            ),
        )
    }

    private data class Order(
        val tenantId: UUID,
        val propertyId: UUID,
        val userId: UUID,
        val orderId: UUID,
        val ticketId: UUID,
        val orderItemId: UUID,
    )
}
