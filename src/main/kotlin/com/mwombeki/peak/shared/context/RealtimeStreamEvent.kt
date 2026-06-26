package com.mwombeki.peak.shared.context

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("context")
data class RealtimeStreamEvent(
    val tenantId: UUID,
    val propertyId: UUID,
    val eventType: String,
    val payload: Map<String, Any?>,
)
