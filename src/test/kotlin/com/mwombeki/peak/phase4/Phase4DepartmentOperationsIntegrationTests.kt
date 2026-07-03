package com.mwombeki.peak.phase4

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.housekeeping.api.AssignHousekeepingTaskRequest
import com.mwombeki.peak.housekeeping.api.CompleteHousekeepingTaskRequest
import com.mwombeki.peak.housekeeping.api.CreateHousekeepingTaskRequest
import com.mwombeki.peak.housekeeping.api.HousekeepingConflictException
import com.mwombeki.peak.housekeeping.api.HousekeepingTaskStatus
import com.mwombeki.peak.housekeeping.api.HousekeepingTaskType
import com.mwombeki.peak.housekeeping.api.InspectHousekeepingTaskRequest
import com.mwombeki.peak.housekeeping.api.UpdateHousekeepingSettingsRequest
import com.mwombeki.peak.housekeeping.internal.HousekeepingService
import com.mwombeki.peak.housekeeping.internal.HousekeepingCheckoutOutboxHandler
import com.mwombeki.peak.inventory.api.CreateInventoryItemRequest
import com.mwombeki.peak.inventory.api.CreateInventoryLocationRequest
import com.mwombeki.peak.inventory.api.InventoryConflictException
import com.mwombeki.peak.inventory.api.RecipeComponentRequest
import com.mwombeki.peak.inventory.api.StockAdjustmentLine
import com.mwombeki.peak.inventory.api.StockAdjustmentRequest
import com.mwombeki.peak.inventory.api.StockCommandLine
import com.mwombeki.peak.inventory.api.StockCommandRequest
import com.mwombeki.peak.inventory.api.TransferLine
import com.mwombeki.peak.inventory.api.TransferStockRequest
import com.mwombeki.peak.inventory.api.UpsertRecipeRequest
import com.mwombeki.peak.inventory.internal.InventoryService
import com.mwombeki.peak.maintenance.api.CreateRoomBlockRequest
import com.mwombeki.peak.maintenance.api.MaintenanceReasonRequest
import com.mwombeki.peak.maintenance.api.RoomBlockStatus
import com.mwombeki.peak.maintenance.api.RoomBlockType
import com.mwombeki.peak.maintenance.internal.MaintenanceService
import com.mwombeki.peak.pos.api.AddPosOrderItemRequest
import com.mwombeki.peak.pos.api.CreatePosOrderRequest
import com.mwombeki.peak.pos.api.OpenPosSessionRequest
import com.mwombeki.peak.pos.api.PosVoidDisposition
import com.mwombeki.peak.pos.api.SendPosOrderRequest
import com.mwombeki.peak.pos.api.VoidPosOrderItemRequest
import com.mwombeki.peak.pos.internal.PosKitchenService
import com.mwombeki.peak.pos.internal.PosOrderService
import com.mwombeki.peak.pos.internal.PosSessionService
import com.mwombeki.peak.procurement.api.CreatePurchaseOrderRequest
import com.mwombeki.peak.procurement.api.CreatePurchaseReceiptRequest
import com.mwombeki.peak.procurement.api.CreateSupplierRequest
import com.mwombeki.peak.procurement.api.PurchaseOrderLineRequest
import com.mwombeki.peak.procurement.api.PurchaseOrderStatus
import com.mwombeki.peak.procurement.api.PurchaseReceiptLineRequest
import com.mwombeki.peak.procurement.internal.ProcurementService
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class Phase4DepartmentOperationsIntegrationTests {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var contexts: RequestContextHolder
    @Autowired lateinit var inventory: InventoryService
    @Autowired lateinit var housekeeping: HousekeepingService
    @Autowired lateinit var maintenance: MaintenanceService
    @Autowired lateinit var procurement: ProcurementService
    @Autowired lateinit var posSessions: PosSessionService
    @Autowired lateinit var posOrders: PosOrderService
    @Autowired lateinit var kitchen: PosKitchenService
    @Autowired lateinit var transaction: TransactionTemplate
    @Autowired lateinit var checkoutHandler: HousekeepingCheckoutOutboxHandler
    private val createdTenantIds = mutableSetOf<UUID>()

    @AfterTest
    fun clearContext() {
        createdTenantIds.forEach {
            jdbc.update("DELETE FROM outbox_events WHERE tenant_id = ?", it)
        }
        createdTenantIds.clear()
        contexts.clear()
    }

    @Test
    fun `weighted average rejects negative stock and transfers preserve value`() {
        val f = fixture()
        bind(f, f.userId, "inventory-item")
        val item = inventory.createItem(
            f.propertyId,
            CreateInventoryItemRequest("Rice", "RICE", "Food", "kg", BigDecimal("2")),
        )
        bind(f, f.userId, "inventory-source")
        val source = inventory.createLocation(
            f.propertyId, CreateInventoryLocationRequest("Main Store", "store"),
        )
        bind(f, f.userId, "inventory-target")
        val target = inventory.createLocation(
            f.propertyId, CreateInventoryLocationRequest("Kitchen", "kitchen", f.outletId),
        )
        bind(f, f.userId, "opening")
        inventory.openingBalances(
            f.propertyId,
            StockCommandRequest(
                listOf(StockCommandLine(item.id, source.id, BigDecimal("10"), BigDecimal("10"))),
            ),
        )
        bind(f, f.userId, "positive-adjust")
        inventory.adjust(
            f.propertyId,
            StockAdjustmentRequest(
                listOf(
                    StockAdjustmentLine(
                        item.id, source.id, BigDecimal("10"), BigDecimal("20"),
                    ),
                ),
                "Counted delivery",
            ),
        )
        assertEquals(
            BigDecimal("15.000000"),
            inventory.levels(f.propertyId).single().averageCost,
        )

        bind(f, f.userId, "negative-fails")
        assertFailsWith<InventoryConflictException> {
            inventory.waste(
                f.propertyId,
                StockCommandRequest(
                    listOf(StockCommandLine(item.id, source.id, BigDecimal("21"))),
                    "Spoilage",
                ),
            )
        }

        bind(f, f.userId, "transfer")
        val moved = inventory.transfer(
            f.propertyId,
            TransferStockRequest(
                source.id, target.id, listOf(TransferLine(item.id, BigDecimal("5"))),
            ),
        )
        assertEquals(2, moved.movements.size)
        assertEquals(moved.movements[1].id, moved.movements[0].pairedMovementId)
        assertEquals(moved.movements[0].id, moved.movements[1].pairedMovementId)
        val levels = inventory.levels(f.propertyId).associateBy { it.locationId }
        assertEquals(BigDecimal("15.000"), levels.getValue(source.id).quantity)
        assertEquals(BigDecimal("5.000"), levels.getValue(target.id).quantity)
        assertEquals(
            BigDecimal("300.00"),
            levels.values.sumOf { it.stockValue },
        )
    }

    @Test
    fun `inspection requires another user and maintenance release leaves room dirty`() {
        val f = fixture(roomStatus = "vacant_dirty")
        bind(f, f.userId, "hk-settings")
        housekeeping.updateSettings(
            f.propertyId, UpdateHousekeepingSettingsRequest(true),
        )
        bind(f, f.userId, "hk-create")
        val task = housekeeping.createTask(
            f.propertyId,
            CreateHousekeepingTaskRequest(
                f.roomId, HousekeepingTaskType.DEPARTURE_CLEAN, LocalDate.now(),
            ),
        )
        bind(f, f.userId, "hk-assign")
        housekeeping.assignTask(
            f.propertyId, task.id, AssignHousekeepingTaskRequest(f.userId),
        )
        bind(f, f.userId, "hk-start")
        housekeeping.startTask(f.propertyId, task.id)
        bind(f, f.userId, "hk-complete")
        val awaiting = housekeeping.completeTask(
            f.propertyId, task.id, CompleteHousekeepingTaskRequest(),
        )
        assertEquals(HousekeepingTaskStatus.AWAITING_INSPECTION, awaiting.status)

        bind(f, f.userId, "hk-self-inspect")
        assertFailsWith<HousekeepingConflictException> {
            housekeeping.inspectTask(
                f.propertyId, task.id, InspectHousekeepingTaskRequest(true),
            )
        }
        bind(f, f.supervisorId, "hk-inspect")
        val completed = housekeeping.inspectTask(
            f.propertyId, task.id, InspectHousekeepingTaskRequest(true),
        )
        assertEquals(HousekeepingTaskStatus.COMPLETED, completed.status)
        assertEquals("vacant_clean", roomStatus(f))

        bind(f, f.supervisorId, "room-block")
        val block = maintenance.blockRoom(
            f.propertyId, f.roomId,
            CreateRoomBlockRequest(type = RoomBlockType.OUT_OF_ORDER, reason = "Air conditioner failed"),
        )
        assertEquals("out_of_order", roomStatus(f))
        bind(f, f.supervisorId, "room-release")
        val released = maintenance.releaseBlock(
            f.propertyId, block.id, MaintenanceReasonRequest("Repair verified"),
        )
        assertEquals(RoomBlockStatus.RELEASED, released.status)
        assertEquals("vacant_dirty", roomStatus(f))
    }

    @Test
    fun `purchase order approval is separated and partial receipts cannot exceed remaining`() {
        val f = fixture()
        bind(f, f.userId, "proc-item")
        val item = inventory.createItem(
            f.propertyId, CreateInventoryItemRequest("Coffee", "COFFEE", "Food", "kg"),
        )
        bind(f, f.userId, "proc-location")
        val location = inventory.createLocation(
            f.propertyId, CreateInventoryLocationRequest("Receiving", "store"),
        )
        bind(f, f.userId, "supplier")
        val supplier = procurement.createSupplier(
            f.propertyId, CreateSupplierRequest("Coffee Cooperative", "COOP"),
        )
        bind(f, f.userId, "po-create")
        val po = procurement.createPurchaseOrder(
            f.propertyId,
            CreatePurchaseOrderRequest(
                supplier.id,
                lines = listOf(
                    PurchaseOrderLineRequest(item.id, BigDecimal("10"), BigDecimal("4.25")),
                ),
            ),
        )
        bind(f, f.userId, "po-submit")
        procurement.transitionPurchaseOrder(f.propertyId, po.id, "submit", null)
        bind(f, f.userId, "po-self-approve")
        assertFails {
            procurement.transitionPurchaseOrder(f.propertyId, po.id, "approve", null)
        }
        bind(f, f.supervisorId, "po-approve")
        val approved = procurement.transitionPurchaseOrder(f.propertyId, po.id, "approve", null)
        assertNotEquals(approved.createdBy, approved.approvedBy)

        bind(f, f.userId, "receipt-one")
        procurement.receivePurchaseOrder(
            f.propertyId, po.id,
            CreatePurchaseReceiptRequest(
                "DELIVERY-1",
                listOf(PurchaseReceiptLineRequest(po.lines.single().id, location.id, BigDecimal("4"))),
            ),
        )
        assertEquals(
            PurchaseOrderStatus.PARTIALLY_RECEIVED,
            procurement.getPurchaseOrder(f.propertyId, po.id).status,
        )
        bind(f, f.userId, "receipt-excess")
        assertFails {
            procurement.receivePurchaseOrder(
                f.propertyId, po.id,
                CreatePurchaseReceiptRequest(
                    "DELIVERY-2",
                    listOf(PurchaseReceiptLineRequest(po.lines.single().id, location.id, BigDecimal("7"))),
                ),
            )
        }
        bind(f, f.userId, "receipt-final")
        procurement.receivePurchaseOrder(
            f.propertyId, po.id,
            CreatePurchaseReceiptRequest(
                "DELIVERY-3",
                listOf(PurchaseReceiptLineRequest(po.lines.single().id, location.id, BigDecimal("6"))),
            ),
        )
        assertEquals(
            PurchaseOrderStatus.RECEIVED,
            procurement.getPurchaseOrder(f.propertyId, po.id).status,
        )
        assertEquals(BigDecimal("10.000"), inventory.levels(f.propertyId).single().quantity)
    }

    @Test
    fun `duplicate kitchen send consumes once and post-send return is compensating`() {
        val f = fixture()
        bind(f, f.userId, "kds-item")
        val stockItem = inventory.createItem(
            f.propertyId, CreateInventoryItemRequest("Beans", "BEANS", "Food", "kg"),
        )
        bind(f, f.userId, "kds-location")
        val location = inventory.createLocation(
            f.propertyId, CreateInventoryLocationRequest("Line Kitchen", "kitchen", f.outletId),
        )
        bind(f, f.userId, "kds-opening")
        inventory.openingBalances(
            f.propertyId,
            StockCommandRequest(
                listOf(StockCommandLine(stockItem.id, location.id, BigDecimal("10"), BigDecimal("5"))),
            ),
        )
        bind(f, f.userId, "kds-recipe")
        inventory.upsertRecipe(
            f.propertyId,
            UpsertRecipeRequest(
                f.menuItemId,
                listOf(RecipeComponentRequest(stockItem.id, location.id, BigDecimal("0.500"), "kg")),
            ),
        )
        bind(f, f.userId, "kds-session")
        val session = posSessions.openSession(
            f.propertyId, OpenPosSessionRequest(f.outletId),
        )
        bind(f, f.userId, "kds-order")
        val order = posOrders.createOrder(
            f.propertyId,
            CreatePosOrderRequest(
                session.id, "dine_in", clientOperationId = "offline-order-1",
            ),
        )
        bind(f, f.userId, "kds-add")
        val withItem = posOrders.addItem(
            f.propertyId, order.id,
            AddPosOrderItemRequest(
                f.menuItemId, BigDecimal("2"), clientOperationId = "offline-add-1",
            ),
        )
        bind(f, f.userId, "kds-send")
        val ticket = kitchen.send(
            f.propertyId, order.id, SendPosOrderRequest("offline-send-1"),
        )
        bind(f, f.userId, "kds-send")
        val replay = kitchen.send(
            f.propertyId, order.id, SendPosOrderRequest("offline-send-1"),
        )
        assertEquals(ticket.id, replay.id)
        assertEquals(BigDecimal("9.000"), inventory.levels(f.propertyId).single().quantity)
        assertEquals(
            1,
            jdbc.queryForObject(
                """
                SELECT count(*) FROM stock_movements
                WHERE tenant_id = ? AND source_type = 'kitchen_ticket' AND source_id = ?
                """.trimIndent(),
                Int::class.java, f.tenantId, ticket.id,
            ),
        )

        bind(f, f.supervisorId, "kds-void")
        val voided = kitchen.voidItem(
            f.propertyId, order.id, withItem.items.single().id,
            VoidPosOrderItemRequest(
                PosVoidDisposition.RETURN_TO_STOCK, "Guest cancelled before preparation",
            ),
        )
        assertTrue(voided.returnBatchId != null)
        assertEquals(BigDecimal("10.000"), inventory.levels(f.propertyId).single().quantity)
    }

    @Test
    fun `phase4 property BOLA and row-level isolation reject another tenant`() {
        val owner = fixture()
        val other = fixture()
        bind(owner, owner.userId, "isolation-item")
        val item = inventory.createItem(
            owner.propertyId, CreateInventoryItemRequest("Isolated Item", "ISO", "Food", "kg"),
        )
        bind(owner, owner.userId, "isolation-location")
        val location = inventory.createLocation(
            owner.propertyId, CreateInventoryLocationRequest("Isolated Store", "store"),
        )
        bind(owner, owner.userId, "isolation-opening")
        inventory.openingBalances(
            owner.propertyId,
            StockCommandRequest(
                listOf(StockCommandLine(item.id, location.id, BigDecimal.ONE, BigDecimal.ONE)),
            ),
        )

        bind(other, other.userId, "isolation-bola")
        assertFails {
            inventory.getItem(owner.propertyId, item.id)
        }

        val visible = transaction.execute {
            jdbc.execute("SET LOCAL ROLE pms_app")
            jdbc.queryForObject(
                "SELECT set_config('app.current_tenant_id', ?, true)",
                String::class.java, other.tenantId.toString(),
            )
            jdbc.queryForObject(
                "SELECT set_config('app.current_tenant_user_id', ?, true)",
                String::class.java, other.userId.toString(),
            )
            jdbc.queryForObject(
                "SELECT count(*) FROM stock_levels WHERE tenant_id = ?",
                Int::class.java, owner.tenantId,
            )
        }
        assertEquals(0, visible)
    }

    @Test
    fun `checkout event creates exactly one departure clean task`() = runBlocking {
        val f = fixture(roomStatus = "vacant_dirty")
        val guest = UUID.randomUUID()
        val reservation = UUID.randomUUID()
        val stay = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO guests (id, tenant_id, full_name) VALUES (?, ?, 'Departure Guest')",
            guest, f.tenantId,
        )
        jdbc.update(
            """
            INSERT INTO reservations (
                id, tenant_id, property_id, primary_guest_id, status,
                check_in_date, check_out_date
            ) VALUES (?, ?, ?, ?, 'checked_out', current_date - 1, current_date)
            """.trimIndent(),
            reservation, f.tenantId, f.propertyId, guest,
        )
        jdbc.update(
            """
            INSERT INTO stays (
                id, tenant_id, reservation_id, room_id, status,
                check_in_time, check_out_time
            ) VALUES (?, ?, ?, ?, 'checked_out', now() - interval '1 day', now())
            """.trimIndent(),
            stay, f.tenantId, reservation, f.roomId,
        )
        bind(f, f.userId, "checkout-event")
        val event = ClaimedOutboxEvent(
            id = UUID.randomUUID(),
            tenantId = f.tenantId,
            propertyId = f.propertyId,
            aggregateType = "stays",
            aggregateId = stay,
            eventType = "frontdesk.departure_clean_requested",
            destination = OutboxDestination.HOUSEKEEPING,
            payload = """{"roomId":"${f.roomId}","stayId":"$stay"}""",
            headers = "{}",
            correlationId = "phase4-checkout",
            idempotencyKeyId = null,
            status = OutboxStatus.LOCKED,
            priority = 2,
            attemptCount = 1,
            maxAttempts = 10,
            nextAttemptAt = java.time.Instant.now(),
            lockedBy = "test",
            lockedAt = java.time.Instant.now(),
            deliveredAt = null,
            failedAt = null,
            errorMessage = null,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
        )
        checkoutHandler.handle(event)
        checkoutHandler.handle(event.copy(id = UUID.randomUUID()))
        assertEquals(
            1,
            jdbc.queryForObject(
                """
                SELECT count(*) FROM housekeeping_tasks
                WHERE tenant_id = ? AND property_id = ? AND source_stay_id = ?
                  AND type = 'departure_clean'
                """.trimIndent(),
                Int::class.java, f.tenantId, f.propertyId, stay,
            ),
        )
    }

    private fun fixture(roomStatus: String = "vacant_clean"): Fixture {
        val f = Fixture()
        createdTenantIds += f.tenantId
        jdbc.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            f.planId, "Phase 4 Plan ${f.planId}", "p4-${f.planId}",
        )
        jdbc.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            f.tenantId, "Phase 4 Tenant ${f.tenantId}", "p4-${f.tenantId}",
            "tenant_${f.tenantId}".replace("-", "_"), f.planId,
        )
        insertUser(f.tenantId, f.userId, "Operator")
        insertUser(f.tenantId, f.supervisorId, "Supervisor")
        jdbc.update(
            """
            INSERT INTO properties (
                id, tenant_id, name, status, is_active, total_rooms, business_date
            ) VALUES (?, ?, 'Phase 4 Property', 'active', true, 1, current_date)
            """.trimIndent(),
            f.propertyId, f.tenantId,
        )
        jdbc.update(
            """
            INSERT INTO room_types (
                id, tenant_id, property_id, name, code, base_price
            ) VALUES (?, ?, ?, 'Standard', ?, 100)
            """.trimIndent(),
            f.roomTypeId, f.tenantId, f.propertyId, "STD-${f.roomTypeId.toString().take(8)}",
        )
        jdbc.update(
            """
            INSERT INTO rooms (
                id, tenant_id, property_id, room_type_id, room_number, status
            ) VALUES (?, ?, ?, ?, '101', ?)
            """.trimIndent(),
            f.roomId, f.tenantId, f.propertyId, f.roomTypeId, roomStatus,
        )
        jdbc.update(
            """
            INSERT INTO outlets (id, tenant_id, property_id, name, type, is_active)
            VALUES (?, ?, ?, 'Restaurant', 'RESTAURANT', true)
            """.trimIndent(),
            f.outletId, f.tenantId, f.propertyId,
        )
        jdbc.update(
            "INSERT INTO menu_categories (id, tenant_id, outlet_id, name) VALUES (?, ?, ?, 'Food')",
            f.categoryId, f.tenantId, f.outletId,
        )
        jdbc.update(
            """
            INSERT INTO tax_rates (
                id, tenant_id, name, code, rate, tax_type, applies_to,
                is_inclusive, is_active
            ) VALUES (?, ?, 'VAT', ?, 0.18, 'vat', ARRAY['food'], false, true)
            """.trimIndent(),
            f.taxRateId, f.tenantId, "VAT-${f.taxRateId}",
        )
        jdbc.update(
            """
            INSERT INTO menu_items (
                id, tenant_id, category_id, name, price, vat_rate,
                is_available, tax_rate_id
            ) VALUES (?, ?, ?, 'Bean Plate', 10, 18, true, ?)
            """.trimIndent(),
            f.menuItemId, f.tenantId, f.categoryId, f.taxRateId,
        )
        return f
    }

    private fun insertUser(tenantId: UUID, userId: UUID, name: String) {
        jdbc.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            userId, tenantId, name, "phase4-$userId@example.com",
        )
    }

    private fun bind(f: Fixture, userId: UUID, key: String) {
        contexts.set(
            RequestContext(
                RequestIdentity.Tenant(f.tenantId, userId),
                "corr-$key", key, "POST",
                "/api/v1/properties/${f.propertyId}/phase4",
            ),
        )
    }

    private fun roomStatus(f: Fixture): String = jdbc.queryForObject(
        "SELECT status FROM rooms WHERE tenant_id = ? AND id = ?",
        String::class.java, f.tenantId, f.roomId,
    )!!

    private data class Fixture(
        val planId: UUID = UUID.randomUUID(),
        val tenantId: UUID = UUID.randomUUID(),
        val userId: UUID = UUID.randomUUID(),
        val supervisorId: UUID = UUID.randomUUID(),
        val propertyId: UUID = UUID.randomUUID(),
        val roomTypeId: UUID = UUID.randomUUID(),
        val roomId: UUID = UUID.randomUUID(),
        val outletId: UUID = UUID.randomUUID(),
        val categoryId: UUID = UUID.randomUUID(),
        val taxRateId: UUID = UUID.randomUUID(),
        val menuItemId: UUID = UUID.randomUUID(),
    )
}
