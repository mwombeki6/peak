package com.mwombeki.peak.tenantmanagement.api

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.modulith.NamedInterface

/**
 * A verification case belongs to exactly one of these, enforced at the database by a CHECK
 * constraint — never both, never neither. The same case/document/review machinery in
 * [TenantTrustControlService] operates on either without knowing which one it's holding.
 */
@NamedInterface("api")
sealed interface VerificationSubjectRef {
    data class Tenant(val tenantId: UUID) : VerificationSubjectRef
    data class Application(val applicationId: UUID) : VerificationSubjectRef
}

@NamedInterface("api")
interface TenantTrustControlPort {
    fun listVerificationCases(subject: VerificationSubjectRef, platformView: Boolean): List<VerificationCaseSummary>
    fun createVerificationCase(command: CreateVerificationCaseCommand): VerificationCaseSummary
    fun requestVerificationDocumentUpload(
        command: RequestVerificationDocumentUploadCommand,
    ): VerificationDocumentUploadAuthorization
    fun addVerificationDocument(command: AddVerificationDocumentCommand): VerificationDocumentSummary
    fun submitVerificationCase(subject: VerificationSubjectRef, caseId: UUID): VerificationCaseSummary
    fun reviewVerificationCase(command: ReviewVerificationCaseCommand): VerificationCaseSummary

    /**
     * Re-points an application's approved verification case (and its documents) onto a freshly
     * provisioned tenant, and carries the case's own recorded evidence onto [tenant_profiles]
     * so the new tenant doesn't need a second, redundant KYB review of evidence FBC already
     * approved. Throws if the application has no approved case.
     */
    fun carryForwardVerificationEvidence(applicationId: UUID, tenantId: UUID)

    fun listPrivacyRequests(tenantId: UUID, platformView: Boolean): List<PrivacyRequestSummary>
    fun createPrivacyRequest(command: CreatePrivacyRequestCommand): PrivacyRequestSummary
    fun processPrivacyRequest(command: ProcessPrivacyRequestCommand): PrivacyRequestSummary
    fun createLegalHold(command: CreateLegalHoldCommand): LegalHoldSummary
    fun releaseLegalHold(tenantId: UUID, holdId: UUID, reason: String): LegalHoldSummary

    fun listIdentityConnections(tenantId: UUID, platformView: Boolean): List<IdentityConnectionSummary>
    fun upsertIdentityConnection(command: UpsertIdentityConnectionCommand): IdentityConnectionSummary
    fun reviewIdentityConnection(command: ReviewIdentityConnectionCommand): IdentityConnectionSummary
}

data class VerificationCaseSummary(
    val caseId: UUID,
    val tenantId: UUID?,
    val onboardingApplicationId: UUID?,
    val caseType: String,
    val requiredLevel: String,
    val status: String,
    val riskRating: String,
    val assignedPlatformUserId: UUID?,
    val submittedAt: Instant?,
    val reviewedAt: Instant?,
    val expiresAt: Instant?,
    val rejectionReason: String?,
    val documents: List<VerificationDocumentSummary>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class VerificationDocumentSummary(
    val documentId: UUID,
    val caseId: UUID,
    val documentType: String,
    val documentNumberMasked: String?,
    val storageObjectKey: String,
    val contentHash: String,
    val mimeType: String,
    val issuedAt: LocalDate?,
    val expiresAt: LocalDate?,
    val status: String,
    val rejectionReason: String?,
    val scanStatus: String,
)

data class CreateVerificationCaseCommand(
    val subject: VerificationSubjectRef,
    val caseType: String,
    val requiredLevel: String,
)

data class AddVerificationDocumentCommand(
    val subject: VerificationSubjectRef,
    val caseId: UUID,
    val documentType: String,
    val documentNumberMasked: String?,
    val storageObjectKey: String,
    val contentHash: String,
    val mimeType: String,
    val issuedAt: LocalDate?,
    val expiresAt: LocalDate?,
)

data class RequestVerificationDocumentUploadCommand(
    val subject: VerificationSubjectRef,
    val caseId: UUID,
    val mimeType: String,
)

/** [uploadUrl] is a single-use, time-limited write authorization — never a bucket credential. */
data class VerificationDocumentUploadAuthorization(
    val objectKey: String,
    val uploadUrl: String,
    val expiresAt: Instant,
)

enum class VerificationReviewAction {
    START_REVIEW,
    REQUEST_INFORMATION,
    APPROVE,
    REJECT,
    SUSPEND,
}

data class ReviewVerificationCaseCommand(
    val subject: VerificationSubjectRef,
    val caseId: UUID,
    val action: VerificationReviewAction,
    val reason: String?,
    val riskRating: String?,
    val expiresAt: Instant?,
)

data class PrivacyRequestSummary(
    val requestId: UUID,
    val tenantId: UUID,
    val requestType: String,
    val subjectReference: String,
    val status: String,
    val assignedPlatformUserId: UUID?,
    val dueAt: Instant,
    val verifiedAt: Instant?,
    val completedAt: Instant?,
    val rejectionReason: String?,
    val exportObjectKey: String?,
    val exportContentHash: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CreatePrivacyRequestCommand(
    val tenantId: UUID,
    val requestType: String,
    val subjectReference: String,
    val metadata: Map<String, Any?> = emptyMap(),
)

enum class PrivacyRequestAction {
    ASSIGN,
    VERIFY_IDENTITY,
    START_PROCESSING,
    GENERATE_EXPORT,
    COMPLETE,
    REJECT,
    CANCEL,
}

data class ProcessPrivacyRequestCommand(
    val tenantId: UUID,
    val requestId: UUID,
    val action: PrivacyRequestAction,
    val reason: String?,
)

data class LegalHoldSummary(
    val holdId: UUID,
    val tenantId: UUID,
    val scope: String,
    val subjectReference: String?,
    val reason: String,
    val status: String,
    val startsAt: Instant,
    val expiresAt: Instant?,
    val releasedAt: Instant?,
)

data class CreateLegalHoldCommand(
    val tenantId: UUID,
    val scope: String,
    val subjectReference: String?,
    val reason: String,
    val expiresAt: Instant?,
)

data class IdentityConnectionSummary(
    val connectionId: UUID,
    val tenantId: UUID,
    val name: String,
    val protocol: String,
    val issuer: String?,
    val verifiedDomain: String?,
    val discoveryUrl: String?,
    val clientId: String?,
    val hasClientSecret: Boolean,
    val scimEnabled: Boolean,
    val status: String,
    val version: Long,
    val verifiedAt: Instant?,
)

data class UpsertIdentityConnectionCommand(
    val tenantId: UUID,
    val connectionId: UUID?,
    val name: String,
    val protocol: String,
    val issuer: String?,
    val verifiedDomain: String?,
    val discoveryUrl: String?,
    val clientId: String?,
    val clientSecretRef: String?,
    val scimEnabled: Boolean,
    val expectedVersion: Long?,
)

enum class IdentityConnectionReviewAction {
    VERIFY,
    DISABLE,
    FAIL,
}

data class ReviewIdentityConnectionCommand(
    val tenantId: UUID,
    val connectionId: UUID,
    val action: IdentityConnectionReviewAction,
    val expectedVersion: Long,
    val reason: String,
)
