package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.tenantmanagement.api.TenantEntitlementProjectionPort
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * Gives late payment a graduated consequence instead of a cliff.
 *
 * ```
 * ACTIVE ──T-14d──> RENEWAL_DUE ──lapse──> GRACE ──7d──> RESTRICTED ──14d──> SUSPENDED
 *    ^                                        │              │                  │
 *    └────────────────── payment ─────────────┴──────────────┴──────────────────┘
 * ```
 *
 * ## What each state costs the customer
 *
 * **GRACE** restricts nothing. A hotel three days late must not discover it at 2am with a
 * guest at the desk; they get a warning and a fortnight of ordinary operation.
 *
 * **RESTRICTED** denies growth and administration — inviting users, adding a property,
 * configuring POS — while front desk, billing, payments, fiscal, night audit and
 * housekeeping keep working.
 *
 * **SUSPENDED** is read-only plus four non-negotiables: check out a guest, take a payment,
 * export your data, and buy a subscription. A tenant must never be locked out of paying,
 * and must never be able to strand a guest.
 *
 * ## Why this only moves lifecycle_status
 *
 * It is tempting to expire `tenant_subscriptions.status` at SUSPENDED. That would be a trap.
 * An expired row leaves the service-granting set, so `effective_tenant_entitlement` resolves
 * no plan entitlements, the reconciler disables every module, and `can_access_module` then
 * fails at `is_tenant_module_enabled` — before `tenant_restriction_permits` is consulted at
 * all. Every allowance above would become unreachable and the suspended hotel could not
 * check anyone out.
 *
 * So the two mechanisms stay in their lanes: grants decide which modules a tenant has,
 * `lifecycle_status` decides how much of them a late payer may use. Expiring a subscription
 * is an operator decision, not an automatic consequence of being overdue.
 *
 * ## Nothing here charges anything
 *
 * At T-14 a renewal quote is prepared and the customer is told. It is not collected. Mobile
 * money has no mandate — there is no stored instrument to charge and no authority to charge
 * it — so every collection in this system begins with a customer action in a live request.
 */
@Component
class SubscriptionLifecycleService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val tenantProjection: TenantEntitlementProjectionPort,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(SubscriptionLifecycleService::class.java)

    /**
     * Moves one tenant to whatever state their paid-through date now implies.
     *
     * Idempotent: a tenant already in the right state is left alone, and no lifecycle event
     * is recorded for a move that did not happen.
     */
    fun advance(tenantId: UUID, correlationId: String): BillingLifecycleState? {
        return try {
            transactionTemplate.execute {
                databaseSessionContext.bind(
                    RequestIdentity.Public(tenantId = tenantId, correlationId = correlationId),
                )
                val paidThrough = paidThrough(tenantId)
                    ?: return@execute null

                val target = stateFor(paidThrough)
                val previous = tenantProjection.applyBillingLifecycle(
                    tenantId = tenantId,
                    lifecycleStatus = target.lifecycleStatus,
                    subscriptionStatus = target.subscriptionStatus,
                    graceEndsAt = paidThrough.plus(GRACE_PERIOD),
                )

                if (previous != null) {
                    recordTransition(tenantId, previous, target, paidThrough)
                }
                target
            }
        } catch (ex: Exception) {
            log.warn("Billing lifecycle advance failed for tenant {}", tenantId, ex)
            null
        }
    }

    private fun stateFor(paidThrough: Instant): BillingLifecycleState =
        stateFor(paidThrough, clock.instant())

    /**
     * How long the tenant has paid for, taken from grants rather than from the subscription
     * row, because grants are what a purchase actually produces.
     */
    private fun paidThrough(tenantId: UUID): Instant? {
        return jdbcTemplate.query(
            """
            SELECT max(ends_at) AS paid_through
            FROM peak_product_grants
            WHERE tenant_id = ?
              AND revoked_at IS NULL
              AND status <> 'revoked'
              AND ends_at IS NOT NULL
            """.trimIndent(),
            { rs, _ -> rs.getTimestamp("paid_through")?.toInstant() },
            tenantId,
        ).firstOrNull()
    }

    private fun recordTransition(
        tenantId: UUID,
        previousLifecycleStatus: String,
        target: BillingLifecycleState,
        paidThrough: Instant,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO peak_billing_lifecycle_events (
                tenant_id, from_state, to_state, reason, actor
            ) VALUES (?, ?, ?, ?, 'system')
            """.trimIndent(),
            tenantId,
            previousLifecycleStatus,
            target.lifecycleStatus,
            "${target.name}: cover ran to $paidThrough",
        )
    }

    enum class BillingLifecycleState(
        val lifecycleStatus: String,
        val subscriptionStatus: String,
    ) {
        /** Paid, with more than the notice period to run. */
        ACTIVE("active", "active"),

        /** Inside the notice period. A quote is prepared; nothing is charged. */
        RENEWAL_DUE("active", "active"),

        /** Lapsed, but nothing is restricted yet. */
        GRACE("active", "past_due"),

        /** Growth and administration denied; operations continue. */
        RESTRICTED("restricted", "past_due"),

        /**
         * Read-only, plus checkout, payment collection, data export and subscription
         * purchase. Note this is still `past_due` rather than `expired` — see the class
         * documentation for why expiring the row would make the allowances unreachable.
         */
        SUSPENDED("suspended", "past_due"),
    }

    internal companion object {
        /**
         * The state a tenant is in, derived purely from how long ago their cover ran out.
         *
         * A pure function of two instants on purpose. The state is never stored as a
         * separate fact that could drift from the dates it claims to summarise, and being
         * pure means every boundary can be walked in a unit test rather than waited for.
         */
        fun stateFor(paidThrough: Instant, now: Instant): BillingLifecycleState {
            val lapsedFor = Duration.between(paidThrough, now)
            return when {
                lapsedFor < NOTICE_PERIOD.negated() -> BillingLifecycleState.ACTIVE
                lapsedFor.isNegative -> BillingLifecycleState.RENEWAL_DUE
                lapsedFor < GRACE_PERIOD -> BillingLifecycleState.GRACE
                lapsedFor < GRACE_PERIOD.plus(RESTRICTION_PERIOD) -> BillingLifecycleState.RESTRICTED
                else -> BillingLifecycleState.SUSPENDED
            }
        }

        /** How long before expiry the customer is told to renew. */
        val NOTICE_PERIOD: Duration = Duration.ofDays(14)

        /** How long after expiry nothing is restricted at all. */
        val GRACE_PERIOD: Duration = Duration.ofDays(7)

        /** How long the restricted state lasts before suspension. */
        val RESTRICTION_PERIOD: Duration = Duration.ofDays(14)
    }
}
