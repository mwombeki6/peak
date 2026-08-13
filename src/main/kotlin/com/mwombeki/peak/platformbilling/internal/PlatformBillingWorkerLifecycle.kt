package com.mwombeki.peak.platformbilling.internal

import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Runs the loops that make a subscription mean something over time.
 *
 * `SmartLifecycle` rather than `@Scheduled` on purpose. `application-worker.yaml` sets
 * `web-application-type: none`, and the only `@EnableScheduling` in the codebase is gated
 * on a servlet context — so a `@Scheduled` bean here would never fire in the very process
 * it was written for, and would fail silently rather than loudly.
 *
 * Two loops:
 *
 * - **reconcile** converges tenants whose entitlements have drifted. Settlement already
 *   converges its own tenant immediately; this is what catches *expiry*, which no event
 *   announces. Without it, buying works and lapsing does not.
 * - **attempt sweep** expires payment attempts the provider never came back about, so a
 *   customer whose PIN prompt vanished is not blocked from trying again forever by the
 *   one-open-attempt index.
 */
@Component
class PlatformBillingWorkerLifecycle(
    private val jdbcTemplate: JdbcTemplate,
    private val reconciler: EntitlementReconciler,
    private val lifecycleService: SubscriptionLifecycleService,
    private val statusReconciliation: PaymentStatusReconciliationService,
    private val renewalOfferService: RenewalOfferService,
    private val properties: PlatformBillingProperties,
    @Value("\${peak.runtime.mode:api}")
    private val runtimeMode: String,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(PlatformBillingWorkerLifecycle::class.java)
    private val running = AtomicBoolean(false)
    private var supervisorJob: Job? = null

    override fun start() {
        if (!properties.enabled || !runtimeMode.equals("worker", ignoreCase = true)) {
            return
        }
        if (!running.compareAndSet(false, true)) {
            return
        }

        val job = SupervisorJob()
        supervisorJob = job
        val scope = CoroutineScope(job + Dispatchers.IO)

        scope.launch(CoroutineName("platform-billing-reconcile")) { reconcileLoop() }
        scope.launch(CoroutineName("platform-billing-attempt-sweep")) { attemptSweepLoop() }
        scope.launch(CoroutineName("platform-billing-lifecycle")) { lifecycleLoop() }

        log.info("Started platform billing worker loops")
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        supervisorJob?.let { job -> runBlocking { job.cancelAndJoin() } }
        supervisorJob = null
        log.info("Stopped platform billing worker loops")
    }

    override fun stop(callback: Runnable) {
        stop()
        callback.run()
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE

    private suspend fun reconcileLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val tenants = tenantsNeedingReconciliation()
                tenants.forEach { tenantId ->
                    reconciler.reconcileTenant(tenantId, "reconcile-${UUID.randomUUID()}")
                    markReconciled(tenantId)
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                log.error("Platform billing reconciliation sweep failed", ex)
            }
            delay(RECONCILE_INTERVAL.toKotlinDuration())
        }
    }

    /**
     * Through a definer function, because these loops run with no tenant bound and every
     * table they read is under `tenant_id = current_tenant_id()`. Querying directly would
     * match nothing and report it as an empty sweep — silently correct-looking and
     * completely inert.
     */
    private fun tenantsNeedingReconciliation(): List<UUID> {
        return jdbcTemplate.query(
            "SELECT tenant_id FROM platform_billing_tenants_due(?)",
            { rs, _ -> rs.getObject("tenant_id", UUID::class.java) },
            RECONCILE_BATCH,
        )
    }

    private fun markReconciled(tenantId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO peak_reconciliation_state (tenant_id, last_run_at, next_run_at, consecutive_failures)
            VALUES (?, now(), now() + interval '1 minute', 0)
            ON CONFLICT (tenant_id) DO UPDATE
            SET last_run_at = now(),
                next_run_at = now() + interval '1 minute',
                consecutive_failures = 0,
                last_error = NULL,
                updated_at = now()
            """.trimIndent(),
            tenantId,
        )
    }

    /**
     * Walks tenants through GRACE, RESTRICTED and SUSPENDED as their cover runs out.
     *
     * Slower than the reconcile loop because the states it moves between are measured in
     * days: a tenant does not become suspended more precisely than once a quarter of an
     * hour, and polling faster would only add load.
     */
    private suspend fun lifecycleLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                tenantsNeedingReconciliation().forEach { tenantId ->
                    lifecycleService.advance(tenantId, "lifecycle-${UUID.randomUUID()}")
                }
                // An offer, not a quoted purchase. A background job must not take the
                // tenant's single open-order slot and hold it for a fortnight — the owner
                // who tries to buy an add-on the next morning would simply be refused.
                val offered = renewalOfferService.offerDueRenewals(
                    noticeDays = SubscriptionLifecycleService.NOTICE_PERIOD.toDays().toInt(),
                    limit = RECONCILE_BATCH,
                )
                if (offered > 0) {
                    log.info("Offered renewal to {} tenants", offered)
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                log.error("Platform billing lifecycle sweep failed", ex)
            }
            delay(LIFECYCLE_INTERVAL.toKotlinDuration())
        }
    }

    private suspend fun attemptSweepLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                // Both halves live in the function: releasing the attempt without returning
                // the purchase to 'quoted' would leave the customer unable to retry, blocked
                // by the one-open-attempt index against an attempt that is already dead.
                val expired = jdbcTemplate.queryForObject(
                    "SELECT platform_billing_expire_stale_attempts()",
                    Int::class.java,
                ) ?: 0
                if (expired > 0) {
                    log.info(
                        "Moved {} platform billing attempts to reconciliation_required",
                        expired,
                    )
                }

                // Ask the provider what actually happened. Without this a lost callback is
                // indistinguishable from a failed payment, and the customer who was debited
                // gets nothing and is invited to pay again.
                val resolved = statusReconciliation.reconcileDueAttempts(RECONCILE_BATCH)
                if (resolved > 0) {
                    log.info("Resolved {} platform billing payments by status query", resolved)
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                log.error("Platform billing attempt sweep failed", ex)
            }
            delay(SWEEP_INTERVAL.toKotlinDuration())
        }
    }

    private companion object {
        val RECONCILE_INTERVAL: Duration = Duration.ofSeconds(60)
        val SWEEP_INTERVAL: Duration = Duration.ofMinutes(2)
        val LIFECYCLE_INTERVAL: Duration = Duration.ofMinutes(15)
        const val RECONCILE_BATCH = 200
    }
}
