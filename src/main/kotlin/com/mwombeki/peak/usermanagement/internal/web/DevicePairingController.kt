package com.mwombeki.peak.usermanagement.internal.web

import com.fasterxml.jackson.annotation.JsonInclude
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.exception.ApiProblemFactory
import com.mwombeki.peak.usermanagement.internal.application.DevicePairingService
import com.mwombeki.peak.usermanagement.internal.application.PairingCreateThrottledException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestController
@RequestMapping("/api/v1")
class DevicePairingController(
    private val pairing: DevicePairingService,
    private val requestContextHolder: RequestContextHolder,
) {
    @PostMapping("/devices/pairing-requests")
    fun requestPairing(
        @Valid @RequestBody request: PairingRequestHttpRequest,
    ): PairingRequestHttpResponse {
        val issued = pairing.requestPairing(request.publicKey)
        return PairingRequestHttpResponse(
            pairingRequestId = issued.id,
            deviceCode = issued.deviceCode,
            pairingCode = issued.code,
            fingerprint = issued.fingerprint,
            expiresAt = issued.expiresAt,
        )
    }

    @GetMapping("/devices/pairing-requests/{pairingRequestId}")
    fun pairingStatus(
        @PathVariable pairingRequestId: UUID,
    ): PairingStatusHttpResponse {
        val status = pairing.status(pairingRequestId)
            ?: throw PairingStatusNotFoundException()
        return PairingStatusHttpResponse(
            status = status.status,
            deviceCode = status.deviceCode,
            expiresAt = status.expiresAt,
            terminalName = status.terminalName,
            mode = status.mode,
        )
    }

    @PostMapping("/tenants/{tenantId}/devices/pairing-approvals")
    fun approve(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: PairingApprovalHttpRequest,
    ): PairedDeviceHttpResponse {
        val actor = requestContextHolder.current().identity as RequestIdentity.Tenant
        val paired = pairing.approve(
            tenantId = tenantId,
            pairingCode = request.pairingCode,
            approval = DevicePairingService.Approval(
                propertyId = request.propertyId,
                outletId = request.outletId,
                terminalName = request.terminalName,
                mode = request.mode,
            ),
            actorId = actor.tenantUserId,
        )
        return PairedDeviceHttpResponse(
            deviceId = paired.deviceId,
            deviceCode = paired.deviceCode,
        )
    }

    @PostMapping("/tenants/{tenantId}/devices/{deviceId}/revoke")
    fun revoke(
        @PathVariable tenantId: UUID,
        @PathVariable deviceId: UUID,
    ) {
        val actor = requestContextHolder.current().identity as RequestIdentity.Tenant
        pairing.revoke(tenantId, deviceId, actor.tenantUserId)
    }
}

/**
 * Held outside the controller, like every other module's advice.
 *
 * Controller-local `@ExceptionHandler` methods on this bean were never reached: the Spring
 * Modulith observability interceptor wraps the controller and renders the invoked method for
 * its trace span, which threw NullPointerException before the handler ran. Spring logs the
 * handler failure and rethrows the original exception, so a mistyped pairing code — the most
 * ordinary error a manager can make — left the servlet container as a 500.
 */
// A controller-specific advice must outrank GlobalExceptionHandler, whose
// @ExceptionHandler(Exception) catch-all otherwise turns every domain exception
// it does not name explicitly into a 500. Both default to LOWEST_PRECEDENCE, and
// the tie is broken arbitrarily.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [DevicePairingController::class])
class DevicePairingExceptionAdvice(private val problems: ApiProblemFactory) {
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalid(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> =
        problems.response(
            HttpStatus.BAD_REQUEST,
            "Invalid pairing request",
            ex.message ?: "Pairing request is invalid",
        )

    @ExceptionHandler(IllegalStateException::class)
    fun handleLocked(ex: IllegalStateException): ResponseEntity<ProblemDetail> =
        problems.response(
            HttpStatus.CONFLICT,
            "Pairing locked",
            ex.message ?: "This pairing can no longer be approved",
        )

    @ExceptionHandler(PairingCreateThrottledException::class)
    fun handleThrottled(ex: PairingCreateThrottledException): ResponseEntity<ProblemDetail> =
        problems.response(
            HttpStatus.TOO_MANY_REQUESTS,
            "Too many pairing requests",
            ex.message,
        )

    @ExceptionHandler(PairingStatusNotFoundException::class)
    fun handleMissing(): ResponseEntity<ProblemDetail> =
        problems.response(
            HttpStatus.NOT_FOUND,
            "Pairing request not found",
            "That pairing request is not waiting",
        )
}

internal class PairingStatusNotFoundException : RuntimeException()

data class PairingRequestHttpRequest(
    @field:NotBlank val publicKey: String,
)

data class PairingRequestHttpResponse(
    val pairingRequestId: UUID,
    val deviceCode: String,
    val pairingCode: String,
    val fingerprint: String,
    val expiresAt: Instant,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PairingStatusHttpResponse(
    val status: String,
    val deviceCode: String? = null,
    val expiresAt: Instant? = null,
    val terminalName: String? = null,
    val mode: String? = null,
)

data class PairingApprovalHttpRequest(
    @field:NotBlank val pairingCode: String,
    val propertyId: UUID,
    val outletId: UUID? = null,
    @field:NotBlank val terminalName: String,
    @field:NotBlank val mode: String,
)

data class PairedDeviceHttpResponse(
    val deviceId: UUID,
    val deviceCode: String,
)
