package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.exception.ApiProblemFactory
import com.mwombeki.peak.usermanagement.internal.application.DevicePairingService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class DevicePairingController(
    private val pairing: DevicePairingService,
    private val requestContextHolder: RequestContextHolder,
    private val apiProblemFactory: ApiProblemFactory,
) {
    @PostMapping("/devices/pairing-requests")
    fun requestPairing(
        @Valid @RequestBody request: PairingRequestHttpRequest,
    ): PairingRequestHttpResponse {
        val issued = pairing.requestPairing(request.publicKey)
        return PairingRequestHttpResponse(
            deviceCode = issued.deviceCode,
            pairingCode = issued.code,
            fingerprint = issued.fingerprint,
            expiresAt = issued.expiresAt,
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

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalid(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> {
        return apiProblemFactory.response(
            HttpStatus.BAD_REQUEST,
            "Invalid pairing request",
            ex.message ?: "Pairing request is invalid",
        )
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleLocked(ex: IllegalStateException): ResponseEntity<ProblemDetail> {
        return apiProblemFactory.response(
            HttpStatus.CONFLICT,
            "Pairing locked",
            ex.message ?: "This pairing can no longer be approved",
        )
    }
}

data class PairingRequestHttpRequest(
    @field:NotBlank val publicKey: String,
)

data class PairingRequestHttpResponse(
    val deviceCode: String,
    val pairingCode: String,
    val fingerprint: String,
    val expiresAt: Instant,
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
