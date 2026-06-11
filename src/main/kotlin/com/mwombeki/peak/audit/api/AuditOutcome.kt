package com.mwombeki.peak.audit.api

enum class AuditOutcome(val databaseValue: String) {
    SUCCESS("success"),
    FAILURE("failure"),
    DENIED("denied"),
}
