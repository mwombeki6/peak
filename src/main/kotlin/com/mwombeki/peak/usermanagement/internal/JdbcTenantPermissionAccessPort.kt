package com.mwombeki.peak.usermanagement.internal

import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessPort
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessRequest
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class JdbcTenantPermissionAccessPort(
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val jdbcTemplate: JdbcTemplate,
) : TenantPermissionAccessPort {
    override fun requireAuthorized(request: TenantPermissionAccessRequest): UUID {
        val identity = requestContextHolder.current().identity
        require(identity is RequestIdentity.Tenant) {
            "Tenant user identity is required"
        }
        require(identity.tenantId == request.tenantId) {
            "Requested tenant does not match identity"
        }
        databaseSessionContext.bind(identity)
        val allowed = jdbcTemplate.queryForObject(
            "SELECT user_has_tenant_permission(?, ?, ?)",
            Boolean::class.java,
            identity.tenantUserId,
            request.tenantId,
            request.permissionCode,
        ) == true
        require(allowed) {
            request.denialMessage
        }
        return identity.tenantUserId
    }
}
