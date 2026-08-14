package com.mwombeki.peak.pos.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.billing.api.BillingConflictException
import com.mwombeki.peak.pos.api.AddPosOrderItemRequest
import com.mwombeki.peak.pos.api.CreatePosOrderRequest
import com.mwombeki.peak.pos.api.OpenPosSessionRequest
import com.mwombeki.peak.pos.api.SettlePosOrderRequest
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.math.BigDecimal
import java.time.LocalDate
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
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * A waiter may charge the room the guest is actually in, and no other.
 *
 * `postPosCharge` verified only that the folio existed, was not deleted, and was open. Not that
 * the room was occupied, not that the stay was checked in, not that any guest matched — so
 * **any waiter holding any open folio's UUID could charge it**. In a hotel that is a stranger's
 * dinner appearing on your bill, discovered at checkout, with nothing in the record explaining
 * how it got there.
 *
 * The verification already existed. `reservationRoomForCharge` requires `rr.status =
 * 'checked_in'` and selects `rm.room_number`, and is used by the room-rate path — it was simply
 * never called from this one. This is mostly a matter of calling code that was already written.
 *
 * A waiter cannot see folio UUIDs anyway; they hear "put it on 204" across a restaurant. So the
 * room number is what the caller supplies and what Peak checks, which is also the only input a
 * POS client could realistically have.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PosRoomChargeIntegrationTests {

    @Autowired private lateinit var posOrderService: PosOrderService
    @Autowired private lateinit var posSessionService: PosSessionService
    @Autowired private lateinit var requestContextHolder: RequestContextHolder
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
        jdbcTemplate.execute("RESET ALL")
    }

    /** The one that matters: room 208's guest must not be able to pay for room 204's dinner. */
    @Test
    fun chargingAFolioWhoseGuestIsNotInThatRoomIsRefused() {
        val hotel = hotelWithTwoOccupiedRooms()

        val refused = assertFailsWith<BillingConflictException> {
            settleToRoom(hotel, folioId = hotel.room204Folio, roomNumber = "208")
        }

        assertTrue(refused.message.contains("204"), refused.message)
        assertEquals(
            0,
            folioChargeCount(hotel.room204Folio),
            "a refused charge must leave nothing on the folio",
        )
        assertEquals(
            "open",
            orderStatus(hotel.orderId),
            "and the order must stay settleable, so the waiter can correct the room",
        )
    }

    /**
     * A folio with no checked-in guest is a folio nobody is standing behind. It may be a stay
     * that already departed, or one that never arrived.
     */
    @Test
    fun chargingAFolioWithNoCheckedInStayIsRefused() {
        val hotel = hotelWithTwoOccupiedRooms()
        jdbcTemplate.update(
            "UPDATE reservation_rooms SET status = 'checked_out' WHERE folio_id = ?",
            hotel.room204Folio,
        )

        val refused = assertFailsWith<BillingConflictException> {
            settleToRoom(hotel, folioId = hotel.room204Folio, roomNumber = "204")
        }

        assertTrue(refused.message.contains("no guest checked into"), refused.message)
        assertEquals(0, folioChargeCount(hotel.room204Folio))
    }

    /**
     * The control. Without it both refusals above would pass against an implementation that
     * refuses everything, and the room charge would be broken rather than protected.
     */
    @Test
    fun chargingTheRoomTheGuestIsInSucceeds() {
        val hotel = hotelWithTwoOccupiedRooms()

        settleToRoom(hotel, folioId = hotel.room204Folio, roomNumber = "204")

        assertEquals(1, folioChargeCount(hotel.room204Folio))
        assertEquals("closed", orderStatus(hotel.orderId))
        assertEquals(
            0,
            folioChargeCount(hotel.room208Folio),
            "the other guest's folio must be untouched",
        )
    }

    /** A room charge with no room named is a folio UUID trusted on its own, which is the bug. */
    @Test
    fun aRoomChargeWithNoRoomNumberIsRefused() {
        val hotel = hotelWithTwoOccupiedRooms()

        assertFailsWith<IllegalArgumentException> {
            settleToRoom(hotel, folioId = hotel.room204Folio, roomNumber = null)
        }
    }

    private fun settleToRoom(hotel: Hotel, folioId: UUID, roomNumber: String?) {
        bind(hotel, "idem-settle-${UUID.randomUUID()}")
        posOrderService.settleOrder(
            hotel.propertyId,
            hotel.orderId,
            SettlePosOrderRequest(
                paymentMethod = "room_charge",
                folioId = folioId,
                roomNumber = roomNumber,
            ),
        )
    }

    private fun folioChargeCount(folioId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM folio_charges WHERE folio_id = ?",
            Int::class.java,
            folioId,
        ) ?: 0

    private fun orderStatus(orderId: UUID): String? =
        jdbcTemplate.queryForObject(
            "SELECT status FROM pos_orders WHERE id = ?",
            String::class.java,
            orderId,
        )

    /** Two guests in two rooms, and one restaurant order waiting to be charged to one of them. */
    private fun hotelWithTwoOccupiedRooms(): Hotel {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val propertyId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val outletId = UUID.randomUUID()
        val categoryId = UUID.randomUUID()
        val menuItemId = UUID.randomUUID()
        val taxRateId = UUID.randomUUID()
        val roomTypeId = UUID.randomUUID()

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
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Waiter', ?, 'active', true)
            """.trimIndent(),
            userId, tenantId, "waiter-$userId@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, status, is_active, total_rooms)
            VALUES (?, ?, 'Charge Hotel', 'active', true, 2)
            """.trimIndent(),
            propertyId, tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO room_types (
                id, tenant_id, property_id, name, code, base_price,
                max_adults, max_children, max_occupancy, is_active
            ) VALUES (?, ?, ?, 'Standard', 'STD', 100.00, 2, 1, 3, true)
            """.trimIndent(),
            roomTypeId, tenantId, propertyId,
        )

        val room204 = insertRoom(tenantId, propertyId, roomTypeId, "204")
        val room208 = insertRoom(tenantId, propertyId, roomTypeId, "208")
        val folio204 = insertOccupiedStay(tenantId, propertyId, room204, roomTypeId, userId)
        val folio208 = insertOccupiedStay(tenantId, propertyId, room208, roomTypeId, userId)

        jdbcTemplate.update(
            """
            INSERT INTO outlets (id, tenant_id, property_id, name, type, is_active)
            VALUES (?, ?, ?, 'Restaurant', 'RESTAURANT', true)
            """.trimIndent(),
            outletId, tenantId, propertyId,
        )
        jdbcTemplate.update(
            "INSERT INTO menu_categories (id, tenant_id, outlet_id, name) VALUES (?, ?, ?, 'Food')",
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
            ) VALUES (?, ?, ?, 'Lunch', 10.00, 18.00, true, ?)
            """.trimIndent(),
            menuItemId, tenantId, categoryId, taxRateId,
        )

        val hotel = Hotel(
            tenantId = tenantId,
            propertyId = propertyId,
            userId = userId,
            outletId = outletId,
            room204Folio = folio204,
            room208Folio = folio208,
            orderId = UUID.randomUUID(),
        )

        bind(hotel, "idem-session-$tenantId")
        val session = posSessionService.openSession(
            propertyId,
            OpenPosSessionRequest(outletId = outletId, openingFloat = BigDecimal.ZERO),
        )

        bind(hotel, "idem-create-$tenantId")
        val order = posOrderService.createOrder(
            propertyId,
            CreatePosOrderRequest(
                sessionId = session.id,
                orderType = "dine_in",
                tableNumber = "12",
                clientOperationId = "room-charge-create",
            ),
        )
        bind(hotel, "idem-item-$tenantId")
        posOrderService.addItem(
            propertyId,
            order.id,
            AddPosOrderItemRequest(
                menuItemId = menuItemId,
                quantity = BigDecimal.ONE,
                clientOperationId = "room-charge-item",
            ),
        )

        return hotel.copy(orderId = order.id)
    }

    private fun insertRoom(
        tenantId: UUID,
        propertyId: UUID,
        roomTypeId: UUID,
        roomNumber: String,
    ): UUID {
        val roomId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO rooms (id, tenant_id, property_id, room_type_id, room_number, status)
            VALUES (?, ?, ?, ?, ?, 'occupied')
            """.trimIndent(),
            roomId, tenantId, propertyId, roomTypeId, roomNumber,
        )
        return roomId
    }

    /** A guest checked into a room, with the folio their charges land on. */
    private fun insertOccupiedStay(
        tenantId: UUID,
        propertyId: UUID,
        roomId: UUID,
        roomTypeId: UUID,
        userId: UUID,
    ): UUID {
        val guestId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        val folioId = UUID.randomUUID()
        val today = LocalDate.now()

        jdbcTemplate.update(
            """
            INSERT INTO guests (id, tenant_id, full_name)
            VALUES (?, ?, ?)
            """.trimIndent(),
            guestId, tenantId, "Guest $guestId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO reservations (
                id, tenant_id, property_id, primary_guest_id, status,
                check_in_date, check_out_date, adults, children
            ) VALUES (?, ?, ?, ?, 'checked_in', ?, ?, 1, 0)
            """.trimIndent(),
            reservationId, tenantId, propertyId, guestId, today, today.plusDays(1),
        )
        jdbcTemplate.update(
            """
            INSERT INTO folios (id, tenant_id, property_id, reservation_id, folio_type, status)
            VALUES (?, ?, ?, ?, 'guest', 'open')
            """.trimIndent(),
            folioId, tenantId, propertyId, reservationId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO reservation_rooms (
                id, tenant_id, reservation_id, room_type_id, room_id, folio_id,
                check_in_date, check_out_date, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'checked_in')
            """.trimIndent(),
            UUID.randomUUID(), tenantId, reservationId, roomTypeId, roomId, folioId,
            today, today.plusDays(1),
        )
        return folioId
    }

    private fun bind(hotel: Hotel, idempotencyKey: String) {
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Tenant(hotel.tenantId, hotel.userId),
                correlationId = "corr-$idempotencyKey",
                idempotencyKey = idempotencyKey,
                httpMethod = "POST",
                requestPath = "/api/v1/properties/${hotel.propertyId}/pos-orders",
            ),
        )
    }

    private data class Hotel(
        val tenantId: UUID,
        val propertyId: UUID,
        val userId: UUID,
        val outletId: UUID,
        val room204Folio: UUID,
        val room208Folio: UUID,
        val orderId: UUID,
    )
}
