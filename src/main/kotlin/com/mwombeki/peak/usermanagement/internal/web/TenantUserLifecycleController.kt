package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.shared.exception.ApiProblemFactory

import com.mwombeki.peak.usermanagement.api.RevokeTenantUserIdentityLinkCommand
import com.mwombeki.peak.usermanagement.api.TenantUserIdentityLinkRevocationReceipt
import com.mwombeki.peak.usermanagement.api.TenantUserLifecycleAction
import com.mwombeki.peak.usermanagement.api.TenantUserLifecycleCommand
import com.mwombeki.peak.usermanagement.api.TenantUserLifecycleConflictException
import com.mwombeki.peak.usermanagement.api.TenantUserLifecycleInProgressException
import com.mwombeki.peak.usermanagement.api.TenantUserLifecycleNotFoundException
import com.mwombeki.peak.usermanagement.api.TenantUserLifecyclePort
import com.mwombeki.peak.usermanagement.api.TenantUserLifecycleReceipt
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class TenantUserLifecycleController(
    private val lifecyclePort: TenantUserLifecyclePort,
    private val apiProblemFactory: ApiProblemFactory,
) {
    @PostMapping("/tenants/{tenantId}/users/{userId}/disable")
    fun disableTenantUser(
        @PathVariable tenantId: UUID,
        @PathVariable userId: UUID,
    ): TenantUserLifecycleHttpResponse {
        return lifecyclePort.changeTenantUserLifecycle(
            TenantUserLifecycleCommand(
                tenantId = tenantId,
                userId = userId,
                action = TenantUserLifecycleAction.DISABLE,
            ),
        ).toHttpResponse()
    }

    @PostMapping("/tenants/{tenantId}/users/{userId}/reactivate")
    fun reactivateTenantUser(
        @PathVariable tenantId: UUID,
        @PathVariable userId: UUID,
    ): TenantUserLifecycleHttpResponse {
        return lifecyclePort.changeTenantUserLifecycle(
            TenantUserLifecycleCommand(
                tenantId = tenantId,
                userId = userId,
                action = TenantUserLifecycleAction.REACTIVATE,
            ),
        ).toHttpResponse()
    }

    @PostMapping("/tenants/{tenantId}/users/{userId}/lock")
    fun lockTenantUser(
        @PathVariable tenantId: UUID,
        @PathVariable userId: UUID,
    ): TenantUserLifecycleHttpResponse {
        return lifecyclePort.changeTenantUserLifecycle(
            TenantUserLifecycleCommand(
                tenantId = tenantId,
                userId = userId,
                action = TenantUserLifecycleAction.LOCK,
            ),
        ).toHttpResponse()
    }

    @PostMapping("/tenants/{tenantId}/users/{userId}/unlock")
    fun unlockTenantUser(
        @PathVariable tenantId: UUID,
        @PathVariable userId: UUID,
    ): TenantUserLifecycleHttpResponse {
        return lifecyclePort.changeTenantUserLifecycle(
            TenantUserLifecycleCommand(
                tenantId = tenantId,
                userId = userId,
                action = TenantUserLifecycleAction.UNLOCK,
            ),
        ).toHttpResponse()
    }

    @PostMapping("/tenants/{tenantId}/users/{userId}/identity-links/{identityLinkId}/revoke")
    fun revokeTenantUserIdentityLink(
        @PathVariable tenantId: UUID,
        @PathVariable userId: UUID,
        @PathVariable identityLinkId: UUID,
    ): TenantUserIdentityLinkRevocationHttpResponse {
        return lifecyclePort.revokeTenantUserIdentityLink(
            RevokeTenantUserIdentityLinkCommand(
                tenantId = tenantId,
                userId = userId,
                identityLinkId = identityLinkId,
            ),
        ).toHttpResponse()
    }

    @ExceptionHandler(TenantUserLifecycleNotFoundException::class)
    fun handleNotFound(
        ex: TenantUserLifecycleNotFoundException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.NOT_FOUND, "Tenant user lifecycle target not found", ex.publicMessage())
    }

    @ExceptionHandler(TenantUserLifecycleConflictException::class)
    fun handleConflict(
        ex: TenantUserLifecycleConflictException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Tenant user lifecycle conflict", ex.publicMessage())
    }

    @ExceptionHandler(TenantUserLifecycleInProgressException::class)
    fun handleInProgress(
        ex: TenantUserLifecycleInProgressException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Tenant user lifecycle in progress", ex.publicMessage())
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidRequest(
        ex: IllegalArgumentException,
    ): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.BAD_REQUEST, "Invalid tenant user lifecycle request", ex.publicMessage())
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        return apiProblemFactory.response(status, title, detail)
    }

    private fun RuntimeException.publicMessage(): String {
        val message = message.orEmpty()
        return if (message.startsWith("ERROR:")) {
            message.removePrefix("ERROR:").lineSequence().first().trim()
        } else {
            message
        }
    }

    private fun TenantUserLifecycleReceipt.toHttpResponse(): TenantUserLifecycleHttpResponse {
        return TenantUserLifecycleHttpResponse(
            tenantId = tenantId,
            userId = userId,
            action = action.databaseValue,
            status = status,
            isActive = isActive,
            lockedUntil = lockedUntil,
            changed = changed,
            replayed = replayed,
        )
    }

    private fun TenantUserIdentityLinkRevocationReceipt.toHttpResponse():
            TenantUserIdentityLinkRevocationHttpResponse {
        return TenantUserIdentityLinkRevocationHttpResponse(
            tenantId = tenantId,
            userId = userId,
            identityLinkId = identityLinkId,
            revokedAt = revokedAt,
            changed = changed,
            replayed = replayed,
        )
    }
}

data class TenantUserLifecycleHttpResponse(
    val tenantId: UUID,
    val userId: UUID,
    val action: String,
    val status: String,
    val isActive: Boolean,
    val lockedUntil: Instant?,
    val changed: Boolean,
    val replayed: Boolean,
)

data class TenantUserIdentityLinkRevocationHttpResponse(
    val tenantId: UUID,
    val userId: UUID,
    val identityLinkId: UUID,
    val revokedAt: Instant?,
    val changed: Boolean,
    val replayed: Boolean,
)
