package com.mwombeki.peak.shared.context

import org.springframework.modulith.NamedInterface

@NamedInterface("context")
data class RequestContext(
    val identity: RequestIdentity,
    val correlationId: String,
    val idempotencyKey: String?,
    val httpMethod: String,
    val requestPath: String,
    val remoteAddress: String? = null,
    val userAgent: String? = null,
    /**
     * Authentication strength proven by the validated token. Never populated
     * from a request body or header, so a caller cannot declare its own
     * assurance.
     */
    val authentication: AuthenticationAssurance = AuthenticationAssurance.UNAUTHENTICATED,
)
