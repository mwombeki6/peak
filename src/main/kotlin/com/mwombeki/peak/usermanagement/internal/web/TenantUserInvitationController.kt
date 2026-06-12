package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.usermanagement.api.AcceptTenantUserInvitationCommand
import com.mwombeki.peak.usermanagement.api.InviteTenantUserCommand
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationAcceptanceReceipt
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationAcceptanceRejectedException
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationConflictException
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationException
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationInProgressException
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationPort
import com.mwombeki.peak.usermanagement.api.TenantUserInvitationReceipt
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1")
class TenantUserInvitationController(
    private val invitationPort: TenantUserInvitationPort,
) {
    @PostMapping("/tenants/{tenantId}/users/invitations")
    fun inviteTenantUser(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: InviteTenantUserHttpRequest,
    ): ResponseEntity<TenantUserInvitationHttpResponse> {
        val receipt = invitationPort.inviteTenantUser(
            InviteTenantUserCommand(
                tenantId = tenantId,
                email = request.email,
                tenantRoleId = request.tenantRoleId,
                fullName = request.fullName,
                expiresIn = Duration.ofHours(request.expiresInHours ?: DEFAULT_EXPIRY_HOURS),
                metadata = request.metadata.orEmpty(),
            ),
        )

        return ResponseEntity
            .created(URI.create("/api/v1/tenants/$tenantId/users/invitations/${receipt.invitationId}"))
            .body(receipt.toHttpResponse())
    }

    @PostMapping("/invitations/accept")
    fun acceptTenantUserInvitation(
        @Valid @RequestBody request: AcceptTenantUserInvitationHttpRequest,
        authentication: Authentication?,
    ): TenantUserInvitationAcceptanceHttpResponse {
        val oidcIdentity = authentication.requireOidcIdentity()

        return invitationPort.acceptTenantUserInvitation(
            AcceptTenantUserInvitationCommand(
                invitationToken = request.invitationToken,
                issuer = oidcIdentity.issuer,
                subject = oidcIdentity.subject,
                email = oidcIdentity.email,
                fullName = request.fullName,
            ),
        ).toHttpResponse()
    }

    @ExceptionHandler(TenantUserInvitationConflictException::class)
    fun handleConflict(
        ex: TenantUserInvitationConflictException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Invitation conflict", ex.publicMessage())
    }

    @ExceptionHandler(TenantUserInvitationInProgressException::class)
    fun handleInProgress(
        ex: TenantUserInvitationInProgressException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Invitation in progress", ex.publicMessage())
    }

    @ExceptionHandler(TenantUserInvitationAcceptanceRejectedException::class)
    fun handleAcceptanceRejected(
        ex: TenantUserInvitationAcceptanceRejectedException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.BAD_REQUEST, "Invitation acceptance rejected", ex.publicMessage())
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidRequest(
        ex: IllegalArgumentException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.BAD_REQUEST, "Invalid invitation request", ex.publicMessage())
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.title = title
        return ResponseEntity.status(status).body(problem)
    }

    private fun TenantUserInvitationException.publicMessage(): String {
        val message = message.orEmpty()
        return if (message.startsWith("ERROR:")) {
            message.removePrefix("ERROR:").lineSequence().first().trim()
        } else {
            message
        }
    }

    private fun IllegalArgumentException.publicMessage(): String {
        return message ?: "Invitation request is invalid"
    }

    private fun Authentication?.requireOidcIdentity(): OidcInvitationIdentity {
        if (this !is JwtAuthenticationToken || !isAuthenticated) {
            throw ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authenticated OIDC identity is required",
            )
        }

        return token.toOidcInvitationIdentity()
    }

    private fun Jwt.toOidcInvitationIdentity(): OidcInvitationIdentity {
        val issuer = issuer?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: stringClaim("iss")
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC issuer is required")
        val subject = subject?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC subject is required")
        val email = stringClaim("email")?.lowercase()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC email is required")

        if (!booleanClaim("email_verified")) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC email must be verified")
        }

        return OidcInvitationIdentity(
            issuer = issuer,
            subject = subject,
            email = email,
        )
    }

    private fun Jwt.stringClaim(name: String): String? {
        return claims[name]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun Jwt.booleanClaim(name: String): Boolean {
        return when (val value = claims[name]) {
            true -> true
            is String -> value.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun TenantUserInvitationReceipt.toHttpResponse(): TenantUserInvitationHttpResponse {
        return TenantUserInvitationHttpResponse(
            invitationId = invitationId,
            tenantId = tenantId,
            email = email,
            tenantRoleId = tenantRoleId,
            expiresAt = expiresAt,
            invitationToken = invitationToken,
            replayed = replayed,
        )
    }

    private fun TenantUserInvitationAcceptanceReceipt.toHttpResponse():
            TenantUserInvitationAcceptanceHttpResponse {
        return TenantUserInvitationAcceptanceHttpResponse(
            invitationId = invitationId,
            tenantId = tenantId,
            userId = userId,
            tenantRoleId = tenantRoleId,
            email = email,
            identityLinkId = identityLinkId,
            replayed = replayed,
        )
    }

    private companion object {
        const val DEFAULT_EXPIRY_HOURS = 72L
    }
}

data class InviteTenantUserHttpRequest(
    @field:NotBlank
    @field:Email
    val email: String,
    @field:NotNull
    val tenantRoleId: UUID,
    val fullName: String? = null,
    @field:Min(1)
    val expiresInHours: Long? = null,
    val metadata: Map<String, Any?>? = null,
)

data class TenantUserInvitationHttpResponse(
    val invitationId: UUID,
    val tenantId: UUID,
    val email: String,
    val tenantRoleId: UUID,
    val expiresAt: Instant,
    val invitationToken: String?,
    val replayed: Boolean,
)

data class AcceptTenantUserInvitationHttpRequest(
    @field:NotBlank
    val invitationToken: String,
    val fullName: String? = null,
)

data class TenantUserInvitationAcceptanceHttpResponse(
    val invitationId: UUID,
    val tenantId: UUID,
    val userId: UUID,
    val tenantRoleId: UUID,
    val email: String,
    val identityLinkId: UUID,
    val replayed: Boolean,
)

private data class OidcInvitationIdentity(
    val issuer: String,
    val subject: String,
    val email: String,
)
