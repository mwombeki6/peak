package com.mwombeki.peak.tenantmanagement.internal.web

import com.mwombeki.peak.tenantmanagement.api.AddVerificationDocumentCommand
import com.mwombeki.peak.tenantmanagement.api.CreateLegalHoldCommand
import com.mwombeki.peak.tenantmanagement.api.CreatePrivacyRequestCommand
import com.mwombeki.peak.tenantmanagement.api.CreateVerificationCaseCommand
import com.mwombeki.peak.tenantmanagement.api.IdentityConnectionReviewAction
import com.mwombeki.peak.tenantmanagement.api.ProcessPrivacyRequestCommand
import com.mwombeki.peak.tenantmanagement.api.PrivacyRequestAction
import com.mwombeki.peak.tenantmanagement.api.RequestVerificationDocumentUploadCommand
import com.mwombeki.peak.tenantmanagement.api.ReviewIdentityConnectionCommand
import com.mwombeki.peak.tenantmanagement.api.ReviewVerificationCaseCommand
import com.mwombeki.peak.tenantmanagement.api.TenantTrustControlPort
import com.mwombeki.peak.tenantmanagement.api.UpsertIdentityConnectionCommand
import com.mwombeki.peak.tenantmanagement.api.VerificationReviewAction
import com.mwombeki.peak.tenantmanagement.api.VerificationSubjectRef
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
class TenantTrustController(
    private val trustPort: TenantTrustControlPort,
) {
    @GetMapping("/verification-cases")
    fun verificationCases(@PathVariable tenantId: UUID) =
        trustPort.listVerificationCases(VerificationSubjectRef.Tenant(tenantId), platformView = false)

    @PostMapping("/verification-cases")
    fun createVerificationCase(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: CreateVerificationCaseHttpRequest,
    ) = ResponseEntity.status(HttpStatus.CREATED).body(
        trustPort.createVerificationCase(
            CreateVerificationCaseCommand(
                VerificationSubjectRef.Tenant(tenantId), request.caseType, request.requiredLevel,
            ),
        ),
    )

    @PostMapping("/verification-cases/{caseId}/documents/upload-url")
    fun requestVerificationDocumentUpload(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
        @Valid @RequestBody request: VerificationDocumentUploadHttpRequest,
    ) = trustPort.requestVerificationDocumentUpload(
        RequestVerificationDocumentUploadCommand(
            VerificationSubjectRef.Tenant(tenantId), caseId, request.mimeType,
        ),
    )

    @PostMapping("/verification-cases/{caseId}/documents")
    fun addVerificationDocument(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
        @Valid @RequestBody request: VerificationDocumentHttpRequest,
    ) = ResponseEntity.status(HttpStatus.CREATED).body(
        trustPort.addVerificationDocument(request.toCommand(VerificationSubjectRef.Tenant(tenantId), caseId)),
    )

    @PostMapping("/verification-cases/{caseId}/submit")
    fun submitVerificationCase(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
    ) = trustPort.submitVerificationCase(VerificationSubjectRef.Tenant(tenantId), caseId)

    @GetMapping("/privacy-requests")
    fun privacyRequests(@PathVariable tenantId: UUID) =
        trustPort.listPrivacyRequests(tenantId, platformView = false)

    @PostMapping("/privacy-requests")
    fun createPrivacyRequest(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: CreatePrivacyRequestHttpRequest,
    ) = ResponseEntity.status(HttpStatus.CREATED).body(
        trustPort.createPrivacyRequest(
            CreatePrivacyRequestCommand(
                tenantId, request.requestType, request.subjectReference, request.metadata,
            ),
        ),
    )

    @GetMapping("/identity-connections")
    fun identityConnections(@PathVariable tenantId: UUID) =
        trustPort.listIdentityConnections(tenantId, platformView = false)

    @PostMapping("/identity-connections")
    fun createIdentityConnection(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: UpsertIdentityConnectionHttpRequest,
    ) = ResponseEntity.status(HttpStatus.CREATED).body(
        trustPort.upsertIdentityConnection(request.toCommand(tenantId, null)),
    )

    @PutMapping("/identity-connections/{connectionId}")
    fun updateIdentityConnection(
        @PathVariable tenantId: UUID,
        @PathVariable connectionId: UUID,
        @Valid @RequestBody request: UpsertIdentityConnectionHttpRequest,
    ) = trustPort.upsertIdentityConnection(request.toCommand(tenantId, connectionId))
}

@RestController
@RequestMapping("/api/v1/platform/tenants/{tenantId}")
class PlatformTenantTrustController(
    private val trustPort: TenantTrustControlPort,
) {
    @GetMapping("/verification-cases")
    fun verificationCases(@PathVariable tenantId: UUID) =
        trustPort.listVerificationCases(VerificationSubjectRef.Tenant(tenantId), platformView = true)

    @PostMapping("/verification-cases/{caseId}/review")
    fun reviewVerificationCase(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
        @Valid @RequestBody request: ReviewVerificationCaseHttpRequest,
    ) = trustPort.reviewVerificationCase(
        ReviewVerificationCaseCommand(
            VerificationSubjectRef.Tenant(tenantId), caseId, request.action, request.reason,
            request.riskRating, request.expiresAt,
        ),
    )

    @GetMapping("/privacy-requests")
    fun privacyRequests(@PathVariable tenantId: UUID) =
        trustPort.listPrivacyRequests(tenantId, platformView = true)

    @PostMapping("/privacy-requests/{requestId}/process")
    fun processPrivacyRequest(
        @PathVariable tenantId: UUID,
        @PathVariable requestId: UUID,
        @Valid @RequestBody request: ProcessPrivacyRequestHttpRequest,
    ) = trustPort.processPrivacyRequest(
        ProcessPrivacyRequestCommand(tenantId, requestId, request.action, request.reason),
    )

    @PostMapping("/legal-holds")
    fun createLegalHold(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: CreateLegalHoldHttpRequest,
    ) = ResponseEntity.status(HttpStatus.CREATED).body(
        trustPort.createLegalHold(
            CreateLegalHoldCommand(
                tenantId, request.scope, request.subjectReference,
                request.reason, request.expiresAt,
            ),
        ),
    )

    @PostMapping("/legal-holds/{holdId}/release")
    fun releaseLegalHold(
        @PathVariable tenantId: UUID,
        @PathVariable holdId: UUID,
        @Valid @RequestBody request: TrustReasonHttpRequest,
    ) = trustPort.releaseLegalHold(tenantId, holdId, request.reason)

    @GetMapping("/identity-connections")
    fun identityConnections(@PathVariable tenantId: UUID) =
        trustPort.listIdentityConnections(tenantId, platformView = true)

    @PostMapping("/identity-connections/{connectionId}/review")
    fun reviewIdentityConnection(
        @PathVariable tenantId: UUID,
        @PathVariable connectionId: UUID,
        @Valid @RequestBody request: ReviewIdentityConnectionHttpRequest,
    ) = trustPort.reviewIdentityConnection(
        ReviewIdentityConnectionCommand(
            tenantId, connectionId, request.action, request.expectedVersion, request.reason,
        ),
    )
}

data class CreateVerificationCaseHttpRequest(
    @field:NotBlank val caseType: String,
    @field:NotBlank val requiredLevel: String,
)

data class VerificationDocumentUploadHttpRequest(
    @field:NotBlank @field:Pattern(regexp = "[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+") val mimeType: String,
)

data class VerificationDocumentHttpRequest(
    @field:NotBlank val documentType: String,
    val documentNumberMasked: String? = null,
    @field:NotBlank val storageObjectKey: String,
    @field:NotBlank @field:Pattern(regexp = "[a-fA-F0-9]{64}") val contentHash: String,
    @field:NotBlank @field:Pattern(regexp = "[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+") val mimeType: String,
    val issuedAt: LocalDate? = null,
    val expiresAt: LocalDate? = null,
) {
    fun toCommand(subject: VerificationSubjectRef, caseId: UUID) = AddVerificationDocumentCommand(
        subject, caseId, documentType, documentNumberMasked, storageObjectKey,
        contentHash, mimeType, issuedAt, expiresAt,
    )
}

data class ReviewVerificationCaseHttpRequest(
    @field:NotNull val action: VerificationReviewAction,
    val reason: String? = null,
    val riskRating: String? = null,
    val expiresAt: Instant? = null,
)

data class CreatePrivacyRequestHttpRequest(
    @field:NotBlank val requestType: String,
    @field:NotBlank @field:Size(max = 320) val subjectReference: String,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class ProcessPrivacyRequestHttpRequest(
    @field:NotNull val action: PrivacyRequestAction,
    val reason: String? = null,
)

data class CreateLegalHoldHttpRequest(
    @field:NotBlank val scope: String,
    @field:Size(max = 320) val subjectReference: String? = null,
    @field:NotBlank @field:Size(max = 1000) val reason: String,
    val expiresAt: Instant? = null,
)

data class UpsertIdentityConnectionHttpRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:NotBlank val protocol: String,
    val issuer: String? = null,
    val verifiedDomain: String? = null,
    val discoveryUrl: String? = null,
    val clientId: String? = null,
    val clientSecretRef: String? = null,
    val scimEnabled: Boolean = false,
    @field:Positive val expectedVersion: Long? = null,
) {
    fun toCommand(tenantId: UUID, connectionId: UUID?) = UpsertIdentityConnectionCommand(
        tenantId, connectionId, name, protocol, issuer, verifiedDomain,
        discoveryUrl, clientId, clientSecretRef, scimEnabled, expectedVersion,
    )
}

data class ReviewIdentityConnectionHttpRequest(
    @field:NotNull val action: IdentityConnectionReviewAction,
    @field:Positive val expectedVersion: Long,
    @field:NotBlank val reason: String,
)

data class TrustReasonHttpRequest(@field:NotBlank val reason: String)
