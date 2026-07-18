package com.mwombeki.peak.tenantmanagement.api

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface TenantTrustControlPort {
    fun listVerificationCases(tenantId: UUID, platformView: Boolean): List<VerificationCaseSummary>
    fun createVerificationCase(command: CreateVerificationCaseCommand): VerificationCaseSummary
    fun addVerificationDocument(command: AddVerificationDocumentCommand): VerificationDocumentSummary
    fun submitVerificationCase(tenantId: UUID, caseId: UUID): VerificationCaseSummary
    fun reviewVerificationCase(command: ReviewVerificationCaseCommand): VerificationCaseSummary

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
    val tenantId: UUID,
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
)

data class CreateVerificationCaseCommand(
    val tenantId: UUID,
    val caseType: String,
    val requiredLevel: String,
)

data class AddVerificationDocumentCommand(
    val tenantId: UUID,
    val caseId: UUID,
    val documentType: String,
    val documentNumberMasked: String?,
    val storageObjectKey: String,
    val contentHash: String,
    val mimeType: String,
    val issuedAt: LocalDate?,
    val expiresAt: LocalDate?,
)

enum class VerificationReviewAction {
    START_REVIEW,
    REQUEST_INFORMATION,
    APPROVE,
    REJECT,
    SUSPEND,
}

data class ReviewVerificationCaseCommand(
    val tenantId: UUID,
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
