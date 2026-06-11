package com.mwombeki.peak.audit.api

interface AuditPort {
    fun recordTenantEvent(event: TenantAuditEvent)

    fun recordPlatformEvent(event: PlatformAuditEvent)
}
