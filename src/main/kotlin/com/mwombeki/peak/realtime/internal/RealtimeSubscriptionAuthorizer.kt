package com.mwombeki.peak.realtime.internal

import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

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
        } == true
    }

    private companion object {
        const val REALTIME_MODULE_ID = "realtime"
        const val REALTIME_STREAM_PERMISSION = "realtime.stream"
    }
}
