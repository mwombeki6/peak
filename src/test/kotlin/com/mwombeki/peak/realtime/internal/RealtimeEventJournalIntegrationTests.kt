package com.mwombeki.peak.realtime.internal

import com.mwombeki.peak.TestcontainersConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RealtimeEventJournalIntegrationTests {
    @Autowired
    private lateinit var journal: RealtimeEventJournal

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun persistsOrderedEventsForCrossNodePollingAndScopedResume() {
        val fixture = insertFixture()
        val otherPropertyId = insertProperty(fixture.tenantId)
        val before = journal.latestSequence()
        val first = journal.append(
            tenantId = fixture.tenantId,
            propertyId = fixture.propertyId,
            eventType = "property.room.updated",
            payload = mapOf("roomId" to UUID.randomUUID(), "status" to "vacant_clean"),
        )
        val second = journal.append(
            tenantId = fixture.tenantId,
            propertyId = fixture.propertyId,
            eventType = "frontdesk.stay.checked_in",
            payload = mapOf("stayId" to UUID.randomUUID()),
        )

        val polled = journal.pollAfter(before)
        assertTrue(polled.indexOf(first) < polled.indexOf(second))
        assertEquals(
            listOf(second),
            journal.replayAfter(
                fixture.tenantId,
                fixture.propertyId,
                first.sequenceId.toString(),
            ),
        )
        assertTrue(
            journal.replayAfter(
                fixture.tenantId,
                otherPropertyId,
                first.sequenceId.toString(),
            ).isEmpty(),
        )
        assertFailsWith<IllegalArgumentException> {
            journal.replayAfter(fixture.tenantId, fixture.propertyId, "invalid")
        }
    }

    @Test
    fun deletesOnlyExpiredJournalEvents() {
        val fixture = insertFixture()
        val expired = journal.append(
            fixture.tenantId,
            fixture.propertyId,
            "property.room.updated",
            mapOf("status" to "dirty"),
        )
        val active = journal.append(
            fixture.tenantId,
            fixture.propertyId,
            "property.room.updated",
            mapOf("status" to "vacant_clean"),
        )
        jdbcTemplate.update(
            "UPDATE realtime_event_journal SET expires_at = now() - interval '1 second' WHERE sequence_id = ?",
            expired.sequenceId,
        )

        assertTrue(journal.deleteExpired() >= 1)
        assertEquals(
            listOf(active),
            journal.replayAfter(
                fixture.tenantId,
                fixture.propertyId,
                (expired.sequenceId - 1).toString(),
            ),
        )
    }

    @Test
    fun `mirrors property platform outbox event in the business transaction`() {
        val fixture = insertFixture()
        val before = journal.latestSequence()
        val aggregateId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO outbox_events (
                id, tenant_id, property_id, aggregate_type, aggregate_id,
                event_type, destination, payload, correlation_id
            )
            VALUES (?, ?, ?, 'rooms', ?, 'property.room.updated',
                    'platform', ?::jsonb, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            fixture.tenantId,
            fixture.propertyId,
            aggregateId,
            """{"roomId":"$aggregateId","status":"vacant_clean"}""",
            "realtime-correlation",
        )

        val mirrored = journal.replayAfter(
            fixture.tenantId,
            fixture.propertyId,
            before.toString(),
        ).single()

        assertEquals("property.room.updated", mirrored.eventType)
        assertEquals("realtime-correlation", mirrored.payload["correlationId"])
        assertEquals(
            "vacant_clean",
            (mirrored.payload["data"] as Map<*, *>)["status"],
        )
    }

    private fun insertFixture(): RealtimeFixture {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId,
            "Realtime Plan $planId",
            "realtime-$planId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, schema_name, plan_id, status)
            VALUES (?, ?, ?, ?, ?, 'active')
            """.trimIndent(),
            tenantId,
            "Realtime Tenant $tenantId",
            "realtime-$tenantId",
            "tenant_$tenantId".replace("-", "_"),
            planId,
        )
        return RealtimeFixture(tenantId, insertProperty(tenantId))
    }

    private fun insertProperty(tenantId: UUID): UUID {
        val propertyId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, code, type, status, is_active)
            VALUES (?, ?, ?, ?, 'HOTEL', 'active', true)
            """.trimIndent(),
            propertyId,
            tenantId,
            "Realtime Property $propertyId",
            "RT-${propertyId.toString().take(8)}",
        )
        return propertyId
    }

    private data class RealtimeFixture(
        val tenantId: UUID,
        val propertyId: UUID,
    )
}
