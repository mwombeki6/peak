package com.mwombeki.peak.nightaudit.api

import com.mwombeki.peak.shared.exception.BusinessException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.http.HttpStatus

data class RunNightAuditRequest(
    val auditDate: LocalDate? = null,
)

data class NightAuditIssueResponse(
    val id: UUID,
    val runId: UUID,
    val propertyId: UUID,
    val severity: String,
    val issueCode: String,
    val message: String,
    val blocking: Boolean,
    val resolvedAt: Instant?,
)

data class NightAuditRunResponse(
    val id: UUID,
    val tenantId: UUID,
    val propertyId: UUID,
    val auditDate: LocalDate,
    val attemptNo: Int,
    val status: String,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val runBy: UUID?,
    val summary: Map<String, Any?>,
    val issues: List<NightAuditIssueResponse> = emptyList(),
)

sealed class NightAuditException(
    message: String,
    status: HttpStatus,
    code: String,
) : BusinessException(message = message, status = status, errorCode = code)

class NightAuditNotFoundException(message: String) : NightAuditException(
    message = message,
    status = HttpStatus.NOT_FOUND,
    code = "NIGHT_AUDIT_NOT_FOUND",
)

class NightAuditConflictException(message: String) : NightAuditException(
    message = message,
    status = HttpStatus.CONFLICT,
    code = "NIGHT_AUDIT_CONFLICT",
)

class NightAuditInProgressException(message: String) : NightAuditException(
    message = message,
    status = HttpStatus.CONFLICT,
    code = "NIGHT_AUDIT_COMMAND_IN_PROGRESS",
)
