package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.PlatformAuditEvent
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.outbound.ObjectStoragePort
import com.mwombeki.peak.shared.outbound.StoreObject
import com.mwombeki.peak.tenantmanagement.api.AddVerificationDocumentCommand
import com.mwombeki.peak.tenantmanagement.api.CreateLegalHoldCommand
import com.mwombeki.peak.tenantmanagement.api.CreatePrivacyRequestCommand
import com.mwombeki.peak.tenantmanagement.api.CreateVerificationCaseCommand
import com.mwombeki.peak.tenantmanagement.api.IdentityConnectionReviewAction
import com.mwombeki.peak.tenantmanagement.api.IdentityConnectionSummary
import com.mwombeki.peak.tenantmanagement.api.LegalHoldSummary
import com.mwombeki.peak.tenantmanagement.api.PlatformControlConflictException
import com.mwombeki.peak.tenantmanagement.api.PlatformControlInProgressException
import com.mwombeki.peak.tenantmanagement.api.PlatformControlNotFoundException
import com.mwombeki.peak.tenantmanagement.api.PrivacyRequestAction
import com.mwombeki.peak.tenantmanagement.api.PrivacyRequestSummary
import com.mwombeki.peak.tenantmanagement.api.ProcessPrivacyRequestCommand
import com.mwombeki.peak.tenantmanagement.api.ReviewIdentityConnectionCommand
import com.mwombeki.peak.tenantmanagement.api.ReviewVerificationCaseCommand
import com.mwombeki.peak.tenantmanagement.api.TenantTrustControlPort
import com.mwombeki.peak.tenantmanagement.api.UpsertIdentityConnectionCommand
import com.mwombeki.peak.tenantmanagement.api.VerificationCaseSummary
import com.mwombeki.peak.tenantmanagement.api.VerificationDocumentSummary
import com.mwombeki.peak.tenantmanagement.api.VerificationReviewAction
import com.mwombeki.peak.usermanagement.api.PlatformAccessPort
import com.mwombeki.peak.usermanagement.api.PlatformAccessRequest
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessPort
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessRequest
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class TenantTrustControlService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val tenantPermissionAccessPort: TenantPermissionAccessPort,
    private val platformAccessPort: PlatformAccessPort,
    private val requestContextHolder: RequestContextHolder,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val objectStoragePort: ObjectStoragePort,
    private val objectMapper: ObjectMapper,
) : TenantTrustControlPort {

    override fun listVerificationCases(
        tenantId: UUID,
        platformView: Boolean,
    ): List<VerificationCaseSummary> {
        return read(tenantId, platformView, "tenant.verification.view", PLATFORM_VERIFICATION) {
            jdbcTemplate.query(
                """
                $VERIFICATION_CASE_SELECT
                WHERE verification_case.tenant_id = ?
                ORDER BY verification_case.created_at DESC, verification_case.id DESC
                LIMIT 200
                """.trimIndent(),
                { rs, _ -> mapVerificationCase(rs) },
                tenantId,
            ).map { case -> case.copy(documents = verificationDocuments(tenantId, case.caseId)) }
        }
    }

    override fun createVerificationCase(
        command: CreateVerificationCaseCommand,
    ): VerificationCaseSummary {
        val type = command.caseType.normalizedVerificationCaseType()
        val level = command.requiredLevel.normalizedVerificationLevel()
        return tenantMutation(
            operation = "tenant.verification.case.create",
            tenantId = command.tenantId,
            permission = "tenant.profile.manage",
            payload = command,
            resourceType = "tenant_verification_cases",
            responseType = VerificationCaseSummary::class.java,
        ) { reservationId ->
            val open = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM tenant_verification_cases
                    WHERE tenant_id = ? AND status IN (
                        'draft', 'submitted', 'under_review', 'needs_information'
                    )
                )
                """.trimIndent(),
                Boolean::class.java,
                command.tenantId,
            ) == true
            if (open) throw PlatformControlConflictException("Tenant already has an open verification case")
            val id = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO tenant_verification_cases (
                    id, tenant_id, case_type, required_level, status, risk_rating
                ) VALUES (?, ?, ?, ?, 'draft', 'low')
                """.trimIndent(),
                id, command.tenantId, type, level,
            )
            verificationCase(command.tenantId, id).also {
                recordTenantSideEffects(
                    command.tenantId, "tenant.verification.case.created",
                    "tenant_verification_cases", id,
                    mapOf("caseId" to id, "caseType" to type, "requiredLevel" to level),
                    reservationId,
                )
            }
        }
    }

    override fun addVerificationDocument(
        command: AddVerificationDocumentCommand,
    ): VerificationDocumentSummary {
        val documentType = command.documentType.normalizedDocumentType()
        require(command.contentHash.matches(Regex("[0-9a-f]{64}"))) {
            "contentHash must be lowercase SHA-256"
        }
        require(command.mimeType in ALLOWED_VERIFICATION_MIME_TYPES) {
            "Unsupported verification document content type"
        }
        require(command.storageObjectKey.matches(SAFE_OBJECT_KEY)) {
            "Invalid verification document storage key"
        }
        if (command.issuedAt != null && command.expiresAt != null) {
            require(command.expiresAt > command.issuedAt) { "Document expiry must follow issue date" }
        }
        return tenantMutation(
            operation = "tenant.verification.document.add",
            tenantId = command.tenantId,
            permission = "tenant.profile.manage",
            payload = command,
            resourceType = "tenant_verification_documents",
            responseType = VerificationDocumentSummary::class.java,
        ) { reservationId ->
            val case = lockedVerificationCase(command.tenantId, command.caseId)
            require(case.status in setOf("draft", "needs_information")) {
                "Documents can be added only while a case is draft or needs information"
            }
            val id = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO tenant_verification_documents (
                    id, tenant_id, verification_case_id, document_type,
                    document_number_masked, storage_object_key, content_hash,
                    mime_type, issued_at, expires_at, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'submitted')
                """.trimIndent(),
                id, command.tenantId, command.caseId, documentType,
                command.documentNumberMasked?.trim()?.take(100), command.storageObjectKey,
                command.contentHash, command.mimeType,
                command.issuedAt, command.expiresAt,
            )
            verificationDocument(command.tenantId, command.caseId, id).also {
                recordTenantSideEffects(
                    command.tenantId, "tenant.verification.document.added",
                    "tenant_verification_documents", id,
                    mapOf(
                        "caseId" to command.caseId,
                        "documentId" to id,
                        "documentType" to documentType,
                        "contentHash" to command.contentHash,
                    ),
                    reservationId,
                )
            }
        }
    }

    override fun submitVerificationCase(tenantId: UUID, caseId: UUID): VerificationCaseSummary {
        return tenantMutation(
            operation = "tenant.verification.case.submit",
            tenantId = tenantId,
            permission = "tenant.profile.manage",
            payload = mapOf("tenantId" to tenantId, "caseId" to caseId),
            resourceType = "tenant_verification_cases",
            responseType = VerificationCaseSummary::class.java,
        ) { reservationId ->
            val case = lockedVerificationCase(tenantId, caseId)
            require(case.status in setOf("draft", "needs_information")) {
                "Only draft or needs-information cases can be submitted"
            }
            val documentCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM tenant_verification_documents
                WHERE tenant_id = ? AND verification_case_id = ? AND status <> 'rejected'
                """.trimIndent(),
                Int::class.java,
                tenantId, caseId,
            ) ?: 0
            require(documentCount > 0) { "Verification case requires at least one document" }
            jdbcTemplate.update(
                """
                UPDATE tenant_verification_cases SET status = 'submitted',
                    submitted_at = COALESCE(submitted_at, now()),
                    submitted_by_user_id = ?, rejection_reason = NULL
                WHERE tenant_id = ? AND id = ?
                """.trimIndent(),
                currentTenantUserId(tenantId), tenantId, caseId,
            )
            jdbcTemplate.update(
                """
                UPDATE tenant_control_states SET verification_status = 'pending', version = version + 1
                WHERE tenant_id = ?
                """.trimIndent(),
                tenantId,
            )
            verificationCase(tenantId, caseId).also {
                recordTenantSideEffects(
                    tenantId, "tenant.verification.case.submitted",
                    "tenant_verification_cases", caseId,
                    mapOf("caseId" to caseId, "documentCount" to documentCount),
                    reservationId,
                )
            }
        }
    }

    override fun reviewVerificationCase(
        command: ReviewVerificationCaseCommand,
    ): VerificationCaseSummary {
        return platformMutation(
            operation = "platform.tenant.verification.${command.action.name.lowercase()}",
            tenantId = command.tenantId,
            permission = PLATFORM_VERIFICATION,
            payload = command,
            resourceType = "tenant_verification_cases",
            responseType = VerificationCaseSummary::class.java,
        ) { reservationId ->
            val before = lockedVerificationCase(command.tenantId, command.caseId)
            val actor = currentPlatformUserId()
            val risk = command.riskRating?.normalizedRisk() ?: before.riskRating
            when (command.action) {
                VerificationReviewAction.START_REVIEW -> {
                    require(before.status == "submitted") { "Only submitted cases can start review" }
                    jdbcTemplate.update(
                        """
                        UPDATE tenant_verification_cases SET status = 'under_review',
                            risk_rating = ?, assigned_platform_user_id = ?,
                            review_started_at = COALESCE(review_started_at, now())
                        WHERE tenant_id = ? AND id = ?
                        """.trimIndent(),
                        risk, actor, command.tenantId, command.caseId,
                    )
                    updateVerificationControl(command.tenantId, "under_review")
                }
                VerificationReviewAction.REQUEST_INFORMATION -> {
                    require(before.status in setOf("submitted", "under_review")) {
                        "Information can be requested only from a submitted or reviewed case"
                    }
                    require(!command.reason.isNullOrBlank()) { "Information request reason is required" }
                    jdbcTemplate.update(
                        """
                        UPDATE tenant_verification_cases SET status = 'needs_information',
                            assigned_platform_user_id = COALESCE(assigned_platform_user_id, ?),
                            metadata = metadata || jsonb_build_object('informationRequest', ?)
                        WHERE tenant_id = ? AND id = ?
                        """.trimIndent(),
                        actor, command.reason.trim(), command.tenantId, command.caseId,
                    )
                    updateVerificationControl(command.tenantId, "needs_information")
                }
                VerificationReviewAction.APPROVE -> {
                    require(before.status == "under_review") { "Only cases under review can be approved" }
                    val rejected = jdbcTemplate.queryForObject(
                        """
                        SELECT EXISTS (SELECT 1 FROM tenant_verification_documents
                        WHERE tenant_id = ? AND verification_case_id = ? AND status = 'rejected')
                        """.trimIndent(),
                        Boolean::class.java, command.tenantId, command.caseId,
                    ) == true
                    require(!rejected) { "A case with rejected documents cannot be approved" }
                    jdbcTemplate.update(
                        """
                        UPDATE tenant_verification_documents
                        SET status = 'approved', verified_at = now(),
                            verified_by_platform_user_id = ?
                        WHERE tenant_id = ? AND verification_case_id = ?
                          AND status = 'submitted'
                        """.trimIndent(),
                        actor, command.tenantId, command.caseId,
                    )
                    jdbcTemplate.update(
                        """
                        UPDATE tenant_verification_cases SET status = 'approved',
                            risk_rating = ?, assigned_platform_user_id = ?,
                            reviewed_at = now(), approved_at = now(),
                            approved_by_platform_user_id = ?, expires_at = ?
                        WHERE tenant_id = ? AND id = ?
                        """.trimIndent(),
                        risk, actor, actor,
                        command.expiresAt?.let(Timestamp::from), command.tenantId, command.caseId,
                    )
                    jdbcTemplate.update(
                        """
                        UPDATE tenant_profiles SET verification_status = 'verified',
                            verification_level = ?, verified_at = now(),
                            verified_by_platform_user_id = ?, verification_expires_at = ?,
                            rejection_reason = NULL, updated_at = now()
                        WHERE tenant_id = ?
                        """.trimIndent(),
                        before.requiredLevel, actor,
                        command.expiresAt?.let(Timestamp::from), command.tenantId,
                    )
                    updateVerificationControl(command.tenantId, "verified")
                    completeOnboardingStep(command.tenantId, "verify_business")
                }
                VerificationReviewAction.REJECT -> {
                    require(before.status in setOf("submitted", "under_review")) {
                        "Only submitted or reviewed cases can be rejected"
                    }
                    require(!command.reason.isNullOrBlank()) { "Rejection reason is required" }
                    jdbcTemplate.update(
                        """
                        UPDATE tenant_verification_cases SET status = 'rejected',
                            reviewed_at = now(), rejected_at = now(),
                            rejected_by_platform_user_id = ?, rejection_reason = ?
                        WHERE tenant_id = ? AND id = ?
                        """.trimIndent(),
                        actor, command.reason.trim(), command.tenantId, command.caseId,
                    )
                    jdbcTemplate.update(
                        """
                        UPDATE tenant_profiles SET verification_status = 'rejected',
                            verified_at = NULL, verified_by_platform_user_id = NULL,
                            rejection_reason = ?, updated_at = now()
                        WHERE tenant_id = ?
                        """.trimIndent(),
                        command.reason.trim(), command.tenantId,
                    )
                    updateVerificationControl(command.tenantId, "rejected")
                }
                VerificationReviewAction.SUSPEND -> {
                    require(!command.reason.isNullOrBlank()) { "Suspension reason is required" }
                    jdbcTemplate.update(
                        """
                        UPDATE tenant_verification_cases SET status = 'suspended',
                            reviewed_at = now(), rejection_reason = ?
                        WHERE tenant_id = ? AND id = ?
                        """.trimIndent(),
                        command.reason.trim(), command.tenantId, command.caseId,
                    )
                    jdbcTemplate.update(
                        """
                        UPDATE tenant_profiles SET verification_status = 'suspended',
                            rejection_reason = ?, updated_at = now() WHERE tenant_id = ?
                        """.trimIndent(),
                        command.reason.trim(), command.tenantId,
                    )
                    updateVerificationControl(command.tenantId, "suspended")
                }
            }
            verificationCase(command.tenantId, command.caseId).also {
                recordPlatformSideEffects(
                    command.tenantId,
                    "platform.tenants.verification.${command.action.name.lowercase()}",
                    "tenant_verification_cases", command.caseId,
                    mapOf(
                        "caseId" to command.caseId,
                        "action" to command.action.name,
                        "riskRating" to risk,
                        "reason" to command.reason,
                    ),
                    reservationId,
                )
            }
        }
    }

    override fun listPrivacyRequests(
        tenantId: UUID,
        platformView: Boolean,
    ): List<PrivacyRequestSummary> {
        return read(tenantId, platformView, "tenant.privacy.manage", "platform.privacy.view") {
            jdbcTemplate.query(
                "$PRIVACY_SELECT WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 200",
                { rs, _ -> mapPrivacyRequest(rs) },
                tenantId,
            )
        }
    }

    override fun createPrivacyRequest(command: CreatePrivacyRequestCommand): PrivacyRequestSummary {
        val type = command.requestType.normalizedPrivacyType()
        val subject = command.subjectReference.normalizedSubjectReference()
        require(command.metadata.size <= 50) { "Privacy request metadata supports at most 50 fields" }
        return tenantMutation(
            operation = "tenant.privacy.request.create",
            tenantId = command.tenantId,
            permission = "tenant.privacy.manage",
            payload = command,
            resourceType = "tenant_privacy_requests",
            responseType = PrivacyRequestSummary::class.java,
        ) { reservationId ->
            val id = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO tenant_privacy_requests (
                    id, tenant_id, request_type, subject_reference, status,
                    requested_by_user_id, due_at, metadata
                ) VALUES (?, ?, ?, ?, 'submitted', ?, now() + interval '30 days', ?::jsonb)
                """.trimIndent(),
                id, command.tenantId, type, subject, currentTenantUserId(command.tenantId),
                objectMapper.writeValueAsString(command.metadata),
            )
            privacyRequest(command.tenantId, id).also {
                recordTenantSideEffects(
                    command.tenantId, "tenant.privacy.request.created",
                    "tenant_privacy_requests", id,
                    mapOf("requestId" to id, "requestType" to type), reservationId,
                )
            }
        }
    }

    override fun processPrivacyRequest(command: ProcessPrivacyRequestCommand): PrivacyRequestSummary {
        return platformMutation(
            operation = "platform.tenant.privacy.${command.action.name.lowercase()}",
            tenantId = command.tenantId,
            permission = "platform.privacy.manage",
            payload = command,
            resourceType = "tenant_privacy_requests",
            responseType = PrivacyRequestSummary::class.java,
        ) { reservationId ->
            val before = lockedPrivacyRequest(command.tenantId, command.requestId)
            val actor = currentPlatformUserId()
            when (command.action) {
                PrivacyRequestAction.ASSIGN -> jdbcTemplate.update(
                    """
                    UPDATE tenant_privacy_requests SET assigned_platform_user_id = ?,
                        status = 'identity_verification' WHERE tenant_id = ? AND id = ?
                    """.trimIndent(), actor, command.tenantId, command.requestId,
                )
                PrivacyRequestAction.VERIFY_IDENTITY -> {
                    require(before.status == "identity_verification") {
                        "Privacy request must be assigned before identity verification"
                    }
                    jdbcTemplate.update(
                        """
                        UPDATE tenant_privacy_requests SET verified_at = now(),
                            status = 'in_progress' WHERE tenant_id = ? AND id = ?
                        """.trimIndent(), command.tenantId, command.requestId,
                    )
                }
                PrivacyRequestAction.START_PROCESSING -> {
                    require(before.verifiedAt != null) { "Privacy subject identity must be verified" }
                    jdbcTemplate.update(
                        "UPDATE tenant_privacy_requests SET status = 'in_progress' WHERE tenant_id = ? AND id = ?",
                        command.tenantId, command.requestId,
                    )
                }
                PrivacyRequestAction.GENERATE_EXPORT -> generatePrivacyExport(before)
                PrivacyRequestAction.COMPLETE -> completePrivacyRequest(before)
                PrivacyRequestAction.REJECT -> {
                    require(!command.reason.isNullOrBlank()) { "Privacy rejection reason is required" }
                    jdbcTemplate.update(
                        """
                        UPDATE tenant_privacy_requests SET status = 'rejected',
                            rejection_reason = ?, completed_at = now()
                        WHERE tenant_id = ? AND id = ?
                        """.trimIndent(), command.reason.trim(), command.tenantId, command.requestId,
                    )
                }
                PrivacyRequestAction.CANCEL -> jdbcTemplate.update(
                    """
                    UPDATE tenant_privacy_requests SET status = 'cancelled', completed_at = now()
                    WHERE tenant_id = ? AND id = ?
                    """.trimIndent(), command.tenantId, command.requestId,
                )
            }
            privacyRequest(command.tenantId, command.requestId).also {
                recordPlatformSideEffects(
                    command.tenantId,
                    "platform.tenants.privacy.${command.action.name.lowercase()}",
                    "tenant_privacy_requests", command.requestId,
                    mapOf(
                        "requestId" to command.requestId,
                        "requestType" to before.requestType,
                        "action" to command.action.name,
                        "reason" to command.reason,
                    ), reservationId,
                )
            }
        }
    }

    override fun createLegalHold(command: CreateLegalHoldCommand): LegalHoldSummary {
        val scope = command.scope.normalizedLegalHoldScope()
        require(command.reason.isNotBlank()) { "Legal hold reason is required" }
        command.expiresAt?.let { require(it > Instant.now()) { "Legal hold expiry must be in the future" } }
        return platformMutation(
            operation = "platform.tenant.privacy.legal_hold.create",
            tenantId = command.tenantId,
            permission = "platform.privacy.manage",
            payload = command,
            resourceType = "tenant_legal_holds",
            responseType = LegalHoldSummary::class.java,
        ) { reservationId ->
            val id = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO tenant_legal_holds (
                    id, tenant_id, hold_scope, subject_reference, reason,
                    expires_at, created_by_platform_user_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, command.tenantId, scope,
                command.subjectReference?.normalizedSubjectReference(), command.reason.trim(),
                command.expiresAt?.let(Timestamp::from), currentPlatformUserId(),
            )
            legalHold(command.tenantId, id).also {
                recordPlatformSideEffects(
                    command.tenantId, "platform.tenants.privacy.legal_hold.created",
                    "tenant_legal_holds", id,
                    mapOf("holdId" to id, "scope" to scope, "reason" to command.reason.trim()),
                    reservationId,
                )
            }
        }
    }

    override fun releaseLegalHold(tenantId: UUID, holdId: UUID, reason: String): LegalHoldSummary {
        require(reason.isNotBlank()) { "Legal hold release reason is required" }
        return platformMutation(
            operation = "platform.tenant.privacy.legal_hold.release",
            tenantId = tenantId,
            permission = "platform.privacy.manage",
            payload = mapOf("tenantId" to tenantId, "holdId" to holdId, "reason" to reason),
            resourceType = "tenant_legal_holds",
            responseType = LegalHoldSummary::class.java,
        ) { reservationId ->
            legalHold(tenantId, holdId)
            jdbcTemplate.update(
                """
                UPDATE tenant_legal_holds SET status = 'released', released_at = now(),
                    released_by_platform_user_id = ?
                WHERE tenant_id = ? AND id = ? AND status = 'active'
                """.trimIndent(), currentPlatformUserId(), tenantId, holdId,
            )
            legalHold(tenantId, holdId).also {
                recordPlatformSideEffects(
                    tenantId, "platform.tenants.privacy.legal_hold.released",
                    "tenant_legal_holds", holdId,
                    mapOf("holdId" to holdId, "reason" to reason.trim()), reservationId,
                )
            }
        }
    }

    override fun listIdentityConnections(
        tenantId: UUID,
        platformView: Boolean,
    ): List<IdentityConnectionSummary> {
        return read(tenantId, platformView, "tenant.identity.manage", "platform.identity.manage") {
            jdbcTemplate.query(
                "$IDENTITY_SELECT WHERE tenant_id = ? ORDER BY connection_name, id",
                { rs, _ -> mapIdentityConnection(rs) }, tenantId,
            )
        }
    }

    override fun upsertIdentityConnection(
        command: UpsertIdentityConnectionCommand,
    ): IdentityConnectionSummary {
        val protocol = command.protocol.normalizedIdentityProtocol()
        require(!command.scimEnabled || protocol in setOf("oidc", "saml", "scim")) {
            "SCIM requires an OIDC, SAML, or SCIM connection"
        }
        command.clientSecretRef?.let {
            require(it.startsWith("secret://")) { "Identity client secret must use secret:// reference" }
        }
        return tenantMutation(
            operation = "tenant.identity.connection.upsert",
            tenantId = command.tenantId,
            permission = "tenant.identity.manage",
            payload = command,
            resourceType = "tenant_identity_connections",
            responseType = IdentityConnectionSummary::class.java,
        ) { reservationId ->
            val id = command.connectionId ?: UUID.randomUUID()
            if (command.connectionId == null) {
                try {
                    jdbcTemplate.update(
                        """
                        INSERT INTO tenant_identity_connections (
                            id, tenant_id, connection_name, protocol, issuer,
                            verified_domain, discovery_url, client_id, client_secret_ref,
                            scim_enabled, status, created_by_user_id
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending_verification', ?)
                        """.trimIndent(),
                        id, command.tenantId, command.name.normalizedConnectionName(), protocol,
                        command.issuer?.trim(), command.verifiedDomain?.normalizedDomain(),
                        command.discoveryUrl?.trim(), command.clientId?.trim(),
                        command.clientSecretRef, command.scimEnabled,
                        currentTenantUserId(command.tenantId),
                    )
                } catch (ex: DuplicateKeyException) {
                    throw PlatformControlConflictException("Identity connection name or domain is already in use")
                }
            } else {
                val current = identityConnection(command.tenantId, id, lock = true)
                if (command.expectedVersion == null || current.version != command.expectedVersion) {
                    throw PlatformControlConflictException("Identity connection version does not match")
                }
                jdbcTemplate.update(
                    """
                    UPDATE tenant_identity_connections
                    SET connection_name = ?, protocol = ?, issuer = ?, verified_domain = ?,
                        discovery_url = ?, client_id = ?,
                        client_secret_ref = COALESCE(?, client_secret_ref),
                        scim_enabled = ?, status = 'pending_verification',
                        verified_at = NULL, verified_by_platform_user_id = NULL,
                        version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ?
                    """.trimIndent(),
                    command.name.normalizedConnectionName(), protocol, command.issuer?.trim(),
                    command.verifiedDomain?.normalizedDomain(), command.discoveryUrl?.trim(),
                    command.clientId?.trim(), command.clientSecretRef, command.scimEnabled,
                    command.tenantId, id, command.expectedVersion,
                )
            }
            identityConnection(command.tenantId, id).also {
                recordTenantSideEffects(
                    command.tenantId, "tenant.identity.connection.configured",
                    "tenant_identity_connections", id,
                    mapOf(
                        "connectionId" to id,
                        "protocol" to protocol,
                        "domain" to command.verifiedDomain?.normalizedDomain(),
                        "scimEnabled" to command.scimEnabled,
                    ), reservationId,
                )
            }
        }
    }

    override fun reviewIdentityConnection(
        command: ReviewIdentityConnectionCommand,
    ): IdentityConnectionSummary {
        require(command.reason.isNotBlank()) { "Identity connection review reason is required" }
        return platformMutation(
            operation = "platform.tenant.identity.${command.action.name.lowercase()}",
            tenantId = command.tenantId,
            permission = "platform.identity.manage",
            payload = command,
            resourceType = "tenant_identity_connections",
            responseType = IdentityConnectionSummary::class.java,
        ) { reservationId ->
            val current = identityConnection(command.tenantId, command.connectionId, lock = true)
            if (current.version != command.expectedVersion) {
                throw PlatformControlConflictException("Identity connection changed during review")
            }
            val status = when (command.action) {
                IdentityConnectionReviewAction.VERIFY -> "active"
                IdentityConnectionReviewAction.DISABLE -> "disabled"
                IdentityConnectionReviewAction.FAIL -> "failed"
            }
            jdbcTemplate.update(
                """
                UPDATE tenant_identity_connections SET status = ?, version = version + 1,
                    verified_by_platform_user_id = CASE WHEN ? = 'active' THEN ? ELSE NULL END,
                    verified_at = CASE WHEN ? = 'active' THEN now() ELSE NULL END
                WHERE tenant_id = ? AND id = ? AND version = ?
                """.trimIndent(),
                status, status, currentPlatformUserId(), status,
                command.tenantId, command.connectionId, command.expectedVersion,
            )
            identityConnection(command.tenantId, command.connectionId).also {
                recordPlatformSideEffects(
                    command.tenantId,
                    "platform.tenants.identity.${command.action.name.lowercase()}",
                    "tenant_identity_connections", command.connectionId,
                    mapOf(
                        "connectionId" to command.connectionId,
                        "status" to status,
                        "reason" to command.reason.trim(),
                    ), reservationId,
                )
            }
        }
    }

    private fun generatePrivacyExport(request: PrivacyRequestSummary) {
        require(request.verifiedAt != null) { "Privacy subject identity must be verified" }
        require(request.requestType in setOf("access", "portability")) {
            "Exports are available only for access and portability requests"
        }
        val payload = linkedMapOf<String, Any?>(
            "requestId" to request.requestId,
            "tenantId" to request.tenantId,
            "requestType" to request.requestType,
            "subjectReference" to request.subjectReference,
            "generatedAt" to Instant.now(),
            "tenantProfile" to jdbcTemplate.queryForMap(
                """
                SELECT legal_name, trading_name, business_email, business_phone,
                       registration_country_code, verification_status
                FROM tenant_profiles WHERE tenant_id = ?
                """.trimIndent(),
                request.tenantId,
            ),
            "tenantUsers" to jdbcTemplate.queryForList(
                """
                SELECT id, full_name, email, status, created_at, updated_at
                FROM users WHERE tenant_id = ? AND deleted_at IS NULL
                  AND (id::text = ? OR lower(email) = lower(?))
                """.trimIndent(),
                request.tenantId, request.subjectReference, request.subjectReference,
            ),
            "guests" to jdbcTemplate.queryForList(
                """
                SELECT id, first_name, last_name, email, phone, created_at, updated_at
                FROM guests WHERE tenant_id = ? AND deleted_at IS NULL
                  AND (id::text = ? OR lower(COALESCE(email, '')) = lower(?))
                """.trimIndent(),
                request.tenantId, request.subjectReference, request.subjectReference,
            ),
        )
        val bytes = objectMapper.writeValueAsBytes(payload)
        require(bytes.size <= 20 * 1024 * 1024) { "Privacy export exceeds the 20 MiB limit" }
        val hash = bytes.sha256()
        val key = "privacy/${request.tenantId}/${request.requestId}/$hash.json"
        objectStoragePort.putIfAbsent(
            StoreObject(key, bytes, "application/json", hash),
        )
        jdbcTemplate.update(
            """
            UPDATE tenant_privacy_requests SET status = 'ready',
                export_object_key = ?, export_content_hash = ?
            WHERE tenant_id = ? AND id = ?
            """.trimIndent(),
            key, hash, request.tenantId, request.requestId,
        )
    }

    private fun completePrivacyRequest(request: PrivacyRequestSummary) {
        require(request.status in setOf("in_progress", "ready", "blocked_by_legal_hold")) {
            "Privacy request is not ready for completion"
        }
        val activeHold = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM tenant_legal_holds
                WHERE tenant_id = ? AND status = 'active'
                  AND (expires_at IS NULL OR expires_at > now())
                  AND (subject_reference IS NULL OR subject_reference = ?)
            )
            """.trimIndent(),
            Boolean::class.java, request.tenantId, request.subjectReference,
        ) == true
        if (activeHold && request.requestType in setOf("erasure", "restriction")) {
            jdbcTemplate.update(
                """
                UPDATE tenant_privacy_requests SET status = 'blocked_by_legal_hold'
                WHERE tenant_id = ? AND id = ?
                """.trimIndent(), request.tenantId, request.requestId,
            )
            return
        }
        if (request.requestType in setOf("access", "portability")) {
            require(request.exportObjectKey != null && request.exportContentHash != null) {
                "Privacy export must be generated before completion"
            }
        }
        jdbcTemplate.update(
            """
            UPDATE tenant_privacy_requests SET status = 'completed', completed_at = now()
            WHERE tenant_id = ? AND id = ?
            """.trimIndent(), request.tenantId, request.requestId,
        )
    }

    private fun updateVerificationControl(tenantId: UUID, status: String) {
        jdbcTemplate.update(
            """
            UPDATE tenant_control_states SET verification_status = ?, version = version + 1,
                updated_by_platform_user_id = ? WHERE tenant_id = ?
            """.trimIndent(), status, currentPlatformUserId(), tenantId,
        )
    }

    private fun completeOnboardingStep(tenantId: UUID, stepKey: String) {
        val workflowId = jdbcTemplate.query(
            """
            SELECT id FROM tenant_workflows WHERE tenant_id = ? AND workflow_type = 'onboarding'
            ORDER BY created_at DESC LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) }, tenantId,
        ).singleOrNull() ?: return
        jdbcTemplate.update(
            """
            UPDATE tenant_workflow_steps SET status = 'succeeded',
                attempt_count = GREATEST(attempt_count, 1),
                started_at = COALESCE(started_at, now()), completed_at = now()
            WHERE tenant_id = ? AND workflow_id = ? AND step_key = ? AND status <> 'succeeded'
            """.trimIndent(), tenantId, workflowId, stepKey,
        )
        jdbcTemplate.update(
            """
            UPDATE tenant_workflows SET completed_steps = (
                SELECT count(*) FROM tenant_workflow_steps
                WHERE workflow_id = ? AND status = 'succeeded'
            ), current_step = ? WHERE tenant_id = ? AND id = ?
            """.trimIndent(), workflowId, stepKey, tenantId, workflowId,
        )
    }

    private fun <T> read(
        tenantId: UUID,
        platformView: Boolean,
        tenantPermission: String,
        platformPermission: String,
        block: () -> T,
    ): T = requireNotNull(
        transactionTemplate.execute {
            if (platformView) {
                platformAccessPort.requireAuthorized(
                    PlatformAccessRequest(tenantId, platformPermission, "platform.tenant.trust.view"),
                )
            } else {
                tenantPermissionAccessPort.requireAuthorized(
                    TenantPermissionAccessRequest(tenantId, tenantPermission),
                )
            }
            block()
        },
    )

    private fun <T : Any> tenantMutation(
        operation: String,
        tenantId: UUID,
        permission: String,
        payload: Any,
        resourceType: String,
        responseType: Class<T>,
        block: (UUID) -> T,
    ): T = mutate(
        operation, tenantId, payload, resourceType, responseType,
        authorize = {
            tenantPermissionAccessPort.requireAuthorized(
                TenantPermissionAccessRequest(tenantId, permission),
            )
        },
        block = block,
    )

    private fun <T : Any> platformMutation(
        operation: String,
        tenantId: UUID,
        permission: String,
        payload: Any,
        resourceType: String,
        responseType: Class<T>,
        block: (UUID) -> T,
    ): T = mutate(
        operation, tenantId, payload, resourceType, responseType,
        authorize = {
            platformAccessPort.requireAuthorized(
                PlatformAccessRequest(tenantId, permission, operation),
            )
        },
        block = block,
    )

    private fun <T : Any> mutate(
        operation: String,
        tenantId: UUID,
        payload: Any,
        resourceType: String,
        responseType: Class<T>,
        authorize: () -> Unit,
        block: (UUID) -> T,
    ): T = requireNotNull(
        transactionTemplate.execute {
            authorize()
            when (
                val reservation = idempotencyPort.reserve(
                    IdempotencyCommand(operation, payload, resourceType),
                )
            ) {
                is IdempotencyReservation.Started -> {
                    val result = block(reservation.recordId)
                    idempotencyPort.markSucceeded(
                        reservation.recordId, 200, result, resourceId(result) ?: tenantId,
                    )
                    result
                }
                is IdempotencyReservation.Replay -> {
                    if (reservation.responseBody.isNullOrBlank()) {
                        throw PlatformControlConflictException("Trust control replay response is missing")
                    }
                    objectMapper.readValue(reservation.responseBody, responseType)
                }
                is IdempotencyReservation.InProgress -> throw PlatformControlInProgressException(
                    "Trust control command is already in progress",
                )
                is IdempotencyReservation.Conflict -> throw PlatformControlConflictException(
                    "Idempotency key was used for a different trust control command",
                )
            }
        },
    )

    private fun resourceId(result: Any): UUID? = when (result) {
        is VerificationCaseSummary -> result.caseId
        is VerificationDocumentSummary -> result.documentId
        is PrivacyRequestSummary -> result.requestId
        is LegalHoldSummary -> result.holdId
        is IdentityConnectionSummary -> result.connectionId
        else -> null
    }

    private fun recordTenantSideEffects(
        tenantId: UUID,
        action: String,
        type: String,
        id: UUID,
        payload: Map<String, Any?>,
        reservationId: UUID,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(tenantId, action, AuditResource(type, id), after = payload),
        )
        enqueue(tenantId, action, type, id, payload, reservationId)
    }

    private fun recordPlatformSideEffects(
        tenantId: UUID,
        action: String,
        type: String,
        id: UUID,
        payload: Map<String, Any?>,
        reservationId: UUID,
    ) {
        auditPort.recordPlatformEvent(
            PlatformAuditEvent(
                action = action,
                resource = AuditResource(type, id),
                targetTenantId = tenantId,
                after = payload,
            ),
        )
        enqueue(tenantId, action, type, id, payload, reservationId)
    }

    private fun enqueue(
        tenantId: UUID,
        action: String,
        type: String,
        id: UUID,
        payload: Map<String, Any?>,
        reservationId: UUID,
    ) {
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = type, aggregateId = id,
                tenantId = when (requestContextHolder.current().identity) {
                    is RequestIdentity.Tenant -> tenantId
                    else -> null
                },
                eventType = action, destination = OutboxDestination.PLATFORM,
                payload = payload, idempotencyKeyId = reservationId, priority = 3,
            ),
        )
    }

    private fun currentTenantUserId(tenantId: UUID): UUID = when (
        val identity = requestContextHolder.current().identity
    ) {
        is RequestIdentity.Tenant -> {
            require(identity.tenantId == tenantId) { "Tenant identity does not match target" }
            identity.tenantUserId
        }
        else -> throw IllegalStateException("Tenant identity is required")
    }

    private fun currentPlatformUserId(): UUID = when (
        val identity = requestContextHolder.current().identity
    ) {
        is RequestIdentity.Platform -> identity.platformUserId
        is RequestIdentity.Support -> identity.platformUserId
        else -> throw IllegalStateException("Platform identity is required")
    }

    private fun verificationCase(tenantId: UUID, caseId: UUID): VerificationCaseSummary {
        return jdbcTemplate.query(
            "$VERIFICATION_CASE_SELECT WHERE verification_case.tenant_id = ? AND verification_case.id = ?",
            { rs, _ -> mapVerificationCase(rs) }, tenantId, caseId,
        ).singleOrNull()?.let { it.copy(documents = verificationDocuments(tenantId, caseId)) }
            ?: throw PlatformControlNotFoundException("Verification case was not found")
    }

    private fun lockedVerificationCase(tenantId: UUID, caseId: UUID): VerificationCaseSummary {
        jdbcTemplate.queryForList(
            "SELECT id FROM tenant_verification_cases WHERE tenant_id = ? AND id = ? FOR UPDATE",
            tenantId, caseId,
        ).singleOrNull() ?: throw PlatformControlNotFoundException("Verification case was not found")
        return verificationCase(tenantId, caseId)
    }

    private fun mapVerificationCase(rs: ResultSet): VerificationCaseSummary =
        VerificationCaseSummary(
            caseId = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            caseType = rs.getString("case_type"),
            requiredLevel = rs.getString("required_level"),
            status = rs.getString("status"),
            riskRating = rs.getString("risk_rating"),
            assignedPlatformUserId = rs.getObject("assigned_platform_user_id", UUID::class.java),
            submittedAt = rs.getTimestamp("submitted_at")?.toInstant(),
            reviewedAt = rs.getTimestamp("reviewed_at")?.toInstant(),
            expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
            rejectionReason = rs.getString("rejection_reason"),
            documents = emptyList(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )

    private fun verificationDocuments(tenantId: UUID, caseId: UUID): List<VerificationDocumentSummary> =
        jdbcTemplate.query(
            """
            SELECT id, verification_case_id, document_type, document_number_masked,
                   storage_object_key, content_hash, mime_type, issued_at, expires_at,
                   status, rejection_reason
            FROM tenant_verification_documents
            WHERE tenant_id = ? AND verification_case_id = ?
            ORDER BY created_at, id
            """.trimIndent(),
            { rs, _ -> mapVerificationDocument(rs) }, tenantId, caseId,
        )

    private fun verificationDocument(
        tenantId: UUID,
        caseId: UUID,
        documentId: UUID,
    ): VerificationDocumentSummary = jdbcTemplate.query(
        """
        SELECT id, verification_case_id, document_type, document_number_masked,
               storage_object_key, content_hash, mime_type, issued_at, expires_at,
               status, rejection_reason
        FROM tenant_verification_documents
        WHERE tenant_id = ? AND verification_case_id = ? AND id = ?
        """.trimIndent(),
        { rs, _ -> mapVerificationDocument(rs) }, tenantId, caseId, documentId,
    ).singleOrNull() ?: throw PlatformControlNotFoundException("Verification document was not found")

    private fun mapVerificationDocument(rs: ResultSet) = VerificationDocumentSummary(
        documentId = rs.getObject("id", UUID::class.java),
        caseId = rs.getObject("verification_case_id", UUID::class.java),
        documentType = rs.getString("document_type"),
        documentNumberMasked = rs.getString("document_number_masked"),
        storageObjectKey = rs.getString("storage_object_key"),
        contentHash = rs.getString("content_hash"),
        mimeType = rs.getString("mime_type"),
        issuedAt = rs.getObject("issued_at", LocalDate::class.java),
        expiresAt = rs.getObject("expires_at", LocalDate::class.java),
        status = rs.getString("status"),
        rejectionReason = rs.getString("rejection_reason"),
    )

    private fun privacyRequest(tenantId: UUID, requestId: UUID): PrivacyRequestSummary =
        jdbcTemplate.query(
            "$PRIVACY_SELECT WHERE tenant_id = ? AND id = ?",
            { rs, _ -> mapPrivacyRequest(rs) }, tenantId, requestId,
        ).singleOrNull() ?: throw PlatformControlNotFoundException("Privacy request was not found")

    private fun lockedPrivacyRequest(tenantId: UUID, requestId: UUID): PrivacyRequestSummary {
        jdbcTemplate.queryForList(
            "SELECT id FROM tenant_privacy_requests WHERE tenant_id = ? AND id = ? FOR UPDATE",
            tenantId, requestId,
        ).singleOrNull() ?: throw PlatformControlNotFoundException("Privacy request was not found")
        return privacyRequest(tenantId, requestId)
    }

    private fun mapPrivacyRequest(rs: ResultSet) = PrivacyRequestSummary(
        requestId = rs.getObject("id", UUID::class.java),
        tenantId = rs.getObject("tenant_id", UUID::class.java),
        requestType = rs.getString("request_type"),
        subjectReference = rs.getString("subject_reference"),
        status = rs.getString("status"),
        assignedPlatformUserId = rs.getObject("assigned_platform_user_id", UUID::class.java),
        dueAt = rs.getTimestamp("due_at").toInstant(),
        verifiedAt = rs.getTimestamp("verified_at")?.toInstant(),
        completedAt = rs.getTimestamp("completed_at")?.toInstant(),
        rejectionReason = rs.getString("rejection_reason"),
        exportObjectKey = rs.getString("export_object_key"),
        exportContentHash = rs.getString("export_content_hash"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )

    private fun legalHold(tenantId: UUID, holdId: UUID): LegalHoldSummary = jdbcTemplate.query(
        """
        SELECT id, tenant_id, hold_scope, subject_reference, reason, status,
               starts_at, expires_at, released_at
        FROM tenant_legal_holds WHERE tenant_id = ? AND id = ?
        """.trimIndent(),
        { rs, _ ->
            LegalHoldSummary(
                rs.getObject("id", UUID::class.java),
                rs.getObject("tenant_id", UUID::class.java),
                rs.getString("hold_scope"), rs.getString("subject_reference"),
                rs.getString("reason"), rs.getString("status"),
                rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("expires_at")?.toInstant(),
                rs.getTimestamp("released_at")?.toInstant(),
            )
        }, tenantId, holdId,
    ).singleOrNull() ?: throw PlatformControlNotFoundException("Legal hold was not found")

    private fun identityConnection(
        tenantId: UUID,
        connectionId: UUID,
        lock: Boolean = false,
    ): IdentityConnectionSummary = jdbcTemplate.query(
        "$IDENTITY_SELECT WHERE tenant_id = ? AND id = ? ${if (lock) "FOR UPDATE" else ""}",
        { rs, _ -> mapIdentityConnection(rs) }, tenantId, connectionId,
    ).singleOrNull() ?: throw PlatformControlNotFoundException("Identity connection was not found")

    private fun mapIdentityConnection(rs: ResultSet) = IdentityConnectionSummary(
        connectionId = rs.getObject("id", UUID::class.java),
        tenantId = rs.getObject("tenant_id", UUID::class.java),
        name = rs.getString("connection_name"),
        protocol = rs.getString("protocol"),
        issuer = rs.getString("issuer"),
        verifiedDomain = rs.getString("verified_domain"),
        discoveryUrl = rs.getString("discovery_url"),
        clientId = rs.getString("client_id"),
        hasClientSecret = rs.getBoolean("has_client_secret"),
        scimEnabled = rs.getBoolean("scim_enabled"),
        status = rs.getString("status"),
        version = rs.getLong("version"),
        verifiedAt = rs.getTimestamp("verified_at")?.toInstant(),
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private fun String.normalizedVerificationCaseType(): String = trim().lowercase().also {
        require(it in VERIFICATION_CASE_TYPES) { "Unsupported verification case type" }
    }
    private fun String.normalizedVerificationLevel(): String = trim().lowercase().also {
        require(it in setOf("basic", "standard", "enhanced")) { "Unsupported verification level" }
    }
    private fun String.normalizedDocumentType(): String = trim().lowercase().also {
        require(it in VERIFICATION_DOCUMENT_TYPES) { "Unsupported verification document type" }
    }
    private fun String.normalizedRisk(): String = trim().lowercase().also {
        require(it in setOf("low", "medium", "high", "critical")) { "Unsupported risk rating" }
    }
    private fun String.normalizedPrivacyType(): String = trim().lowercase().also {
        require(it in PRIVACY_TYPES) { "Unsupported privacy request type" }
    }
    private fun String.normalizedLegalHoldScope(): String = trim().lowercase().also {
        require(it in setOf("tenant", "guest", "user", "financial", "document")) {
            "Unsupported legal hold scope"
        }
    }
    private fun String.normalizedSubjectReference(): String = trim().take(320).also {
        require(it.isNotBlank() && it.none(Char::isISOControl)) { "Invalid subject reference" }
    }
    private fun String.normalizedIdentityProtocol(): String = trim().lowercase().also {
        require(it in setOf("oidc", "saml", "ldap", "scim")) { "Unsupported identity protocol" }
    }
    private fun String.normalizedConnectionName(): String = trim().take(100).also {
        require(it.isNotBlank() && it.none(Char::isISOControl)) { "Invalid connection name" }
    }
    private fun String.normalizedDomain(): String = trim().lowercase().also {
        require(it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+"))) {
            "Invalid verified domain"
        }
    }

    private companion object {
        const val PLATFORM_VERIFICATION = "platform.tenants.verification.manage"
        val SAFE_OBJECT_KEY = Regex("[A-Za-z0-9][A-Za-z0-9/_ .-]{0,499}")
        val ALLOWED_VERIFICATION_MIME_TYPES = setOf(
            "application/pdf", "image/jpeg", "image/png", "image/webp",
        )
        val VERIFICATION_CASE_TYPES = setOf(
            "initial_onboarding", "annual_review", "license_refresh", "risk_review", "reactivation",
        )
        val VERIFICATION_DOCUMENT_TYPES = setOf(
            "business_registration", "tax_identification", "vat_registration",
            "business_license", "hospitality_license", "authorized_signatory_id",
            "bank_letter", "other",
        )
        val PRIVACY_TYPES = setOf(
            "access", "rectification", "erasure", "restriction", "portability",
            "objection", "consent_withdrawal",
        )
        val VERIFICATION_CASE_SELECT = """
            SELECT verification_case.id, verification_case.tenant_id,
                   verification_case.case_type, verification_case.required_level,
                   verification_case.status, verification_case.risk_rating,
                   verification_case.assigned_platform_user_id,
                   verification_case.submitted_at, verification_case.reviewed_at,
                   verification_case.expires_at, verification_case.rejection_reason,
                   verification_case.created_at, verification_case.updated_at
            FROM tenant_verification_cases verification_case
        """.trimIndent()
        val PRIVACY_SELECT = """
            SELECT id, tenant_id, request_type, subject_reference, status,
                   assigned_platform_user_id, due_at, verified_at, completed_at,
                   rejection_reason, export_object_key, export_content_hash,
                   created_at, updated_at
            FROM tenant_privacy_requests
        """.trimIndent()
        val IDENTITY_SELECT = """
            SELECT id, tenant_id, connection_name, protocol, issuer, verified_domain,
                   discovery_url, client_id, client_secret_ref IS NOT NULL AS has_client_secret,
                   scim_enabled, status, version, verified_at
            FROM tenant_identity_connections
        """.trimIndent()
    }
}
