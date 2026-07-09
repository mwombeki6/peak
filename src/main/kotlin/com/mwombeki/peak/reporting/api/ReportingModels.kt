package com.mwombeki.peak.reporting.api

import com.mwombeki.peak.shared.exception.BusinessException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import org.springframework.http.HttpStatus

enum class ReportRunState {
    QUEUED, RUNNING, GENERATED, FAILED, CANCELLED, SUPERSEDED,
}

enum class ReportDeliveryState {
    QUEUED, SENDING, SENT, DELIVERED, FAILED, RETRY_SCHEDULED,
    DEAD_LETTER, CANCELLED,
}

enum class ReportSubscriptionState {
    ACTIVE, PAUSED, DISABLED, ARCHIVED,
}

data class ReportingSettingsResponse(
    val tenantId: UUID,
    val propertyId: UUID?,
    val retentionDays: Int,
    val source: String,
)

data class UpdateReportingSettingsRequest(val retentionDays: Int)

data class ReportCatalogResponse(
    val reportCode: String,
    val name: String,
    val description: String?,
    val scope: String,
    val sensitivity: String,
    val generatorAvailable: Boolean,
    val supportsEmail: Boolean,
    val supportsWhatsApp: Boolean,
)

data class CreateReportSubscriptionRequest(
    val reportCode: String,
    val subscriptionName: String,
    val propertyId: UUID? = null,
    val frequency: String = "after_night_audit",
    val scheduleTime: LocalTime? = null,
    val timezone: String = "Africa/Dar_es_Salaam",
    val languageCode: String = "en",
)

data class UpdateReportSubscriptionRequest(
    val subscriptionName: String,
    val frequency: String = "after_night_audit",
    val scheduleTime: LocalTime? = null,
    val timezone: String = "Africa/Dar_es_Salaam",
    val languageCode: String = "en",
)

data class AddReportRecipientRequest(
    val contactId: UUID,
    val contactChannelId: UUID,
)

data class ReportRecipientResponse(
    val id: UUID,
    val contactId: UUID,
    val contactChannelId: UUID,
    val channelType: String,
    val destinationMasked: String,
    val enabled: Boolean,
)

data class ReportSubscriptionResponse(
    val id: UUID,
    val tenantId: UUID,
    val propertyId: UUID?,
    val reportCode: String,
    val subscriptionName: String,
    val frequency: String,
    val timezone: String,
    val languageCode: String,
    val state: ReportSubscriptionState,
    val recipients: List<ReportRecipientResponse> = emptyList(),
)

data class CreateReportRunRequest(
    val businessDate: LocalDate? = null,
)

data class ReportRunResponse(
    val id: UUID,
    val tenantId: UUID,
    val propertyId: UUID?,
    val reportCode: String,
    val businessDate: LocalDate?,
    val state: ReportRunState,
    val contentHash: String?,
    val generatedAt: Instant?,
    val failureReason: String?,
    val createdAt: Instant,
)

data class ReportDownloadLinkResponse(
    val url: String,
    val expiresAt: Instant,
)

data class ReportDeliveryAttemptResponse(
    val id: UUID,
    val attemptNumber: Int,
    val channelType: String,
    val providerCode: String?,
    val state: String,
    val errorCode: String?,
    val startedAt: Instant,
    val completedAt: Instant?,
)

data class ReportDeliveryResponse(
    val id: UUID,
    val reportRunId: UUID,
    val reportCode: String,
    val channelType: String,
    val destinationMasked: String,
    val state: ReportDeliveryState,
    val attemptCount: Int,
    val linkExpiresAt: Instant?,
    val attempts: List<ReportDeliveryAttemptResponse> = emptyList(),
)

sealed class ReportingException(
    message: String,
    status: HttpStatus,
    code: String,
) : BusinessException(message, status, code)

class ReportingNotFoundException(message: String) : ReportingException(
    message, HttpStatus.NOT_FOUND, "REPORTING_NOT_FOUND",
)

class ReportingConflictException(message: String) : ReportingException(
    message, HttpStatus.CONFLICT, "REPORTING_CONFLICT",
)
