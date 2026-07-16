package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class LargeDataQueryPlanIntegrationTests {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun criticalQueriesKeepSelectiveIndexesAtProductionLikeCardinality() {
        val fixture = insertFixture()
        seedReservations(fixture, 25_000)
        seedPayments(fixture, 50_000)
        seedOutbox(fixture, 50_000)
        jdbc.execute("ANALYZE reservations")
        jdbc.execute("ANALYZE reservation_rooms")
        jdbc.execute("ANALYZE payment_transactions")
        jdbc.execute("ANALYZE outbox_events")

        val arrivalPlan = plan(
            """
            SELECT id
            FROM reservations
            WHERE tenant_id = ?
              AND property_id = ?
              AND status = 'confirmed'
              AND check_in_date = ?
              AND deleted_at IS NULL
            ORDER BY check_in_date, id
            LIMIT 100
            """.trimIndent(),
            fixture.tenantId,
            fixture.propertyId,
            LocalDate.now().plusDays(120),
        )
        assertUsesIndex(
            arrivalPlan,
            "reservations",
            "idx_reservations_tenant_property_status_dates",
        )

        val availabilityPlan = plan(
            """
            SELECT count(*)
            FROM reservation_rooms
            WHERE tenant_id = ?
              AND room_type_id = ?
              AND status NOT IN ('cancelled', 'checked_out')
              AND check_in_date < ?
              AND check_out_date > ?
            """.trimIndent(),
            fixture.tenantId,
            fixture.roomTypeId,
            LocalDate.now().plusDays(121),
            LocalDate.now().plusDays(120),
        )
        assertUsesIndex(
            availabilityPlan,
            "reservation_rooms",
            "idx_reservation_rooms_availability",
            "idx_reservation_rooms_room_dates",
        )

        val paymentPlan = plan(
            """
            SELECT id, amount, status, initiated_at
            FROM payment_transactions
            WHERE tenant_id = ? AND property_id = ?
            ORDER BY initiated_at DESC, id DESC
            LIMIT 100
            """.trimIndent(),
            fixture.tenantId,
            fixture.propertyId,
        )
        assertUsesIndex(
            paymentPlan,
            "payment_transactions",
            "idx_payment_transactions_tenant_property_initiated",
        )

        val workerPlan = plan(
            """
            SELECT id
            FROM outbox_events
            WHERE status IN ('pending', 'failed')
              AND next_attempt_at <= now()
            ORDER BY priority, created_at
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
        )
        assertUsesIndex(
            workerPlan,
            "outbox_events",
            "idx_outbox_events_worker_poll",
        )
    }

    private fun assertUsesIndex(plan: String, relation: String, vararg indexes: String) {
        assertTrue(
            indexes.any(plan::contains),
            "Expected one of ${indexes.toList()} in plan: $plan",
        )
        assertFalse(
            Regex("\\\"Node Type\\\"\\s*:\\s*\\\"Seq Scan\\\"[^}]*" +
                "\\\"Relation Name\\\"\\s*:\\s*\\\"$relation\\\"")
                .containsMatchIn(plan),
            "Unexpected sequential scan of $relation: $plan",
        )
        val executionMs = Regex("\\\"Execution Time\\\"\\s*:\\s*([0-9.]+)")
            .find(plan)
            ?.groupValues
            ?.get(1)
            ?.toDouble()
            ?: error("Execution time missing from plan: $plan")
        assertTrue(executionMs < 500.0, "$relation query took ${executionMs}ms")
    }

    private fun plan(sql: String, vararg args: Any): String {
        return requireNotNull(
            jdbc.queryForObject(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $sql",
                String::class.java,
                *args,
            ),
        )
    }

    private fun seedReservations(fixture: Fixture, count: Int) {
        jdbc.update(
            """
            WITH inserted AS (
                INSERT INTO reservations (
                    id, tenant_id, property_id, primary_guest_id,
                    confirmation_number, status, check_in_date,
                    check_out_date, adults, children, total_amount
                )
                SELECT gen_random_uuid(), ?, ?, ?,
                       'LR' || lpad(g::text, 8, '0'), 'confirmed',
                       current_date + (g % 365)::int,
                       current_date + (g % 365)::int + 1,
                       1, 0, 100000.00
                FROM generate_series(1, ?) AS g
                RETURNING id, check_in_date, check_out_date
            )
            INSERT INTO reservation_rooms (
                id, tenant_id, reservation_id, room_type_id,
                check_in_date, check_out_date, rate_per_night, status
            )
            SELECT gen_random_uuid(), ?, id, ?, check_in_date,
                   check_out_date, 100000.00, 'reserved'
            FROM inserted
            """.trimIndent(),
            fixture.tenantId,
            fixture.propertyId,
            fixture.guestId,
            count,
            fixture.tenantId,
            fixture.roomTypeId,
        )
    }

    private fun seedPayments(fixture: Fixture, count: Int) {
        jdbc.update(
            """
            INSERT INTO payment_transactions (
                id, tenant_id, property_id, transaction_direction,
                transaction_type, internal_reference, amount, currency,
                status, initiated_at, posted_at
            )
            SELECT gen_random_uuid(), ?, ?, 'inbound', 'collection',
                   'LOAD-' || g::text, 1000.00 + (g % 100), 'TZS',
                   'posted', now() - (g || ' milliseconds')::interval,
                   now() - (g || ' milliseconds')::interval
            FROM generate_series(1, ?) AS g
            """.trimIndent(),
            fixture.tenantId,
            fixture.propertyId,
            count,
        )
    }

    private fun seedOutbox(fixture: Fixture, count: Int) {
        jdbc.update(
            """
            INSERT INTO outbox_events (
                id, tenant_id, property_id, aggregate_type, aggregate_id,
                event_type, destination, payload, status, priority,
                next_attempt_at, created_at
            )
            SELECT gen_random_uuid(), ?, ?, 'load_probe', gen_random_uuid(),
                   'load.probe', 'payment', '{}'::jsonb,
                   CASE WHEN g % 1000 = 0 THEN 'pending' ELSE 'delivered' END,
                   ((g % 10) + 1)::smallint,
                   now() - interval '1 minute',
                   now() - (g || ' milliseconds')::interval
            FROM generate_series(1, ?) AS g
            """.trimIndent(),
            fixture.tenantId,
            fixture.propertyId,
            count,
        )
    }

    private fun insertFixture(): Fixture {
        val fixture = Fixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            guestId = UUID.randomUUID(),
            roomTypeId = UUID.randomUUID(),
        )
        jdbc.update(
            "INSERT INTO plans (id, name, code) VALUES (?, 'Large Data Plan', ?)",
            fixture.planId,
            "large-${fixture.planId}",
        )
        jdbc.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, 'Large Data Tenant', ?, 'active', ?, ?)
            """.trimIndent(),
            fixture.tenantId,
            "large-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        jdbc.update(
            """
            INSERT INTO properties (id, tenant_id, name, status, is_active, total_rooms)
            VALUES (?, ?, 'Large Data Property', 'active', true, 500)
            """.trimIndent(),
            fixture.propertyId,
            fixture.tenantId,
        )
        jdbc.update(
            "INSERT INTO guests (id, tenant_id, full_name) VALUES (?, ?, 'Load Guest')",
            fixture.guestId,
            fixture.tenantId,
        )
        jdbc.update(
            """
            INSERT INTO room_types (
                id, tenant_id, property_id, name, code, base_price,
                max_adults, max_children, max_occupancy, is_active
            ) VALUES (?, ?, ?, 'Load Room', 'LOAD', 100000.00, 2, 1, 3, true)
            """.trimIndent(),
            fixture.roomTypeId,
            fixture.tenantId,
            fixture.propertyId,
        )
        return fixture
    }

    private data class Fixture(
        val planId: UUID,
        val tenantId: UUID,
        val propertyId: UUID,
        val guestId: UUID,
        val roomTypeId: UUID,
    )
}
