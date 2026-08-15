package com.mwombeki.peak.realtime.internal

import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * Server-side subscription authorization. A client must never subscribe to another tenant's,
 * property's, or outlet's stream merely by knowing an id — the realtime layer is not a side
 * door around application authorization.
 *
 * Every scoped destination is resolved against the database inside the subscriber's own
 * session context (RLS-bound), so an id that does not belong to the authenticated tenant
 * resolves to nothing rather than leaking.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RealtimeSubscriptionAuthorizer(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val transactionTemplate: TransactionTemplate,
) {
    fun canSubscribe(
        identity: RequestIdentity,
        tenantId: UUID,
        propertyId: UUID,
    ): Boolean {
        if (identity !is RequestIdentity.Tenant || identity.tenantId != tenantId) {
            return false
        }

        return transactionTemplate.execute {
            databaseSessionContext.bind(identity)
            jdbcTemplate.queryForObject(
                "SELECT can_access_module(?, ?, ?, ?, ?)",
                Boolean::class.java,
                identity.tenantUserId,
                tenantId,
                propertyId,
                REALTIME_MODULE_ID,
                REALTIME_STREAM_PERMISSION,
            ) == true
        }
    }

    fun canSubscribeDestination(
        identity: RequestIdentity,
        target: RealtimeSubscriptionTarget,
    ): Boolean {
        if (identity !is RequestIdentity.Tenant) {
            return false
        }

        val scope = resolveScope(identity, target) ?: return false
        if (identity.tenantId != scope.tenantId) {
            return false
        }
        return canSubscribe(identity, scope.tenantId, scope.propertyId)
    }

    /** Resolves a scoped destination to its owning tenant/property, RLS-bound. */
    private fun resolveScope(
        identity: RequestIdentity.Tenant,
        target: RealtimeSubscriptionTarget,
    ): TenantPropertyScope? = transactionTemplate.execute {
        databaseSessionContext.bind(identity)
        when (target) {
            is RealtimeSubscriptionTarget.PropertyStream -> {
                if (target.tenantId != identity.tenantId) {
                    return@execute null
                }
                TenantPropertyScope(target.tenantId, target.propertyId)
            }
            is RealtimeSubscriptionTarget.PropertyOperations -> {
                val propertyId = jdbcTemplate.queryForObject(
                    "SELECT id FROM properties WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL",
                    UUID::class.java,
                    identity.tenantId,
                    target.propertyId,
                ) ?: return@execute null
                TenantPropertyScope(identity.tenantId, propertyId)
            }
            is RealtimeSubscriptionTarget.Outlet -> {
                val row = jdbcTemplate.query(
                    """
                    SELECT tenant_id, property_id FROM outlets
                    WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    { rs, _ ->
                        TenantPropertyScope(
                            rs.getObject("tenant_id", UUID::class.java),
                            rs.getObject("property_id", UUID::class.java),
                        )
                    },
                    identity.tenantId,
                    target.outletId,
                )
                row.singleOrNull()
            }
            is RealtimeSubscriptionTarget.Order -> {
                val row = jdbcTemplate.query(
                    """
                    SELECT tenant_id, property_id FROM pos_orders
                    WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    { rs, _ ->
                        TenantPropertyScope(
                            rs.getObject("tenant_id", UUID::class.java),
                            rs.getObject("property_id", UUID::class.java),
                        )
                    },
                    identity.tenantId,
                    target.orderId,
                )
                row.singleOrNull()
            }
            is RealtimeSubscriptionTarget.Payment -> {
                val row = jdbcTemplate.query(
                    """
                    SELECT tenant_id, property_id FROM payment_transactions
                    WHERE tenant_id = ? AND id = ?
                    """.trimIndent(),
                    { rs, _ ->
                        TenantPropertyScope(
                            rs.getObject("tenant_id", UUID::class.java),
                            rs.getObject("property_id", UUID::class.java),
                        )
                    },
                    identity.tenantId,
                    target.paymentTransactionId,
                )
                row.singleOrNull()
            }
        }
    }

    private data class TenantPropertyScope(
        val tenantId: UUID,
        val propertyId: UUID,
    )

    private companion object {
        const val REALTIME_MODULE_ID = "realtime"
        const val REALTIME_STREAM_PERMISSION = "realtime.stream"
    }
}