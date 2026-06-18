package com.mwombeki.peak.audit.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
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
