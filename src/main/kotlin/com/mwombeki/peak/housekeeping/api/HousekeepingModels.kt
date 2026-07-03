package com.mwombeki.peak.housekeeping.api

import com.mwombeki.peak.shared.exception.BusinessException
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.http.HttpStatus

enum class HousekeepingTaskStatus {
    PENDING,
    ASSIGNED,
    IN_PROGRESS,
    AWAITING_INSPECTION,
    COMPLETED,
    SKIPPED,
    CANCELLED,
}

enum class HousekeepingTaskType {
    STAYOVER_CLEAN,
    DEPARTURE_CLEAN,
    DEEP_CLEAN,
    TURNDOWN,
}

enum class LostAndFoundStatus {
    HELD,
    CLAIMED,
    RETURNED,
    DISPOSED,
    DONATED,
}

data class HousekeepingSettingsResponse(
    val propertyId: UUID,
    val inspectionRequired: Boolean,
    val stayoverEnabled: Boolean,
    val stayoverIntervalDays: Int,
    val turnoverMinutes: Int,
)

data class UpdateHousekeepingSettingsRequest(
    val inspectionRequired: Boolean,
    val stayoverEnabled: Boolean = true,
    @field:Min(1)
    @field:Max(30)
    val stayoverIntervalDays: Int = 3,
    @field:Min(5)
    @field:Max(480)
    val turnoverMinutes: Int = 45,
)

data class CreateHousekeepingTaskRequest(
    @field:NotNull
    val roomId: UUID,
    @field:NotNull
    val type: HousekeepingTaskType,
    val scheduledDate: LocalDate,
    @field:Min(1)
    @field:Max(5)
    val priority: Int = 1,
    @field:Size(max = 1000)
    val notes: String? = null,
)

data class AssignHousekeepingTaskRequest(
    @field:NotNull
    val userId: UUID,
)

data class CompleteHousekeepingTaskRequest(
    @field:Size(max = 1000)
    val notes: String? = null,
)

data class InspectHousekeepingTaskRequest(
    val passed: Boolean,
    @field:Size(max = 1000)
    val notes: String? = null,
)

data class HousekeepingReasonRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 500)
    val reason: String,
)

data class HousekeepingTaskResponse(
    val id: UUID,
    val propertyId: UUID,
    val roomId: UUID,
    val sourceStayId: UUID?,
    val type: HousekeepingTaskType,
    val status: HousekeepingTaskStatus,
    val priority: Int,
    val scheduledDate: LocalDate,
    val assignedTo: UUID?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val completedBy: UUID?,
    val inspectedBy: UUID?,
    val notes: String?,
    val replayed: Boolean = false,
)

data class HousekeepingBoardResponse(
    val propertyId: UUID,
    val businessDate: LocalDate,
    val tasks: List<HousekeepingTaskResponse>,
    val counts: Map<HousekeepingTaskStatus, Int>,
)

data class CreateLostAndFoundRequest(
    val roomId: UUID? = null,
    @field:NotBlank
    @field:Size(max = 1000)
    val description: String,
    @field:NotBlank
    @field:Size(max = 100)
    val storageLocation: String,
)

data class LostAndFoundTransitionRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 500)
    val reason: String,
    @field:Size(max = 500)
    val claimantDetails: String? = null,
)

data class LostAndFoundResponse(
    val id: UUID,
    val propertyId: UUID,
    val roomId: UUID?,
    val description: String,
    val storageLocation: String?,
    val status: LostAndFoundStatus,
    val foundAt: Instant,
    val claimedAt: Instant?,
    val replayed: Boolean = false,
)

open class HousekeepingException(
    message: String,
    status: HttpStatus,
    code: String,
) : BusinessException(message, status, code)

class HousekeepingNotFoundException(message: String) :
    HousekeepingException(message, HttpStatus.NOT_FOUND, "HOUSEKEEPING_NOT_FOUND")

class HousekeepingConflictException(message: String) :
    HousekeepingException(message, HttpStatus.CONFLICT, "HOUSEKEEPING_CONFLICT")
