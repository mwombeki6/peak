package com.mwombeki.peak.verification.internal.web

import com.mwombeki.peak.shared.exception.ApiProblemFactory
import com.mwombeki.peak.verification.api.ConfirmVerificationCommand
import com.mwombeki.peak.verification.api.RequestVerificationCommand
import com.mwombeki.peak.verification.api.VerificationPurpose
import com.mwombeki.peak.verification.api.VerificationThrottledException
import com.mwombeki.peak.verification.internal.VerificationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Deliberately minimal and generic — the shape a future caller (the public request-access
 * flow, tenant activation, guest phone verification) reaches for, not a place to accumulate
 * purpose-specific logic. A purpose's own module owns what it does with a verified outcome.
 */
@RestController
@RequestMapping("/api/v1/verifications")
class VerificationController(
    private val verification: VerificationService,
    private val apiProblemFactory: ApiProblemFactory,
) {
    @PostMapping
    fun request(
        @Valid @RequestBody request: RequestVerificationHttpRequest,
        servletRequest: HttpServletRequest,
    ): VerificationChallengeHttpResponse {
        val receipt = verification.request(
            RequestVerificationCommand(
                purpose = request.purpose.toPurpose(),
                destination = request.destination,
                subjectRef = request.subjectRef,
                sourceIp = servletRequest.remoteAddr,
            ),
        )
        return VerificationChallengeHttpResponse(id = receipt.id, expiresAt = receipt.expiresAt)
    }

    @PostMapping("/confirm")
    fun confirm(
        @Valid @RequestBody request: ConfirmVerificationHttpRequest,
    ): VerificationOutcomeHttpResponse {
        val outcome = verification.confirm(
            ConfirmVerificationCommand(
                purpose = request.purpose.toPurpose(),
                destination = request.destination,
                code = request.code,
            ),
        )
        // subjectRef is deliberately not in this response: a generic HTTP caller gets a yes/no,
        // never enough to impersonate the outcome without also knowing what it unlocks.
        return VerificationOutcomeHttpResponse(verified = outcome.verified)
    }

    @ExceptionHandler(VerificationThrottledException::class)
    fun handleThrottled(ex: VerificationThrottledException): ResponseEntity<ProblemDetail> =
        apiProblemFactory.response(HttpStatus.TOO_MANY_REQUESTS, "Too many verification requests", ex.message)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalid(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> =
        apiProblemFactory.response(HttpStatus.BAD_REQUEST, "Invalid verification request", ex.message)
}

private fun String.toPurpose(): VerificationPurpose =
    VerificationPurpose.entries.firstOrNull { it.name == this }
        ?: throw IllegalArgumentException("Unsupported verification purpose: $this")

data class RequestVerificationHttpRequest(
    @field:NotBlank val purpose: String,
    @field:NotBlank val destination: String,
    val subjectRef: String? = null,
)

data class VerificationChallengeHttpResponse(
    val id: UUID,
    val expiresAt: Instant,
)

data class ConfirmVerificationHttpRequest(
    @field:NotBlank val purpose: String,
    @field:NotBlank val destination: String,
    @field:NotBlank val code: String,
)

data class VerificationOutcomeHttpResponse(
    val verified: Boolean,
)
