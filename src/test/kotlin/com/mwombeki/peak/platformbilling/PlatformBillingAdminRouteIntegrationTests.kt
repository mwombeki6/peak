package com.mwombeki.peak.platformbilling

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Proves the split between seeing the reconciliation queue and acting on it is real.
 *
 * `platform.billing.view` and `platform.billing.reconcile` are separate permissions so that
 * most support staff can find out why a tenant is stuck without being able to declare that
 * money arrived. That is only true if the guard enforces it, which the service-level tests
 * could not show — they bypass HTTP entirely.
 *
 * Writing this found that neither permission existed in `platform_permissions`, the table
 * `platform_user_has_permission` actually reads. Both were only in `permission_catalog`. The
 * split was therefore decorative: nobody could be granted either, so the routes were
 * reachable only by `platform.admin.all` and the RLS policies gating on
 * `platform.billing.view` admitted only superusers. V101 fixes it.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = ["peak.security.request-context.allow-header-identity=true"],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PlatformBillingAdminRouteIntegrationTests {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun resetSession() {
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun anOperatorWhoMayOnlyLookCanReadTheQueue() {
        val fixture = stuckPayment(grant = listOf("platform.billing.view"))

        assertEquals(200, status(get("/api/v1/platform/billing/reconciliation"), fixture))
        assertEquals(200, status(get("/api/v1/platform/billing/standing"), fixture))
        assertEquals(200, status(get("/api/v1/platform/billing/receipts"), fixture))
    }

    /**
     * The gap this file exists to close. Reading why a tenant is stuck is a support task;
     * declaring that their payment arrived is a financial decision.
     */
    @Test
    fun anOperatorWhoMayOnlyLookCannotRequeryOrResolve() {
        val fixture = stuckPayment(grant = listOf("platform.billing.view"))

        assertEquals(
            403,
            status(
                post("/api/v1/platform/billing/reconciliation/${fixture.attemptId}/requery"),
                fixture,
            ),
            "requery costs a provider call and records an action; view alone must not do it",
        )
        assertEquals(
            403,
            status(
                post("/api/v1/platform/billing/reconciliation/${fixture.attemptId}/resolutions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"resolution":"CONFIRMED_PAID",
                         "evidenceType":"PROVIDER_PORTAL",
                         "evidenceReference":"TXN-FORGED",
                         "observedAmount":30000.00,
                         "observedCurrency":"TZS",
                         "reason":"Attempting to settle without the right to"}
                        """.trimIndent(),
                    ),
                fixture,
            ),
        )

        // A 403 that still moved money would be worse than an allow, because nothing would
        // look wrong.
        assertEquals("reconciliation_required", attemptStatus(fixture.attemptId))
        assertEquals("awaiting_payment", purchaseStatus(fixture.purchaseId))
        assertEquals(0, resolutionCount(fixture.attemptId))
        assertEquals(0, grantCount(fixture.purchaseId))
    }

    @Test
    fun anOperatorWithTheReconcileRightMayAct() {
        val fixture = stuckPayment(
            grant = listOf("platform.billing.view", "platform.billing.reconcile"),
        )

        // The control. Without it the denials above could be caused by the permissions
        // being ungrantable rather than by the split being enforced — which is exactly
        // what was wrong before V101.
        val outcome = status(
            post("/api/v1/platform/billing/reconciliation/${fixture.attemptId}/resolutions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"resolution":"CONFIRMED_FAILED",
                     "reason":"Provider support confirmed the debit was reversed"}
                    """.trimIndent(),
                ),
            fixture,
        )

        assertTrue(
            outcome in 200..299,
            "an operator holding platform.billing.reconcile must be able to act (got $outcome)",
        )
        assertEquals(1, resolutionCount(fixture.attemptId))
    }

    @Test
    fun anOperatorWithNoBillingRightsSeesNothingAtAll() {
        val fixture = stuckPayment(grant = listOf("platform.tenants.view"))

        assertEquals(403, status(get("/api/v1/platform/billing/reconciliation"), fixture))
        assertEquals(403, status(get("/api/v1/platform/billing/standing"), fixture))
    }

    /**
     * A tenant identity must not reach the platform surface however it is presented. The
     * reconciliation queue spans every tenant, so leaking it would leak Peak's whole revenue
     * position to one customer.
     */
    @Test
    fun aTenantIdentityCannotReachThePlatformBillingSurface() {
        val fixture = stuckPayment(grant = listOf("platform.billing.view"))

        val response = mockMvc.perform(
            get("/api/v1/platform/billing/reconciliation")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-${UUID.randomUUID()}")
                .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, UUID.randomUUID().toString()),
        ).andReturn().response

        assertEquals(
            403,
            response.status,
            "a tenant must never see across tenants: ${response.contentAsString}",
        )
    }

    private fun status(builder: MockHttpServletRequestBuilder, fixture: AdminFixture): Int {
        return mockMvc.perform(
            builder.secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-${UUID.randomUUID()}")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "idem-${UUID.randomUUID()}")
                .header(PeakRequestHeaders.PLATFORM_USER_ID, fixture.platformUserId.toString()),
        ).andReturn().response.status
    }

    private fun attemptStatus(attemptId: UUID): String? =
        jdbcTemplate.queryForObject(
            "SELECT status FROM peak_payment_attempts WHERE id = ?",
            String::class.java,
            attemptId,
        )

    private fun purchaseStatus(purchaseId: UUID): String? =
        jdbcTemplate.queryForObject(
            "SELECT status FROM peak_purchases WHERE id = ?",
            String::class.java,
            purchaseId,
        )

    private fun resolutionCount(attemptId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM peak_reconciliation_resolutions WHERE payment_attempt_id = ?",
            Int::class.java,
            attemptId,
        ) ?: 0

    private fun grantCount(purchaseId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM peak_product_grants WHERE source_purchase_id = ?",
            Int::class.java,
            purchaseId,
        ) ?: 0

    private fun stuckPayment(grant: List<String>): AdminFixture {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val purchaseId = UUID.randomUUID()
        val attemptId = UUID.randomUUID()
        val platformUserId = UUID.randomUUID()
        val roleId = UUID.randomUUID()
        val amount = BigDecimal("30000.00")

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId, "Plan $planId", "plan-$planId",
        )
        jdbcTemplate.update(
            "INSERT INTO tenants (id, name, slug, schema_name, plan_id) VALUES (?, ?, ?, ?, ?)",
            tenantId, "Tenant $tenantId", "tenant-$tenantId",
            "tenant_$tenantId".replace("-", "_"), planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, email, full_name, status)
            VALUES (?, ?, 'Billing Operator', 'active')
            """.trimIndent(),
            platformUserId, "operator-$platformUserId@peak.example",
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_roles (id, name, code, is_system, is_active)
            VALUES (?, ?, ?, false, true)
            """.trimIndent(),
            roleId, "Role $roleId", "role_${roleId.toString().take(8)}",
        )
        jdbcTemplate.update(
            "INSERT INTO platform_user_roles (platform_user_id, platform_role_id) VALUES (?, ?)",
            platformUserId, roleId,
        )
        // Exactly the permissions asked for, and no platform.admin.all — which would match
        // everything and make every assertion in this file meaningless.
        grant.forEach { code ->
            jdbcTemplate.update(
                """
                INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
                SELECT ?, id FROM platform_permissions WHERE code = ?
                """.trimIndent(),
                roleId, code,
            )
        }

        jdbcTemplate.update(
            """
            INSERT INTO peak_purchases (
                id, tenant_id, status, currency, term_months, total_amount,
                period_starts_at, period_ends_at, quote_expires_at
            ) VALUES (?, ?, 'awaiting_payment', 'TZS', 1, ?,
                      now(), now() + interval '30 days', now() + interval '2 hours')
            """.trimIndent(),
            purchaseId, tenantId, amount,
        )
        jdbcTemplate.update(
            """
            INSERT INTO peak_payment_attempts (
                id, purchase_id, tenant_id, attempt_no, provider, payment_method,
                payer_msisdn, amount, currency, internal_reference, status
            ) VALUES (?, ?, ?, 1, 'azampay', 'mobile_money', '255700000001', ?, 'TZS', ?,
                      'reconciliation_required')
            """.trimIndent(),
            attemptId, purchaseId, tenantId, amount,
            "ADM-${UUID.randomUUID().toString().take(8)}".uppercase(),
        )

        return AdminFixture(tenantId, purchaseId, attemptId, platformUserId)
    }

    private data class AdminFixture(
        val tenantId: UUID,
        val purchaseId: UUID,
        val attemptId: UUID,
        val platformUserId: UUID,
    )
}
