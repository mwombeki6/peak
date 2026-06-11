package com.mwombeki.peak.shared.context

data class RequestContext(
    val identity: RequestIdentity,
    val correlationId: String,
    val idempotencyKey: String?,
    val httpMethod: String,
    val requestPath: String,
)
