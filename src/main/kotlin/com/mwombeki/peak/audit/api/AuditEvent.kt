package com.mwombeki.peak.audit.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
data class TenantAuditEvent(
    val tenantId: UUID,
    val action: String,
    val resource: AuditResource,
    val outcome: AuditOutcome = AuditOutcome.SUCCESS,
    val before: Map<String, Any?>? = null,
    val after: Map<String, Any?>? = null,
) {
    init {
        require(action.isNotBlank()) {
            "Audit action is required"
        }
    }
}

@NamedInterface("api")
data class PlatformAuditEvent(
    val action: String,
    val resource: AuditResource,
    val targetTenantId: UUID? = null,
    val outcome: AuditOutcome = AuditOutcome.SUCCESS,
    val before: Map<String, Any?>? = null,
    val after: Map<String, Any?>? = null,
) {
    init {
        require(action.isNotBlank()) {
            "Audit action is required"
        }
    }
}

@NamedInterface("api")
data class SystemPlatformAuditEvent(
    val platformUserId: UUID,
    val action: String,
    val resource: AuditResource,
    val correlationId: String,
    val targetTenantId: UUID? = null,
    val outcome: AuditOutcome = AuditOutcome.SUCCESS,
    val after: Map<String, Any?>? = null,
) {
    init {
        require(action.isNotBlank()) { "Audit action is required" }
        require(correlationId.isNotBlank()) { "Audit correlation ID is required" }
    }
}
