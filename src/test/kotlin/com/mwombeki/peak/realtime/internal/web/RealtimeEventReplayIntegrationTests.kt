package com.mwombeki.peak.realtime.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.realtime.internal.RealtimeEventJournal
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.hamcrest.Matchers.hasSize
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RealtimeEventReplayIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var journal: RealtimeEventJournal

    @Test
    fun `replays committed events after the cursor in the canonical envelope`() {
        val fixture = insertFixture(grantStream = true)
        val orderId = UUID.randomUUID()
        val first = journal.append(
            tenantId = fixture.tenantId,
            propertyId = fixture.propertyId,
            outletId = null,
            eventType = "pos.order.created",
            schemaVersion = 1,
            aggregateType = "POS_ORDER",
            aggregateId = orderId,
            aggregateVersion = 0,
            payload = mapOf("orderId" to orderId),
        )
        val second = journal.append(
            tenantId = fixture.tenantId,
            propertyId = fixture.propertyId,
            outletId = null,
            eventType = "pos.order.updated",
            schemaVersion = 1,
            aggregateType = "POS_ORDER",
            aggregateId = orderId,
            aggregateVersion = 1,
            payload = mapOf("orderId" to orderId),
        )

        mockMvc.perform(
            get("/api/v1/properties/${fixture.propertyId}/realtime/events?after=0")
                .secure(true)
                .headersFor(fixture),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events", hasSize<Any>(2)))
            .andExpect(jsonPath("$.events[0].sequenceId").value(first.sequenceId.toInt()))
            .andExpect(jsonPath("$.events[0].type").value("pos.order.created"))
            .andExpect(jsonPath("$.events[0].schemaVersion").value(1))
            .andExpect(jsonPath("$.events[0].aggregateId").exists())
            .andExpect(jsonPath("$.events[0].tenantId").value(fixture.tenantId.toString()))
            .andExpect(jsonPath("$.events[0].propertyId").value(fixture.propertyId.toString()))
            .andExpect(jsonPath("$.events[1].sequenceId").value(second.sequenceId.toInt()))
            .andExpect(jsonPath("$.nextCursor").value(second.sequenceId.toInt()))
    }

    @Test
    fun `resumes after the cursor across pages`() {
        val fixture = insertFixture(grantStream = true)
        val sequences = (1..5).map {
            journal.append(
                tenantId = fixture.tenantId,
                propertyId = fixture.propertyId,
                eventType = "pos.kitchen_ticket.created",
                payload = mapOf("ticketId" to UUID.randomUUID()),
            ).sequenceId
        }

        val firstPage = mockMvc.perform(
            get("/api/v1/properties/${fixture.propertyId}/realtime/events?after=0&limit=2")
                .secure(true)
                .headersFor(fixture),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events", hasSize<Any>(2)))
            .andExpect(jsonPath("$.events[0].sequenceId").value(sequences[0].toInt()))
            .andExpect(jsonPath("$.events[1].sequenceId").value(sequences[1].toInt()))
            .andExpect(jsonPath("$.nextCursor").value(sequences[1].toInt()))
            .andReturn()
            .response.contentAsString
        val nextCursor = com.jayway.jsonpath.JsonPath.read<Int>(firstPage, "$.nextCursor")

        mockMvc.perform(
            get("/api/v1/properties/${fixture.propertyId}/realtime/events?after=$nextCursor&limit=2")
                .secure(true)
                .headersFor(fixture),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events", hasSize<Any>(2)))
            .andExpect(jsonPath("$.events[0].sequenceId").value(sequences[2].toInt()))
            .andExpect(jsonPath("$.events[1].sequenceId").value(sequences[3].toInt()))
            .andExpect(jsonPath("$.nextCursor").value(sequences[3].toInt()))

        mockMvc.perform(
            get("/api/v1/properties/${fixture.propertyId}/realtime/events?after=$nextCursor")
                .secure(true)
                .headersFor(fixture),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events", hasSize<Any>(3)))
            .andExpect(jsonPath("$.events[2].sequenceId").value(sequences[4].toInt()))
            .andExpect(jsonPath("$.nextCursor").value(sequences[4].toInt()))
    }

    @Test
    fun `denies replay without the realtime stream entitlement`() {
        val fixture = insertFixture(grantStream = false)

        mockMvc.perform(
            get("/api/v1/properties/${fixture.propertyId}/realtime/events?after=0")
                .secure(true)
                .headersFor(fixture),
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `denies replay for a property of another tenant`() {
        val fixture = insertFixture(grantStream = true)
        val other = insertFixture(grantStream = true)

        mockMvc.perform(
            get("/api/v1/properties/${other.propertyId}/realtime/events?after=0")
                .secure(true)
                .headersFor(fixture),
        )
            .andExpect(status().isForbidden)
    }

    private data class ReplayFixture(
        val planId: UUID,
        val tenantId: UUID,
        val userId: UUID,
        val propertyId: UUID,
    )

    private fun insertFixture(grantStream: Boolean): ReplayFixture {
        val fixture = ReplayFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
        )
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            fixture.planId,
            "Replay Plan ${fixture.planId}",
            "replay-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, schema_name, plan_id, status)
            VALUES (?, ?, ?, ?, ?, 'active')
            """.trimIndent(),
            fixture.tenantId,
            "Replay Tenant ${fixture.tenantId}",
            "replay-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Replay User', ?, 'active', true)
            """.trimIndent(),
            fixture.userId,
            fixture.tenantId,
            "replay-${fixture.userId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, status, is_active, total_rooms)
            VALUES (?, ?, 'Replay Property', 'active', true, 0)
            """.trimIndent(),
            fixture.propertyId,
            fixture.tenantId,
        )
        if (grantStream) {
            jdbcTemplate.update(
                """
                INSERT INTO permissions (id, tenant_id, code, description)
                SELECT gen_random_uuid(), ?, pc.code, pc.description
                FROM permission_catalog pc
                WHERE pc.code = ?
                ON CONFLICT (tenant_id, code) DO UPDATE SET
                    description = EXCLUDED.description,
                    updated_at = now()
                """.trimIndent(),
                fixture.tenantId,
                "realtime.stream",
            )
            jdbcTemplate.update(
                """
                INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
                VALUES (?, 'realtime', true, true)
                ON CONFLICT ON CONSTRAINT tenant_modules_tenant_id_module_id_key
                DO UPDATE SET is_enabled = true, is_configured = true
                """.trimIndent(),
                fixture.tenantId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO property_modules (tenant_id, property_id, module_id, is_enabled, is_configured)
                VALUES (?, ?, 'realtime', true, true)
                ON CONFLICT ON CONSTRAINT property_modules_tenant_id_property_id_module_id_key
                DO UPDATE SET is_enabled = true, is_configured = true
                """.trimIndent(),
                fixture.tenantId,
                fixture.propertyId,
            )
            val roleId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO roles (id, tenant_id, name, is_active)
                VALUES (?, ?, 'Realtime Stream', true)
                """.trimIndent(),
                roleId,
                fixture.tenantId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id
                FROM permissions
                WHERE tenant_id = ? AND code = 'realtime.stream'
                """.trimIndent(),
                roleId,
                fixture.tenantId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                fixture.userId,
                fixture.propertyId,
                roleId,
                fixture.tenantId,
            )
        }
        return fixture
    }

    private fun MockHttpServletRequestBuilder.headersFor(
        fixture: ReplayFixture,
    ): MockHttpServletRequestBuilder {
        header(PeakRequestHeaders.CORRELATION_ID, "corr-replay-${fixture.tenantId}")
        header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
        header(PeakRequestHeaders.TENANT_USER_ID, fixture.userId.toString())
        return this
    }
}