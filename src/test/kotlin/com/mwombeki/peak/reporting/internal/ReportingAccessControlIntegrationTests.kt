package com.mwombeki.peak.reporting.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.reporting.api.AddReportRecipientRequest
import com.mwombeki.peak.reporting.api.CreateReportSubscriptionRequest
import com.mwombeki.peak.reporting.api.ReportingNotFoundException
import com.mwombeki.peak.reporting.api.ReportingPort
import com.mwombeki.peak.reporting.api.ReportSubscriptionState
import com.mwombeki.peak.reporting.api.UpdateReportSubscriptionRequest
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class ReportingAccessControlIntegrationTests {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var contexts: RequestContextHolder
    @Autowired lateinit var reporting: ReportingPort

    private val tenants = mutableSetOf<UUID>()
    private val plans = mutableSetOf<UUID>()

    @AfterTest
    fun cleanup() {
        tenants.forEach { tenantId ->
            jdbc.update("DELETE FROM report_subscription_recipients WHERE tenant_id = ?", tenantId)
            jdbc.update("DELETE FROM report_subscriptions WHERE tenant_id = ?", tenantId)
            jdbc.update("DELETE FROM properties WHERE tenant_id = ?", tenantId)
            jdbc.update("DELETE FROM users WHERE tenant_id = ?", tenantId)
            jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId)
        }
        plans.forEach { planId ->
            jdbc.update("DELETE FROM plans WHERE id = ?", planId)
        }
        tenants.clear()
        plans.clear()
        contexts.clear()
    }

    @Test
    fun `property scoped subscription mutations reject subscriptions from another property`() {
        val fixture = fixture()
        bind(fixture, fixture.propertyOneId, "reporting-property-one-create")
        val subscription = reporting.createSubscription(
            propertyId = fixture.propertyOneId,
            request = CreateReportSubscriptionRequest(
                reportCode = "daily_management_summary",
                subscriptionName = "Daily property one",
            ),
        )

        bind(fixture, fixture.propertyTwoId, "reporting-property-two-update")
        assertFailsWith<ReportingNotFoundException> {
            reporting.updateSubscription(
                subscriptionId = subscription.id,
                request = UpdateReportSubscriptionRequest(
                    subscriptionName = "Cross-property rename",
                ),
                propertyId = fixture.propertyTwoId,
            )
        }
        assertEquals(
            "Daily property one",
            subscriptionName(fixture.tenantId, subscription.id),
        )

        assertFailsWith<ReportingNotFoundException> {
            reporting.transitionSubscription(
                subscriptionId = subscription.id,
                action = "pause",
                propertyId = fixture.propertyTwoId,
            )
        }

        assertFailsWith<ReportingNotFoundException> {
            reporting.addRecipient(
                subscriptionId = subscription.id,
                request = AddReportRecipientRequest(
                    contactId = UUID.randomUUID(),
                    contactChannelId = UUID.randomUUID(),
                ),
                propertyId = fixture.propertyTwoId,
            )
        }

        bind(fixture, fixture.propertyOneId, "reporting-property-one-update")
        val updated = reporting.updateSubscription(
            subscriptionId = subscription.id,
            request = UpdateReportSubscriptionRequest(
                subscriptionName = "Daily property one updated",
            ),
            propertyId = fixture.propertyOneId,
        )
        assertEquals("Daily property one updated", updated.subscriptionName)

        val paused = reporting.transitionSubscription(
            subscriptionId = subscription.id,
            action = "pause",
            propertyId = fixture.propertyOneId,
        )
        assertEquals(ReportSubscriptionState.PAUSED, paused.state)
    }

    private fun fixture(): Fixture {
        val fixture = Fixture()
        tenants += fixture.tenantId
        plans += fixture.planId
        jdbc.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            fixture.planId,
            "Reporting Access Plan ${fixture.planId}",
            "reporting-access-${fixture.planId}",
        )
        jdbc.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            fixture.tenantId,
            "Reporting Access Tenant ${fixture.tenantId}",
            "reporting-access-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        jdbc.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Reporting User', ?, 'active', true)
            """.trimIndent(),
            fixture.userId,
            fixture.tenantId,
            "reporting-${fixture.userId}@example.com",
        )
        insertProperty(fixture.tenantId, fixture.propertyOneId, "Reporting One")
        insertProperty(fixture.tenantId, fixture.propertyTwoId, "Reporting Two")
        return fixture
    }

    private fun insertProperty(
        tenantId: UUID,
        propertyId: UUID,
        name: String,
    ) {
        jdbc.update(
            """
            INSERT INTO properties (
                id, tenant_id, name, status, is_active, total_rooms, business_date
            ) VALUES (?, ?, ?, 'active', true, 1, current_date)
            """.trimIndent(),
            propertyId,
            tenantId,
            name,
        )
    }

    private fun bind(
        fixture: Fixture,
        propertyId: UUID,
        key: String,
    ) {
        contexts.set(
            RequestContext(
                identity = RequestIdentity.Tenant(fixture.tenantId, fixture.userId),
                correlationId = "corr-$key",
                idempotencyKey = key,
                httpMethod = "POST",
                requestPath = "/api/v1/properties/$propertyId/report-subscriptions",
            ),
        )
    }

    private fun subscriptionName(
        tenantId: UUID,
        subscriptionId: UUID,
    ): String = jdbc.queryForObject(
        """
        SELECT subscription_name
        FROM report_subscriptions
        WHERE tenant_id = ? AND id = ?
        """.trimIndent(),
        String::class.java,
        tenantId,
        subscriptionId,
    )!!

    private data class Fixture(
        val planId: UUID = UUID.randomUUID(),
        val tenantId: UUID = UUID.randomUUID(),
        val userId: UUID = UUID.randomUUID(),
        val propertyOneId: UUID = UUID.randomUUID(),
        val propertyTwoId: UUID = UUID.randomUUID(),
    )
}
