package com.mwombeki.peak.shared.context

import jakarta.servlet.http.HttpServletRequest
import java.util.UUID
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

@Component
class RequestContextResolver(
    private val properties: RequestContextProperties,
) {
    fun resolve(
        request: HttpServletRequest,
        authentication: Authentication?,
    ): RequestContext {
        val correlationId = resolveCorrelationId(request)
        val idempotencyKey = resolveIdempotencyKey(request)
        val identity = resolveIdentity(request, authentication, correlationId)

        return RequestContext(
            identity = identity,
            correlationId = correlationId,
            idempotencyKey = idempotencyKey,
            httpMethod = request.method,
            requestPath = request.requestURI,
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
        val tokenIdentity = authentication.toRequestIdentity(correlationId)

        if (tokenIdentity != null && hasIdentityHeaders) {
            throw RequestContextException(
                "Identity headers cannot be combined with authenticated identity",
            )
        }

        if (hasIdentityHeaders && !properties.allowHeaderIdentity) {
            throw RequestContextException(
                "Identity headers are disabled for this runtime",
            )
        }

        return tokenIdentity
            ?: headerIdentity(request, correlationId)
            ?: RequestIdentity.Public(correlationId = correlationId)
    }

    private fun Authentication?.toRequestIdentity(
        correlationId: String,
    ): RequestIdentity? {
        if (this == null || this is AnonymousAuthenticationToken || !isAuthenticated) {
            return null
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
            ?: throw RequestContextException("JWT claim peak_identity_mode is required")

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
            publicTenantId = request.uuidHeader(PeakRequestHeaders.PUBLIC_TENANT_ID),
            publicPropertyId = request.uuidHeader(PeakRequestHeaders.PUBLIC_PROPERTY_ID),
            correlationId = correlationId,
        )

        val hasIdentity = listOf(
            raw.tenantId,
            raw.tenantUserId,
            raw.platformUserId,
            raw.supportSessionId,
            raw.supportTenantId,
            raw.publicTenantId,
            raw.publicPropertyId,
        ).any { it != null }

        return if (hasIdentity) raw.validateContext() else null
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
        val SAFE_CONTEXT_TOKEN = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}
