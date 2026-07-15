package com.mwombeki.peak.nightaudit.api

import com.mwombeki.peak.shared.exception.BusinessException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.http.HttpStatus

data class AssignFinancialControlCaseRequest(
    val assigneeId: UUID,
    val dueAt: Instant? = null,
)

data class ResolveFinancialControlCaseRequest(
    val resolutionType: String,
    val note: String,
    val valueRecovered: BigDecimal = BigDecimal.ZERO,
    val valueProtected: BigDecimal = BigDecimal.ZERO,
)

data class FinancialControlEvidenceResponse(
    val id: UUID,
    val evidenceType: String,
    val resourceType: String?,
    val resourceId: UUID?,
    val amount: BigDecimal?,
    val payload: Map<String, Any?>,
    val recordedAt: Instant,
)

data class FinancialControlCaseEventResponse(
    val id: UUID,
    val eventType: String,
    val actorId: UUID?,
    val payload: Map<String, Any?>,
    val occurredAt: Instant,
)

data class FinancialControlCaseResponse(
    val id: UUID,
    val tenantId: UUID,
    val propertyId: UUID,
    val businessDate: LocalDate,
    val sourceRunId: UUID,
    val sourceIssueId: UUID,
    val issueCode: String,
    val category: String,
    val severity: String,
    val title: String,
    val description: String,
    val status: String,
    val currency: String,
    val quantity: Int,
    val amountAtRisk: BigDecimal?,
    val assignedTo: UUID?,
    val assignedBy: UUID?,
    val assignedAt: Instant?,
    val dueAt: Instant?,
    val resolutionType: String?,
    val resolutionNote: String?,
    val valueRecovered: BigDecimal,
    val valueProtected: BigDecimal,
    val resolvedBy: UUID?,
    val resolvedAt: Instant?,
    val firstDetectedAt: Instant,
    val lastDetectedAt: Instant,
    val occurrenceCount: Int,
    val version: Int,
    val evidence: List<FinancialControlEvidenceResponse> = emptyList(),
    val events: List<FinancialControlCaseEventResponse> = emptyList(),
    val replayed: Boolean = false,
)

data class DailyCloseCertification(
    val status: String,
    val runId: UUID,
    val snapshotId: UUID,
    val snapshotHash: String,
    val capturedAt: Instant,
    val cleanClose: Boolean,
    val closeWithAcceptedExceptions: Boolean,
)

data class DailyFinancialTruth(
    val currency: String,
    val revenueRecognized: BigDecimal,
    val grossSales: BigDecimal,
    val taxTotal: BigDecimal,
    val roomRevenue: BigDecimal,
    val posRevenue: BigDecimal,
    val cashAndDigitalCollected: BigDecimal,
    val paymentsByMethod: Map<String, BigDecimal>,
    val cashVariance: BigDecimal,
    val providerReconciliationVariance: BigDecimal,
    val refunds: BigDecimal,
    val reversals: BigDecimal,
    val revenueJournalDifference: BigDecimal,
    val paymentAllocationDifference: BigDecimal,
    val actualProfitCalculated: Boolean = false,
    val profitQualification: String =
        "Peak reports certified revenue and observed controls; complete operating costs are not yet available for actual profit.",
)

data class DailyRevenueAssurance(
    val totalCases: Int,
    val openCases: Int,
    val assignedCases: Int,
    val resolvedCases: Int,
    val acceptedCases: Int,
    val quantifiedAmountAtRisk: BigDecimal,
    val recordedValueRecovered: BigDecimal,
    val recordedValueProtected: BigDecimal,
)

data class DailyControlBriefResponse(
    val propertyId: UUID,
    val businessDate: LocalDate,
    val generatedAt: Instant,
    val close: DailyCloseCertification,
    val financialTruth: DailyFinancialTruth,
    val revenueAssurance: DailyRevenueAssurance,
    val actions: List<FinancialControlCaseResponse>,
)

sealed class FinancialControlException(
    message: String,
    status: HttpStatus,
    code: String,
) : BusinessException(message = message, status = status, errorCode = code)

class FinancialControlNotFoundException(message: String) :
    FinancialControlException(
        message,
        HttpStatus.NOT_FOUND,
        "FINANCIAL_CONTROL_NOT_FOUND",
    )

class FinancialControlConflictException(message: String) :
    FinancialControlException(
        message,
        HttpStatus.CONFLICT,
        "FINANCIAL_CONTROL_CONFLICT",
    )

class FinancialControlInProgressException(message: String) :
    FinancialControlException(
        message,
        HttpStatus.CONFLICT,
        "FINANCIAL_CONTROL_COMMAND_IN_PROGRESS",
    )
