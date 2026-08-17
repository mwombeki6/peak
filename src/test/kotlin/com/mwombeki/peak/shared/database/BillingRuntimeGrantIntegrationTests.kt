package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * V88 opens the narrowest path that lets the worker apply a paid subscription.
 *
 * The interesting assertions here are the negative ones. It would be easy to fix
 * settlement by granting the API runtime write access to subscriptions, and that would
 * mean a tenant user could move their own subscription by calling an endpoint. These
 * tests pin the asymmetry: the worker may write, bound to its own tenant; the API
 * runtime may not write at all.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BillingRuntimeGrantIntegrationTests {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Test
    fun workerCanActivateASubscriptionForItsBoundTenant() {
        val tenant = seedTenant()

        inTransaction {
            setRole(WORKER_ROLE)
            bindTenant(tenant.tenantId)
            jdbcTemplate.update(
                """
                INSERT INTO tenant_subscriptions (tenant_id, plan_id, status, billing_cycle)
                VALUES (?, ?, 'active', 'monthly')
                """.trimIndent(),
                tenant.tenantId,
                tenant.planId,
            )
        }

        val status = inTransaction {
            setRole(WORKER_ROLE)
            bindTenant(tenant.tenantId)
            jdbcTemplate.queryForObject(
                "SELECT status FROM tenant_subscriptions WHERE tenant_id = ?",
                String::class.java,
                tenant.tenantId,
            )
        }
        assertEquals("active", status)
    }

    /**
     * Both negative cases assert on the *reason* rather than on any
     * `DataAccessException`. Spring maps SQLState 42501 to `BadSqlGrammarException`, so a
     * plain "it threw" assertion also passes when the statement is simply malformed —
     * which is exactly how a broken test survives a broken migration.
     */
    @Test
    fun workerCannotWriteAnotherTenantsSubscription() {
        val bound = seedTenant()
        val other = seedTenant()

        val failure = assertFailsWith<DataAccessException> {
            inTransaction {
                setRole(WORKER_ROLE)
                bindTenant(bound.tenantId)
                jdbcTemplate.update(
                    """
                    INSERT INTO tenant_subscriptions (tenant_id, plan_id, status, billing_cycle)
                    VALUES (?, ?, 'active', 'monthly')
                    """.trimIndent(),
                    other.tenantId,
                    other.planId,
                )
            }
        }

        assertTrue(
            failure.rootMessage().contains("row-level security", ignoreCase = true),
            "Expected an RLS rejection, got: ${failure.rootMessage()}",
        )
    }

    @Test
    fun apiRuntimeStillCannotWriteSubscriptions() {
        val tenant = seedTenant()

        val failure = assertFailsWith<DataAccessException> {
            inTransaction {
                setRole(API_ROLE)
                bindTenant(tenant.tenantId)
                jdbcTemplate.update(
                    """
                    INSERT INTO tenant_subscriptions (tenant_id, plan_id, status, billing_cycle)
                    VALUES (?, ?, 'active', 'monthly')
                    """.trimIndent(),
                    tenant.tenantId,
                    tenant.planId,
                )
            }
        }

        assertTrue(
            failure.rootMessage().contains("permission denied", ignoreCase = true),
            "Expected a table privilege rejection, got: ${failure.rootMessage()}",
        )
    }

    private fun Throwable.rootMessage(): String {
        var cause: Throwable = this
        while (cause.cause != null && cause.cause !== cause) {
            cause = requireNotNull(cause.cause)
        }
        return cause.message.orEmpty()
    }

    @Test
    fun workerCanConvergeTenantModules() {
        val tenant = seedTenant()

        inTransaction {
            setRole(WORKER_ROLE)
            bindTenant(tenant.tenantId)
            jdbcTemplate.update(
                """
                INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, source)
                VALUES (?, 'pos', true, 'system')
                """.trimIndent(),
                tenant.tenantId,
            )
            // The revocation half is the one that matters: a lapsed grant has to be able
            // to turn a module back off, which is what makes an expired subscription mean
            // anything at all.
            jdbcTemplate.update(
                "UPDATE tenant_modules SET is_enabled = false WHERE tenant_id = ? AND module_id = 'pos'",
                tenant.tenantId,
            )
        }

        val enabled = inTransaction {
            setRole(WORKER_ROLE)
            bindTenant(tenant.tenantId)
            jdbcTemplate.queryForObject(
                "SELECT is_enabled FROM tenant_modules WHERE tenant_id = ? AND module_id = 'pos'",
                Boolean::class.java,
                tenant.tenantId,
            )
        }
        assertEquals(false, enabled)
    }

    @Test
    fun platformBillingIsAnAcceptedOutboxDestination() {
        val accepted = jdbcTemplate.queryForObject(
            """
            SELECT pg_catalog.pg_get_constraintdef(oid) LIKE '%platform_billing%'
            FROM pg_catalog.pg_constraint
            WHERE conname = 'chk_outbox_events_destination'
            """.trimIndent(),
            Boolean::class.java,
        )
        assertTrue(accepted == true, "platform_billing must be a permitted outbox destination")
    }

    private fun <T> inTransaction(block: () -> T): T {
        return transactionTemplate.execute { block() } ?: error("Transaction returned null")
    }

    private fun setRole(role: String) {
        require(role in setOf(API_ROLE, WORKER_ROLE)) { "Unexpected test role: $role" }
        jdbcTemplate.execute("SET LOCAL ROLE $role")
    }

    private fun bindTenant(tenantId: UUID) {
        jdbcTemplate.queryForObject(
            "SELECT set_config('app.current_tenant_id', ?, true)",
            String::class.java,
            tenantId.toString(),
        )
    }

    private fun seedTenant(): TenantFixture {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId,
            "Billing Grant Plan $planId",
            "billing-grant-$planId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            tenantId,
            "Billing Grant Tenant $tenantId",
            "bill-${tenantId.toString().take(8)}",
            "tenant_${tenantId.toString().replace("-", "")}",
            planId,
        )
        return TenantFixture(tenantId, planId)
    }

    private data class TenantFixture(val tenantId: UUID, val planId: UUID)

    private companion object {
        const val API_ROLE = "pms_app"
        const val WORKER_ROLE = "pms_worker"
    }
}
