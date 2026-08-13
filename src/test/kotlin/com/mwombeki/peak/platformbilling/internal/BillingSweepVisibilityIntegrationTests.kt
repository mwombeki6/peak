package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.TestcontainersConfiguration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Pins the one thing about the worker sweeps that reading them cannot show.
 *
 * The loops run with no tenant bound, and every table they touch is under
 * `tenant_id = current_tenant_id()`. From an unbound session that matches nothing — not an
 * error, just an empty result — so a broken sweep reports zero rows and looks perfectly
 * healthy while doing nothing at all. Expiry would never revoke and nobody would notice.
 *
 * Every assertion runs under `SET ROLE pms_worker`. The test connection is a superuser and
 * bypasses row-level security entirely, so asserting from it would prove nothing: the
 * checks have to run as the role the worker actually connects as.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BillingSweepVisibilityIntegrationTests {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun resetSession() {
        jdbcTemplate.execute("RESET ROLE")
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun theWorkerSeesNoGrantsDirectlyButTheSweepFunctionFindsThem() {
        val tenantId = seedTenantWithGrant()

        asWorker {
            val directlyVisible = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM peak_product_grants",
                Int::class.java,
            ) ?: -1
            assertEquals(
                0,
                directlyVisible,
                "an unbound worker session must not see tenant-scoped grants directly — " +
                    "if this ever returns rows, row-level security has been weakened",
            )

            val due = jdbcTemplate.query(
                "SELECT tenant_id FROM platform_billing_tenants_due(?)",
                { rs, _ -> rs.getObject("tenant_id", UUID::class.java) },
                DUE_LIMIT,
            )
            assertTrue(
                tenantId in due,
                "the reconcile sweep must find a tenant it has never been bound to, " +
                    "or expiry silently never revokes anything",
            )
        }
    }

    @Test
    fun theAttemptSweepExpiresAStaleAttemptAndReleasesItsPurchase() {
        val tenantId = seedTenantWithGrant()
        val purchaseId = seedAwaitingPurchase(tenantId)
        val reference = seedAttempt(tenantId, purchaseId, expiresInterval = "- interval '1 minute'")

        val expired = asWorker {
            jdbcTemplate.queryForObject(
                "SELECT platform_billing_expire_stale_attempts()",
                Int::class.java,
            ) ?: 0
        }

        assertTrue(expired >= 1, "the stale attempt should have been expired")
        assertEquals("expired", attemptStatus(reference))
        assertEquals(
            "quoted",
            purchaseStatus(reference),
            "releasing the attempt without releasing the purchase would leave the customer " +
                "unable to retry, blocked by the one-open-attempt index against a dead attempt",
        )
    }

    @Test
    fun theAttemptSweepLeavesAPromptTheCustomerMayStillBeAnsweringAlone() {
        val tenantId = seedTenantWithGrant()
        val purchaseId = seedAwaitingPurchase(tenantId)
        val reference = seedAttempt(tenantId, purchaseId, expiresInterval = "+ interval '15 minutes'")

        asWorker {
            jdbcTemplate.queryForObject(
                "SELECT platform_billing_expire_stale_attempts()",
                Int::class.java,
            )
        }

        assertEquals("pending", attemptStatus(reference))
        assertEquals("awaiting_payment", purchaseStatus(reference))
    }

    private fun <T> asWorker(block: () -> T): T {
        jdbcTemplate.execute("SET ROLE pms_worker")
        return try {
            block()
        } finally {
            jdbcTemplate.execute("RESET ROLE")
        }
    }

    private fun attemptStatus(reference: String): String? =
        jdbcTemplate.queryForObject(
            "SELECT attempt_status FROM resolve_platform_billing_scope(?)",
            String::class.java,
            reference,
        )

    private fun purchaseStatus(reference: String): String? =
        jdbcTemplate.queryForObject(
            "SELECT purchase_status FROM resolve_platform_billing_scope(?)",
            String::class.java,
            reference,
        )

    private fun seedAttempt(
        tenantId: UUID,
        purchaseId: UUID,
        expiresInterval: String,
    ): String {
        val reference = "SWEEP-${UUID.randomUUID().toString().take(8)}".uppercase()
        jdbcTemplate.update(
            """
            INSERT INTO peak_payment_attempts (
                purchase_id, tenant_id, attempt_no, provider, payer_msisdn,
                amount, currency, internal_reference, status, expires_at
            ) VALUES (?, ?, 1, 'azampay', '255700000001', 30000.00, 'TZS', ?, 'pending',
                      now() $expiresInterval)
            """.trimIndent(),
            purchaseId,
            tenantId,
            reference,
        )
        return reference
    }

    private fun seedAwaitingPurchase(tenantId: UUID): UUID {
        val purchaseId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO peak_purchases (
                id, tenant_id, status, currency, term_months, total_amount,
                period_starts_at, period_ends_at, quote_expires_at
            ) VALUES (?, ?, 'awaiting_payment', 'TZS', 1, 30000.00,
                      now(), now() + interval '30 days', now() + interval '2 hours')
            """.trimIndent(),
            purchaseId,
            tenantId,
        )
        return purchaseId
    }

    private fun seedTenantWithGrant(): UUID {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId,
            "Plan $planId",
            "plan-$planId",
        )
        jdbcTemplate.update(
            "INSERT INTO tenants (id, name, slug, schema_name, plan_id) VALUES (?, ?, ?, ?, ?)",
            tenantId,
            "Tenant $tenantId",
            "tenant-$tenantId",
            "tenant_$tenantId".replace("-", "_"),
            planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO peak_product_grants (
                tenant_id, product_code, source, status, granted_entitlements
            ) VALUES (?, 'peak_core', 'purchase', 'active',
                      '{"module.pos": {"is_enabled": true, "auto_activate": true, "value": {}}}'::jsonb)
            """.trimIndent(),
            tenantId,
        )
        return tenantId
    }

    private companion object {
        const val DUE_LIMIT = 500
    }
}
