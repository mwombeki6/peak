package com.mwombeki.peak.audit.api

import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface AuditPort {
    fun recordTenantEvent(event: TenantAuditEvent)

    fun recordPlatformEvent(event: PlatformAuditEvent)

    fun recordPlatformEventImmediately(event: PlatformAuditEvent) {
        recordPlatformEvent(event)
    }

    /**
     * Records the bootstrap/recovery event when no HTTP request context exists.
     * This keeps the audit table behind its owning module while requiring the
     * non-web caller to provide explicit actor and correlation provenance.
     */
    fun recordSystemPlatformEvent(event: SystemPlatformAuditEvent)
}
