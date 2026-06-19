package com.mwombeki.peak.usermanagement.internal

import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.AuthorizationDecision
import com.mwombeki.peak.usermanagement.api.AuthorizationPort
import com.mwombeki.peak.usermanagement.api.GuardMode
import com.mwombeki.peak.usermanagement.api.RouteAuthorizationRequest
import com.mwombeki.peak.usermanagement.api.RouteScope
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

@Component
class JdbcAuthorizationPort(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
) : AuthorizationPort {
    override fun authorize(request: RouteAuthorizationRequest): AuthorizationDecision {
        require(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Authorization checks must run inside an active transaction"
        }

        val identity = requestContextHolder.current().identity

        return when (request.guardMode) {
            GuardMode.STAFF_PERMISSION -> {
                databaseSessionContext.bind(identity)
                authorizeStaffPermission(identity, request)
            }
            GuardMode.MODULE_ONLY -> authorizePublicModule(identity, request)
            GuardMode.PLATFORM_PERMISSION -> {
                databaseSessionContext.bind(identity)
                authorizePlatformPermission(identity, request)
            }
            GuardMode.PUBLIC_TOKEN -> authorizePublicToken(identity, request)
        }
    }

    private fun authorizeStaffPermission(
        identity: RequestIdentity,
        request: RouteAuthorizationRequest,
    ): AuthorizationDecision {
        if (identity !is RequestIdentity.Tenant) {
            return AuthorizationDecision.denied("Tenant identity is required")
        }
        if (request.tenantId == null) {
            return AuthorizationDecision.denied("Tenant route scope requires tenant id")
        }
        if (request.tenantId != identity.tenantId) {
            return AuthorizationDecision.denied("Requested tenant does not match identity")
        }
        if (request.permissionCode.isNullOrBlank()) {
            return AuthorizationDecision.denied("Staff permission guard requires permission")
        }
        if (request.routeScope == RouteScope.TENANT && request.propertyId != null) {
            return AuthorizationDecision.denied("Tenant route scope must not include property id")
        }
        if (request.routeScope == RouteScope.PROPERTY && request.propertyId == null) {
            return AuthorizationDecision.denied("Property route scope requires property id")
        }
        if (request.routeScope !in setOf(RouteScope.TENANT, RouteScope.PROPERTY)) {
            return AuthorizationDecision.denied("Invalid route scope for staff permission guard")
        }

        val allowed = jdbcTemplate.queryForObject(
            "SELECT can_access_module(?, ?, ?, ?, ?)",
            Boolean::class.java,
            identity.tenantUserId,
            request.tenantId,
            request.propertyId,
            request.moduleId,
            request.permissionCode,
        ) == true

        return if (allowed) {
            AuthorizationDecision.allowed()
        } else {
            AuthorizationDecision.denied("Tenant user lacks required module permission")
        }
    }

    private fun authorizePublicModule(
        identity: RequestIdentity,
        request: RouteAuthorizationRequest,
    ): AuthorizationDecision {
        if (identity !is RequestIdentity.Public) {
            return AuthorizationDecision.denied("Public identity is required")
        }
        if (request.routeScope != RouteScope.PUBLIC_PROPERTY) {
            return AuthorizationDecision.denied("Invalid route scope for public module guard")
        }
        if (request.propertyId == null) {
            return AuthorizationDecision.denied("Public module guard requires property")
        }
        val tenantId = request.tenantId
            ?: resolvePublicTenantId(request.propertyId, request.moduleId)
            ?: return AuthorizationDecision.denied("Public module is not accessible")

        if (identity.tenantId != null && identity.tenantId != tenantId) {
            return AuthorizationDecision.denied("Requested tenant does not match public identity")
        }
        if (identity.propertyId != null && identity.propertyId != request.propertyId) {
            return AuthorizationDecision.denied("Requested property does not match public identity")
        }

        val allowed = jdbcTemplate.queryForObject(
            "SELECT can_access_public_module(?, ?, ?)",
            Boolean::class.java,
            tenantId,
            request.propertyId,
            request.moduleId,
        ) == true

        return if (allowed) {
            AuthorizationDecision.allowed()
        } else {
            AuthorizationDecision.denied("Public module is not accessible")
        }
    }

    private fun resolvePublicTenantId(
        propertyId: UUID,
        moduleId: String,
    ): UUID? {
        return jdbcTemplate.query(
            """
            SELECT tenant_id
            FROM resolve_public_property_scope(?, ?)
            """.trimIndent(),
            { rs, _ -> rs.getObject("tenant_id", UUID::class.java) },
            propertyId,
            moduleId,
        ).singleOrNull()
    }

    private fun authorizePlatformPermission(
        identity: RequestIdentity,
        request: RouteAuthorizationRequest,
    ): AuthorizationDecision {
        val platformUserId = when (identity) {
            is RequestIdentity.Platform -> identity.platformUserId
            is RequestIdentity.Support -> identity.platformUserId
            else -> return AuthorizationDecision.denied("Platform identity is required")
        }
        if (request.routeScope != RouteScope.PLATFORM) {
            return AuthorizationDecision.denied("Invalid route scope for platform guard")
        }
        if (request.permissionCode.isNullOrBlank()) {
            return AuthorizationDecision.denied("Platform guard requires permission")
        }

        val allowed = jdbcTemplate.queryForObject(
            "SELECT platform_user_has_permission(?, ?)",
            Boolean::class.java,
            platformUserId,
            request.permissionCode,
        ) == true

        return if (allowed) {
            AuthorizationDecision.allowed()
        } else {
            AuthorizationDecision.denied("Platform user lacks required permission")
        }
    }

    private fun authorizePublicToken(
        identity: RequestIdentity,
        request: RouteAuthorizationRequest,
    ): AuthorizationDecision {
        if (identity !is RequestIdentity.Public) {
            return AuthorizationDecision.denied("Public identity is required")
        }
        if (request.routeScope != RouteScope.PUBLIC) {
            return AuthorizationDecision.denied("Invalid route scope for public token guard")
        }
        if (request.permissionCode != null) {
            return AuthorizationDecision.denied("Public token guard must not require permission")
        }
        if (request.tenantId != null || request.propertyId != null) {
            return AuthorizationDecision.denied("Public token guard must not include tenant or property")
        }

        return AuthorizationDecision.allowed()
    }
}
