package com.mwombeki.peak.onboarding.internal.web

import com.mwombeki.peak.onboarding.api.OnboardingSessionReceipt
import com.mwombeki.peak.onboarding.api.RequestAccessCommand
import com.mwombeki.peak.onboarding.api.RequestAccessReceipt
import com.mwombeki.peak.onboarding.api.VerifyOnboardingPhoneCommand
import com.mwombeki.peak.onboarding.internal.OnboardingApplicationService
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.tenantmanagement.api.AddVerificationDocumentCommand
import com.mwombeki.peak.tenantmanagement.api.CreateVerificationCaseCommand
import com.mwombeki.peak.tenantmanagement.api.RequestVerificationDocumentUploadCommand
import com.mwombeki.peak.tenantmanagement.api.TenantTrustControlPort
import com.mwombeki.peak.tenantmanagement.api.VerificationSubjectRef
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.time.LocalDate
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The public front door (unauthenticated) plus the applicant's own view of their KYB case
 * (ONBOARDING_ONLY session). The `/me` routes never take an applicationId path variable —
 * the bearer session is the only credential that can exist for one, so the session itself
 * is the sole source of which application a request scopes to.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
class OnboardingController(
    private val onboardingApplicationService: OnboardingApplicationService,
    private val trustPort: TenantTrustControlPort,
    private val requestContextHolder: RequestContextHolder,
) {
    @PostMapping("/request-access")
    fun requestAccess(
        @Valid @RequestBody request: RequestAccessHttpRequest,
    ): ResponseEntity<RequestAccessReceipt> = ResponseEntity.status(HttpStatus.CREATED).body(
        onboardingApplicationService.requestAccess(
            RequestAccessCommand(
                request.representativeFullName, request.representativePhone,
                request.businessName, request.countryCode,
            ),
        ),
    )

    @PostMapping("/verify-phone")
    fun verifyPhone(
        @Valid @RequestBody request: VerifyPhoneHttpRequest,
    ): OnboardingSessionReceipt = onboardingApplicationService.verifyPhone(
        VerifyOnboardingPhoneCommand(request.applicationId, request.code),
    )

    @GetMapping("/me/verification-cases")
    fun verificationCases() = trustPort.listVerificationCases(currentSubject(), platformView = false)

    @PostMapping("/me/verification-cases")
    fun createVerificationCase(
        @Valid @RequestBody request: CreateVerificationCaseHttpRequest,
    ) = ResponseEntity.status(HttpStatus.CREATED).body(
        trustPort.createVerificationCase(
            CreateVerificationCaseCommand(currentSubject(), request.caseType, request.requiredLevel),
        ),
    )

    @PostMapping("/me/verification-cases/{caseId}/documents/upload-url")
    fun requestVerificationDocumentUpload(
        @PathVariable caseId: UUID,
        @Valid @RequestBody request: OnboardingVerificationDocumentUploadHttpRequest,
    ) = trustPort.requestVerificationDocumentUpload(
        RequestVerificationDocumentUploadCommand(currentSubject(), caseId, request.mimeType),
    )

    @PostMapping("/me/verification-cases/{caseId}/documents")
    fun addVerificationDocument(
        @PathVariable caseId: UUID,
        @Valid @RequestBody request: OnboardingVerificationDocumentHttpRequest,
    ) = ResponseEntity.status(HttpStatus.CREATED).body(
        trustPort.addVerificationDocument(
            AddVerificationDocumentCommand(
                currentSubject(), caseId, request.documentType, request.documentNumberMasked,
                request.storageObjectKey, request.contentHash, request.mimeType,
                request.issuedAt, request.expiresAt,
            ),
        ),
    )

    @PostMapping("/me/verification-cases/{caseId}/submit")
    fun submitVerificationCase(@PathVariable caseId: UUID) =
        trustPort.submitVerificationCase(currentSubject(), caseId)

    private fun currentSubject(): VerificationSubjectRef.Application {
        val identity = requestContextHolder.current().identity as? RequestIdentity.OnboardingApplicant
            ?: error("Onboarding applicant identity is required")
        return VerificationSubjectRef.Application(identity.applicationId)
    }
}

data class RequestAccessHttpRequest(
    @field:NotBlank val representativeFullName: String,
    @field:NotBlank val representativePhone: String,
    val businessName: String? = null,
    @field:NotBlank val countryCode: String = "TZ",
)

data class VerifyPhoneHttpRequest(
    val applicationId: UUID,
    @field:NotBlank val code: String,
)

data class CreateVerificationCaseHttpRequest(
    @field:NotBlank val caseType: String,
    @field:NotBlank val requiredLevel: String,
)

data class OnboardingVerificationDocumentUploadHttpRequest(
    @field:NotBlank @field:Pattern(regexp = "[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+") val mimeType: String,
)

data class OnboardingVerificationDocumentHttpRequest(
    @field:NotBlank val documentType: String,
    val documentNumberMasked: String? = null,
    @field:NotBlank val storageObjectKey: String,
    @field:NotBlank @field:Pattern(regexp = "[a-fA-F0-9]{64}") val contentHash: String,
    @field:NotBlank @field:Pattern(regexp = "[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+") val mimeType: String,
    val issuedAt: LocalDate? = null,
    val expiresAt: LocalDate? = null,
)
