package com.mwombeki.peak.audit.api

import java.util.UUID

data class AuditResource(
    val type: String,
    val id: UUID? = null,
) {
    init {
        require(type.isNotBlank()) {
            "Audit resource type is required"
        }
    }
}
