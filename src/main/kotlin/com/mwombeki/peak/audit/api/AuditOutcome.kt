package com.mwombeki.peak.audit.api

import org.springframework.modulith.NamedInterface

@NamedInterface("api")
enum class AuditOutcome(val databaseValue: String) {
    SUCCESS("success"),
    FAILURE("failure"),
    DENIED("denied"),
}
