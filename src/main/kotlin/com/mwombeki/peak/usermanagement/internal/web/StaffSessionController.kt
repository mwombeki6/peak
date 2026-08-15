package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.shared.context.SessionClass
import com.mwombeki.peak.shared.exception.ApiProblemFactory
import com.mwombeki.peak.usermanagement.internal.application.DeviceSessionService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class StaffSessionController(
    private val sessions: DeviceSessionService,
    private val apiProblemFactory: ApiProblemFactory,
) {
    @PostMapping("/devices/challenges")
    fun issueChallenge(
        @Valid @RequestBody request: DeviceChallengeHttpRequest,
    ): DeviceChallengeHttpResponse {
        val challenge = sessions.issueChallenge(request.deviceCode)
            ?: throw DeviceCredentialsRejectedException()
        return DeviceChallengeHttpResponse(
            challengeId = challenge.challengeId,
            nonce = challenge.nonce,
            expiresAt = challenge.expiresAt,
        )
    }

    @PostMapping("/staff/sessions")
    fun login(
        @Valid @RequestBody request: StaffSessionHttpRequest,
    ): StaffSessionHttpResponse {
        val session = sessions.login(
            deviceCode = request.deviceCode,
            challengeId = request.challengeId,
            signature = request.signature,
            staffNumber = request.staffNumber,
            pin = request.pin,
        ) ?: throw DeviceCredentialsRejectedException()

        return StaffSessionHttpResponse(
            token = session.token,
            expiresAt = session.expiresAt,
            sessionClass = SessionClass.OPERATIONAL.name.lowercase(),
            deviceId = session.deviceId,
            propertyId = session.propertyId,
            tenantId = session.tenantId,
            userId = session.userId,
            outletId = session.outletId,
            mode = session.mode,
            terminalName = session.terminalName,
        )
    }

    @DeleteMapping("/staff/sessions/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun lockCurrent() {
        sessions.revokeCurrent()
    }

    @ExceptionHandler(DeviceCredentialsRejectedException::class)
    fun handleRejected(): ResponseEntity<ProblemDetail> {
        return apiProblemFactory.response(
            HttpStatus.UNAUTHORIZED,
            "Unauthorized",
            "Device or credentials were not accepted",
        )
    }
}

internal class DeviceCredentialsRejectedException : RuntimeException()

data class DeviceChallengeHttpRequest(
    @field:NotBlank val deviceCode: String,
)

data class DeviceChallengeHttpResponse(
    val challengeId: UUID,
    val nonce: String,
    val expiresAt: Instant,
)

data class StaffSessionHttpRequest(
    @field:NotBlank val deviceCode: String,
    @field:NotNull val challengeId: UUID,
    @field:NotBlank val signature: String,
    @field:NotBlank val staffNumber: String,
    @field:NotBlank val pin: String,
)

data class StaffSessionHttpResponse(
    val token: String,
    val expiresAt: Instant,
    val sessionClass: String,
    val deviceId: UUID,
    val propertyId: UUID,
    val tenantId: UUID,
    val userId: UUID,
    val outletId: UUID?,
    val mode: String,
    val terminalName: String,
)
