package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.shared.exception.ApiProblemFactory
import com.mwombeki.peak.usermanagement.api.AccountActivationException
import com.mwombeki.peak.usermanagement.internal.application.AccountActivationService
import com.mwombeki.peak.usermanagement.internal.application.CodeDispatch
import com.mwombeki.peak.usermanagement.internal.application.CredentialAccepted
import com.mwombeki.peak.usermanagement.internal.application.InvitationDetails
import com.mwombeki.peak.usermanagement.internal.application.SetupGrant
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
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
class AccountActivationController(
    private val activation: AccountActivationService,
) {
    @GetMapping("/invitations/{invitationToken}")
    fun invitation(@PathVariable invitationToken: String): InvitationDetails =
        activation.lookupInvitation(invitationToken)

    @PostMapping("/invitations/{invitationToken}/send-code")
    fun sendInvitationCode(@PathVariable invitationToken: String): CodeDispatch =
        activation.sendInvitationCode(invitationToken)

    @PostMapping("/invitations/{invitationToken}/verify-code")
    fun verifyInvitationCode(
        @PathVariable invitationToken: String,
        @Valid @RequestBody request: VerifyCodeHttpRequest,
    ): SetupGrant = activation.verifyInvitationCode(invitationToken, request.code)

    @PostMapping("/invitations/{invitationToken}/set-credential")
    fun setInvitationCredential(
        @PathVariable invitationToken: String,
        @Valid @RequestBody request: SetCredentialHttpRequest,
    ): CredentialAccepted =
        activation.setInvitationCredential(invitationToken, request.setupGrant, request.password)

    @PostMapping("/invitations/{invitationToken}/confirm-recovery-code")
    fun confirmRecoveryCode(
        @PathVariable invitationToken: String,
        @Valid @RequestBody request: ConfirmRecoveryHttpRequest,
    ): CredentialAccepted =
        activation.confirmRecoveryCode(invitationToken, request.setupGrant, request.code)

    @PostMapping("/auth/recovery/start")
    fun startRecovery(@Valid @RequestBody request: RecoveryStartHttpRequest): CodeDispatch =
        activation.startRecovery(request.identifier)

    @PostMapping("/auth/recovery/verify-code")
    fun verifyRecoveryCode(
        @Valid @RequestBody request: RecoveryVerifyHttpRequest,
    ): SetupGrant = activation.verifyRecoveryCode(request.identifier, request.code)

    @PostMapping("/auth/recovery/set-credential")
    fun setRecoveryCredential(
        @Valid @RequestBody request: RecoverySetCredentialHttpRequest,
    ): CredentialAccepted =
        activation.setRecoveryCredential(request.identifier, request.setupGrant, request.password)
}

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [AccountActivationController::class])
class AccountActivationExceptionAdvice(
    private val problems: ApiProblemFactory,
) {
    @ExceptionHandler(AccountActivationException::class)
    fun handle(ex: AccountActivationException): ResponseEntity<ProblemDetail> =
        problems.response(
            ex.status,
            titleFor(ex.code),
            ex.message,
            mapOf("code" to ex.code),
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalid(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> =
        problems.response(
            HttpStatus.BAD_REQUEST,
            "Invalid activation request",
            ex.message,
            mapOf("code" to "unknown"),
        )

    private fun titleFor(code: String): String = when (code) {
        "invitation_not_found" -> "Invitation not found"
        "invitation_expired" -> "Invitation expired"
        "invitation_used" -> "Invitation already used"
        "code_incorrect" -> "Code incorrect"
        "code_expired" -> "Code expired"
        "too_many_attempts" -> "Too many attempts"
        "password_too_weak" -> "Password rejected"
        "credential_setup_pending" -> "Credential setup unavailable"
        else -> "Activation failed"
    }
}

data class VerifyCodeHttpRequest(
    @field:NotBlank val code: String,
)

data class SetCredentialHttpRequest(
    @field:NotBlank val setupGrant: String,
    @field:NotBlank val password: String,
)

data class ConfirmRecoveryHttpRequest(
    @field:NotBlank val setupGrant: String,
    @field:NotBlank val code: String,
)

data class RecoveryStartHttpRequest(
    @field:NotBlank val identifier: String,
)

data class RecoveryVerifyHttpRequest(
    @field:NotBlank val identifier: String,
    @field:NotBlank val code: String,
)

data class RecoverySetCredentialHttpRequest(
    @field:NotBlank val identifier: String,
    @field:NotBlank val setupGrant: String,
    @field:NotBlank val password: String,
)
