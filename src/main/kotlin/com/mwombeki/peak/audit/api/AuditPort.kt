package com.mwombeki.peak.audit.api

import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface AuditPort {
    fun recordTenantEvent(event: TenantAuditEvent)

    fun recordPlatformEvent(event: PlatformAuditEvent)

    fun recordPlatformEventImmediately(event: PlatformAuditEvent) {
        recordPlatformEvent(event)
    }
}
