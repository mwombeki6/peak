package com.mwombeki.peak.shared.config

import com.mwombeki.peak.shared.context.TenantContext
import org.jooq.ExecuteContext
import org.jooq.impl.DefaultExecuteListener
import org.springframework.stereotype.Component
import java.sql.PreparedStatement

/**
 *
 * Intercepts jOOQ database execution requests and securely forces the active
 * Tenant Context variables into the live PostgreSQL connection session before
 * any query runs. This acts as our primary defense for Row Level Security (RLS).
 */
@Component
class DatabaseSessionInterceptor : DefaultExecuteListener() {

    override fun renderEnd(ctx: ExecuteContext) {
        val connection = ctx.connection() ?: return

        val tenantId = TenantContext.getTenantId()
        val userId = TenantContext.getTenantUserId()

        // 1. If no tenant context exists, PostgreSQL RLS will automatically block access to data.
        // We set these session variables using local scope attributes variables 'app.*'
        val tenantSql = "SET LOCAL app.current_tenant_id = ?"
        val userSql = "SET LOCAL app.current_tenant_user_id = ?"

        var tenantStmt: PreparedStatement? = null
        var userStmt: PreparedStatement? = null

        try {
            if (tenantId != null) {
                tenantStmt = connection.prepareStatement(tenantSql)
                tenantStmt.setString(1, tenantId.toString())
                tenantStmt.execute()
            }

            if (userId != null) {
                userStmt = connection.prepareStatement(userSql)
                userStmt.setString(1, userId.toString())
                userStmt.execute()
            }
        } finally {
            tenantStmt?.close()
            userStmt?.close()
        }
    }
}