package com.mwombeki.peak.realtime.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.context.SessionClass
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RealtimeSubscriptionAuthorizerIntegrationTests {

    @Autowired
    private lateinit var authorizer: RealtimeSubscriptionAuthorizer

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun operationalSessionCanWatchItsPropertyOperationsStream() {
        val hotel = seedHotel(grantStream = true)

        assertTrue(
            authorizer.canSubscribeDestination(
                identity = RequestIdentity.Tenant(hotel.tenantId, hotel.userId),
                target = RealtimeSubscriptionTarget.PropertyOperations(hotel.propertyId),
                sessionClass = SessionClass.OPERATIONAL,
                boundPropertyId = hotel.propertyId,
                boundOutletId = hotel.outletId,
            ),
        )
        assertTrue(
            authorizer.canSubscribeDestination(
                identity = RequestIdentity.Tenant(hotel.tenantId, hotel.userId),
                target = RealtimeSubscriptionTarget.Outlet(hotel.outletId, orders = true),
                sessionClass = SessionClass.OPERATIONAL,
                boundPropertyId = hotel.propertyId,
                boundOutletId = hotel.outletId,
            ),
        )
    }

    @Test
    fun operationalSessionCannotSubscribeToAnotherTenantsTopic() {
        val ours = seedHotel(grantStream = true)
        val theirs = seedHotel(grantStream = true)

        assertFalse(
            authorizer.canSubscribeDestination(
                identity = RequestIdentity.Tenant(ours.tenantId, ours.userId),
                target = RealtimeSubscriptionTarget.PropertyOperations(theirs.propertyId),
                sessionClass = SessionClass.OPERATIONAL,
                boundPropertyId = ours.propertyId,
            ),
            "a leaked property topic must be refused",
        )
        assertFalse(
            authorizer.canSubscribeDestination(
                identity = RequestIdentity.Tenant(ours.tenantId, ours.userId),
                target = RealtimeSubscriptionTarget.Outlet(theirs.outletId, orders = true),
                sessionClass = SessionClass.OPERATIONAL,
                boundPropertyId = ours.propertyId,
                boundOutletId = ours.outletId,
            ),
            "a leaked outlet topic must be refused",
        )
    }

    @Test
    fun operationalSessionCannotSubscribeToAnotherOutletInTheSameProperty() {
        val hotel = seedHotel(grantStream = true)
        val otherOutlet = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO outlets (id, tenant_id, property_id, name, type, is_active)
            VALUES (?, ?, ?, 'Bar', 'BAR', true)
            """.trimIndent(),
            otherOutlet,
            hotel.tenantId,
            hotel.propertyId,
        )

        assertFalse(
            authorizer.canSubscribeDestination(
                identity = RequestIdentity.Tenant(hotel.tenantId, hotel.userId),
                target = RealtimeSubscriptionTarget.Outlet(otherOutlet, orders = true),
                sessionClass = SessionClass.OPERATIONAL,
                boundPropertyId = hotel.propertyId,
                boundOutletId = hotel.outletId,
            ),
        )
    }

    private fun seedHotel(grantStream: Boolean): Hotel {
        val hotel = Hotel(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            outletId = UUID.randomUUID(),
        )
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            hotel.planId, "Plan ${hotel.planId}", "plan-${hotel.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, schema_name, plan_id, status)
            VALUES (?, ?, ?, ?, ?, 'active')
            """.trimIndent(),
            hotel.tenantId,
            "Tenant ${hotel.tenantId}",
            "tenant-${hotel.tenantId}",
            "tenant_${hotel.tenantId}".replace("-", "_"),
            hotel.planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Waiter', ?, 'active', true)
            """.trimIndent(),
            hotel.userId, hotel.tenantId, "w-${hotel.userId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, status, is_active, total_rooms)
            VALUES (?, ?, 'Hotel', 'active', true, 0)
            """.trimIndent(),
            hotel.propertyId, hotel.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO outlets (id, tenant_id, property_id, name, type, is_active)
            VALUES (?, ?, ?, 'Restaurant', 'RESTAURANT', true)
            """.trimIndent(),
            hotel.outletId, hotel.tenantId, hotel.propertyId,
        )
        if (grantStream) {
            jdbcTemplate.update(
                """
                INSERT INTO permissions (id, tenant_id, code, description)
                SELECT gen_random_uuid(), ?, pc.code, pc.description
                FROM permission_catalog pc
                WHERE pc.code = 'realtime.stream'
                ON CONFLICT (tenant_id, code) DO UPDATE SET description = EXCLUDED.description
                """.trimIndent(),
                hotel.tenantId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
                VALUES (?, 'realtime', true, true)
                ON CONFLICT ON CONSTRAINT tenant_modules_tenant_id_module_id_key
                DO UPDATE SET is_enabled = true, is_configured = true
                """.trimIndent(),
                hotel.tenantId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO property_modules (tenant_id, property_id, module_id, is_enabled, is_configured)
                VALUES (?, ?, 'realtime', true, true)
                ON CONFLICT ON CONSTRAINT property_modules_tenant_id_property_id_module_id_key
                DO UPDATE SET is_enabled = true, is_configured = true
                """.trimIndent(),
                hotel.tenantId,
                hotel.propertyId,
            )
            val roleId = UUID.randomUUID()
            jdbcTemplate.update(
                "INSERT INTO roles (id, tenant_id, name, is_active) VALUES (?, ?, 'Stream', true)",
                roleId,
                hotel.tenantId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions
                WHERE tenant_id = ? AND code = 'realtime.stream'
                """.trimIndent(),
                roleId,
                hotel.tenantId,
            )
            jdbcTemplate.update(
                """
                INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                hotel.userId, hotel.propertyId, roleId, hotel.tenantId,
            )
        }
        return hotel
    }

    private data class Hotel(
        val planId: UUID,
        val tenantId: UUID,
        val userId: UUID,
        val propertyId: UUID,
        val outletId: UUID,
    )
}
