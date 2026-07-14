package com.mwombeki.peak.usermanagement.internal

import com.mwombeki.peak.audit.api.AuditOutcome
import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.PlatformAuditEvent
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.AuthorizationDecision
import com.mwombeki.peak.usermanagement.api.SupportTenantAccessPort
import com.mwombeki.peak.usermanagement.api.SupportTenantAccessRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class JdbcSupportTenantAccessPort(
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val jdbcTemplate: JdbcTemplate,
    private val auditPort: AuditPort,
) : SupportTenantAccessPort {
    override fun authorize(request: SupportTenantAccessRequest): AuthorizationDecision {
        return when (val identity = requestContextHolder.current().identity) {
            is RequestIdentity.Platform -> AuthorizationDecision.allowed()
            is RequestIdentity.Support -> authorizeSupport(identity, request)
            else -> AuthorizationDecision.denied("Platform identity is required")
        }
    }

    private fun authorizeSupport(
        identity: RequestIdentity.Support,
        request: SupportTenantAccessRequest,
    ): AuthorizationDecision {
        databaseSessionContext.bind(identity)

        val decision = when {
            request.tenantId == null -> AuthorizationDecision.denied(
                "Support identity requires a tenant-targeted platform route",
            )
            request.tenantId != identity.tenantId -> AuthorizationDecision.denied(
                "Support session tenant does not match target tenant",
            )
            !hasActiveSessionGrant(identity, request) -> AuthorizationDecision.denied(
                "Active approved support break-glass access is required for tenant operation",
            )
            else -> AuthorizationDecision.allowed()
        }

        if (!decision.allowed || request.auditSuccess) {
            auditPort.recordPlatformEventImmediately(
                PlatformAuditEvent(
                    action = "platform.support.break_glass.access",
                    targetTenantId = request.tenantId ?: identity.tenantId,
                    resource = AuditResource(
                        "platform_break_glass_access",
                        identity.supportSessionId,
                    ),
                    outcome = if (decision.allowed) {
                        AuditOutcome.SUCCESS
                    } else {
                        AuditOutcome.DENIED
                    },
                    after = mapOf(
                        "tenantId" to request.tenantId,
                        "supportTenantId" to identity.tenantId,
                        "platformUserId" to identity.platformUserId,
                        "supportSessionId" to identity.supportSessionId,
                        "actionCode" to request.permissionCode,
                        "operation" to request.operation,
                    ),
                ),
            )
        }

        return decision
    }

    private fun hasActiveSessionGrant(
        identity: RequestIdentity.Support,
        request: SupportTenantAccessRequest,
    ): Boolean {
        return jdbcTemplate.queryForObject(
            "SELECT can_support_session_access_tenant(?, ?, ?, ?)",
            Boolean::class.java,
            identity.platformUserId,
            identity.supportSessionId,
            request.tenantId,
            request.permissionCode,
        ) == true
    }
}
