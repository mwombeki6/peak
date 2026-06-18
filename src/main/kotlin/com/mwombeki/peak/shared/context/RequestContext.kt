package com.mwombeki.peak.shared.context

import org.springframework.modulith.NamedInterface

@NamedInterface("context")
data class RequestContext(
    val identity: RequestIdentity,
    val correlationId: String,
    val idempotencyKey: String?,
    val httpMethod: String,
    val requestPath: String,
)
