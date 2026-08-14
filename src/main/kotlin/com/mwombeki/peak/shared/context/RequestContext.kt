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
    /**
     * How this session was established, which is independent of [authentication].
     *
     * Defaults to [SessionClass.STRONG] because every session Peak has ever issued came from
     * Keycloak; an operational device session is the thing that opts down. A default of
     * OPERATIONAL would silently downgrade every existing construction site at once.
     *
     * Like [authentication], never populated from a request body or header — a caller must not
     * be able to declare itself strong.
     */
    val sessionClass: SessionClass = SessionClass.STRONG,
)
