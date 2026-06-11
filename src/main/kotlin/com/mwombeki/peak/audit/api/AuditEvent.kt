package com.mwombeki.peak.audit.api

import java.util.UUID

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
