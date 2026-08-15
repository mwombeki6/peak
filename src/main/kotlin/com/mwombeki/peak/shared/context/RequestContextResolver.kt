package com.mwombeki.peak.shared.context

import jakarta.servlet.http.HttpServletRequest
import java.time.Instant
import java.util.Date
import java.util.UUID
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Component

@NamedInterface("context")
@Component
class RequestContextResolver(
    private val properties: RequestContextProperties,
    private val externalIdentityResolver: ExternalIdentityResolver,
) {
    fun resolve(
        request: HttpServletRequest,
        authentication: Authentication?,
    ): RequestContext {
        val correlationId = resolveCorrelationId(request)
        val idempotencyKey = resolveIdempotencyKey(request)
        val identity = resolveIdentity(request, authentication, correlationId)
        val operational = authentication as? OperationalSessionAuthentication

        return RequestContext(
            identity = identity,
            correlationId = correlationId,
            idempotencyKey = idempotencyKey,
            httpMethod = request.method,
            requestPath = request.requestURI,
            remoteAddress = request.remoteAddr
                ?.trim()
                ?.takeIf { it.matches(SAFE_IP_ADDRESS) },
            userAgent = request.getHeader("User-Agent")
                ?.filterNot(Char::isISOControl)
                ?.trim()
                ?.take(MAX_USER_AGENT_LENGTH)
                ?.takeIf(String::isNotEmpty),
            authentication = resolveAssurance(authentication),
            sessionClass = when (authentication) {
                is OperationalSessionAuthentication -> SessionClass.OPERATIONAL
                else -> SessionClass.STRONG
            },
            boundPropertyId = operational?.propertyId,
            boundOutletId = operational?.outletId,
            boundSessionId = operational?.sessionId,
        )
    }

    /**
     * Derives the achieved authentication strength from the validated token
     * only. Requests without a JWT carry no assurance, so a privileged
     * operation cannot be satisfied by header or body content.
     */
    private fun resolveAssurance(
        authentication: Authentication?,
    ): AuthenticationAssurance {
        val token = (authentication as? JwtAuthenticationToken)
            ?.takeIf { it.isAuthenticated }
            ?.token
            ?: return AuthenticationAssurance.UNAUTHENTICATED

        val acr = token.stringClaim("acr")
        val amr = token.stringListClaim("amr")
        val phishingResistant =
            acr != null && acr in properties.phishingResistantAcrValues ||
                amr.any { it in properties.phishingResistantAmrValues }
        val secondFactor = acr != null && acr in properties.mfaAcrValues

        return AuthenticationAssurance(
            level = when {
                phishingResistant -> AssuranceLevel.PHISHING_RESISTANT
                secondFactor -> AssuranceLevel.MFA
                else -> AssuranceLevel.NONE
            },
            acr = acr,
            amr = amr,
            authTime = token.authTimeClaim(),
            issuer = token.stringClaim("iss"),
            subject = token.stringClaim("sub"),
        )
    }

    private fun resolveIdentity(
        request: HttpServletRequest,
        authentication: Authentication?,
        correlationId: String,
    ): RequestIdentity {
        val hasIdentityHeaders = PeakRequestHeaders.IDENTITY_HEADERS.any { header ->
            !request.getHeader(header).isNullOrBlank()
        }
        val hasSupportSelectorHeaders = SUPPORT_SELECTOR_HEADERS.any { header ->
            !request.getHeader(header).isNullOrBlank()
        }
        val hasNonSupportIdentityHeaders = NON_SUPPORT_IDENTITY_HEADERS.any { header ->
            !request.getHeader(header).isNullOrBlank()
        }

        if (authentication.hasAuthenticatedPrincipal() && hasNonSupportIdentityHeaders) {
            throw RequestContextException(
                "Identity headers cannot be combined with authenticated identity",
            )
        }

        if (!authentication.hasAuthenticatedPrincipal() &&
            hasIdentityHeaders && !properties.allowHeaderIdentity
        ) {
            throw RequestContextException(
                "Identity headers are disabled for this runtime",
            )
        }

        val authenticatedIdentity = authentication.toRequestIdentity(correlationId)
        if (authenticatedIdentity != null && hasSupportSelectorHeaders) {
            return authenticatedIdentity.withSupportSelector(request, correlationId)
        }

        return authenticatedIdentity
            ?: headerIdentity(request, correlationId)
            ?: publicRouteIdentity(request, correlationId)
            ?: RequestIdentity.Public(correlationId = correlationId)
    }

    private fun RequestIdentity.withSupportSelector(
        request: HttpServletRequest,
        correlationId: String,
    ): RequestIdentity.Support {
        val platform = this as? RequestIdentity.Platform
            ?: throw RequestContextException(
                "Support access requires an authenticated platform identity",
            )
        val sessionId = request.uuidHeader(PeakRequestHeaders.SUPPORT_SESSION_ID)
            ?: throw RequestContextException("Support session header is required")
        val tenantId = request.uuidHeader(PeakRequestHeaders.SUPPORT_TENANT_ID)
            ?: throw RequestContextException("Support tenant header is required")
        return RequestIdentity.Support(
            platformUserId = platform.platformUserId,
            tenantId = tenantId,
            supportSessionId = sessionId,
            correlationId = correlationId,
        )
    }

    private fun Authentication?.hasAuthenticatedPrincipal(): Boolean {
        return this != null &&
            this !is AnonymousAuthenticationToken &&
            this !is HeaderIdentityAuthentication &&
            isAuthenticated
    }

    private fun Authentication?.toRequestIdentity(
        correlationId: String,
    ): RequestIdentity? {
        if (!hasAuthenticatedPrincipal()) {
            return null
        }

        if (this is OperationalSessionAuthentication) {
            return RequestIdentity.Tenant(
                tenantId = tenantId,
                tenantUserId = tenantUserId,
                correlationId = correlationId,
            )
        }

        if (this !is JwtAuthenticationToken) {
            throw RequestContextException(
                "Unsupported authenticated principal for request identity",
            )
        }

        return token.toRequestIdentity(correlationId)
    }

    private fun Jwt.toRequestIdentity(correlationId: String): RequestIdentity {
        val mode = stringClaim("peak_identity_mode")
            ?: return toExternalRequestIdentity(correlationId)

        if (!properties.allowTrustedJwtIdentityClaims) {
            throw RequestContextException(
                "Trusted JWT identity claims are disabled for this runtime",
            )
        }

        val raw = when (mode) {
            "tenant" -> RawRequestIdentity(
                tenantId = uuidClaim("tenant_id"),
                tenantUserId = uuidClaim("tenant_user_id"),
                correlationId = correlationId,
            )

            "platform" -> RawRequestIdentity(
                platformUserId = uuidClaim("platform_user_id"),
                correlationId = correlationId,
            )

            "support" -> RawRequestIdentity(
                platformUserId = uuidClaim("platform_user_id"),
                supportSessionId = uuidClaim("support_session_id"),
                supportTenantId = uuidClaim("support_tenant_id"),
                correlationId = correlationId,
            )

            else -> throw RequestContextException("Unsupported JWT identity mode: $mode")
        }

        return raw.validateContext()
    }

    private fun Jwt.toExternalRequestIdentity(correlationId: String): RequestIdentity {
        val issuer = stringClaim("iss")
            ?: throw RequestContextException("JWT claim iss is required")
        val subject = stringClaim("sub")
            ?: throw RequestContextException("JWT claim sub is required")
        val email = stringClaim("email")

        if (email != null && !booleanClaim("email_verified")) {
            throw RequestContextException("JWT email must be verified")
        }

        return when (
            val resolved = externalIdentityResolver.resolve(
                ExternalIdentityPrincipal(
                    issuer = issuer,
                    subject = subject,
                    email = email,
                ),
            )
        ) {
            is ResolvedExternalIdentity.Tenant -> RequestIdentity.Tenant(
                tenantId = resolved.tenantId,
                tenantUserId = resolved.tenantUserId,
                correlationId = correlationId,
            )

            is ResolvedExternalIdentity.Platform -> RequestIdentity.Platform(
                platformUserId = resolved.platformUserId,
                correlationId = correlationId,
            )

            null -> RequestIdentity.Public(correlationId = correlationId)
        }
    }

    private fun headerIdentity(
        request: HttpServletRequest,
        correlationId: String,
    ): RequestIdentity? {
        val raw = RawRequestIdentity(
            tenantId = request.uuidHeader(PeakRequestHeaders.TENANT_ID),
            tenantUserId = request.uuidHeader(PeakRequestHeaders.TENANT_USER_ID),
            platformUserId = request.uuidHeader(PeakRequestHeaders.PLATFORM_USER_ID),
            supportSessionId = request.uuidHeader(PeakRequestHeaders.SUPPORT_SESSION_ID),
            supportTenantId = request.uuidHeader(PeakRequestHeaders.SUPPORT_TENANT_ID),
            correlationId = correlationId,
        )

        val hasIdentity = listOf(
            raw.tenantId,
            raw.tenantUserId,
            raw.platformUserId,
            raw.supportSessionId,
            raw.supportTenantId,
        ).any { it != null }

        return if (hasIdentity) raw.validateContext() else null
    }

    private fun publicRouteIdentity(
        request: HttpServletRequest,
        correlationId: String,
    ): RequestIdentity? {
        val propertyId = PUBLIC_PROPERTY_ROUTE_PATTERN
            .matchEntire(request.requestURI)
            ?.groupValues
            ?.get(1)
            ?.toUuid("public property route")
            ?: return null

        return RequestIdentity.Public(
            propertyId = propertyId,
            correlationId = correlationId,
        )
    }

    private fun resolveCorrelationId(request: HttpServletRequest): String {
        val headerValue = request.getHeader(PeakRequestHeaders.CORRELATION_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (headerValue == null) {
            return UUID.randomUUID().toString()
        }

        if (!SAFE_CONTEXT_TOKEN.matches(headerValue)) {
            throw RequestContextException("Invalid correlation ID")
        }

        return headerValue
    }

    private fun resolveIdempotencyKey(request: HttpServletRequest): String? {
        val headerValue = request.getHeader(PeakRequestHeaders.IDEMPOTENCY_KEY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        if (!SAFE_CONTEXT_TOKEN.matches(headerValue)) {
            throw RequestContextException("Invalid idempotency key")
        }

        return headerValue
    }

    private fun Jwt.stringClaim(name: String): String? {
        return claims[name]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * `amr` is a JSON array, but some providers emit a single string. Both are
     * normalised so a phishing-resistant method is not missed by shape.
     */
    private fun Jwt.stringListClaim(name: String): List<String> {
        return when (val value = claims[name]) {
            is Collection<*> -> value.mapNotNull {
                it?.toString()?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
            }

            is String -> value.split(' ', ',')
                .map { it.trim().lowercase() }
                .filter(String::isNotEmpty)

            else -> emptyList()
        }
    }

    /**
     * `auth_time` is a NumberDate in seconds. Spring may already have parsed it
     * to an Instant; otherwise it arrives as a number. Anything unparseable is
     * treated as absent so a malformed value cannot pass a freshness gate.
     */
    private fun Jwt.authTimeClaim(): Instant? {
        return when (val value = claims["auth_time"]) {
            is Instant -> value
            is Date -> value.toInstant()
            is Number -> runCatching { Instant.ofEpochSecond(value.toLong()) }.getOrNull()
            is String -> value.trim().toLongOrNull()
                ?.let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() }

            else -> null
        }
    }

    private fun Jwt.booleanClaim(name: String): Boolean {
        return when (val value = claims[name]) {
            true -> true
            is String -> value.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun Jwt.uuidClaim(name: String): UUID? {
        return stringClaim(name)?.toUuid(name)
    }

    private fun HttpServletRequest.uuidHeader(name: String): UUID? {
        return getHeader(name)?.trim()?.takeIf { it.isNotEmpty() }?.toUuid(name)
    }

    private fun String.toUuid(name: String): UUID {
        return try {
            UUID.fromString(this)
        } catch (ex: IllegalArgumentException) {
            throw RequestContextException("Invalid UUID for $name")
        }
    }

    private fun RawRequestIdentity.validateContext(): RequestIdentity {
        return try {
            validate()
        } catch (ex: IllegalArgumentException) {
            throw RequestContextException(ex.message ?: "Invalid request identity")
        }
    }

    private companion object {
        const val MAX_USER_AGENT_LENGTH = 512
        val SUPPORT_SELECTOR_HEADERS = setOf(
            PeakRequestHeaders.SUPPORT_SESSION_ID,
            PeakRequestHeaders.SUPPORT_TENANT_ID,
        )
        val NON_SUPPORT_IDENTITY_HEADERS = setOf(
            PeakRequestHeaders.TENANT_ID,
            PeakRequestHeaders.TENANT_USER_ID,
            PeakRequestHeaders.PLATFORM_USER_ID,
        )
        val SAFE_CONTEXT_TOKEN = Regex("[A-Za-z0-9._:-]{1,128}")
        val SAFE_IP_ADDRESS = Regex("^[0-9a-fA-F:.]{2,45}$")
        val PUBLIC_PROPERTY_ROUTE_PATTERN = Regex(
            "^/api(?:/v\\d+)?/public/properties/" +
                    "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
                    "[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:/.*)?$",
        )
    }
}
