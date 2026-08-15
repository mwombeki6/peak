package com.mwombeki.peak.shared.context

import java.util.UUID
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
    /**
     * Property the operational device was paired into. Null for Keycloak sessions.
     * A PIN till must not call another property's routes with the same token.
     */
    val boundPropertyId: UUID? = null,
    /**
     * Outlet the operational device was paired into, when the manager chose one.
     * Null means the till is property-scoped. Never taken from a request body.
     */
    val boundOutletId: UUID? = null,
    /**
     * Live operational session row. Null for Keycloak sessions. Used to lock
     * or switch staff on this till without closing the drawer.
     */
    val boundSessionId: UUID? = null,
)
