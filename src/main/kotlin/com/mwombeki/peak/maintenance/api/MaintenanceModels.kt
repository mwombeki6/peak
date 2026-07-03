package com.mwombeki.peak.maintenance.api

import com.mwombeki.peak.shared.exception.BusinessException
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus

enum class MaintenancePriority { LOW, MEDIUM, HIGH, CRITICAL }
enum class MaintenanceRequestStatus { OPEN, IN_PROGRESS, RESOLVED, CANCELLED, DEFERRED }
enum class WorkOrderStatus {
    OPEN, ASSIGNED, IN_PROGRESS, ON_HOLD, AWAITING_VERIFICATION, VERIFIED, CANCELLED,
}
enum class RoomBlockType { OUT_OF_SERVICE, OUT_OF_ORDER }
enum class RoomBlockStatus { ACTIVE, RELEASED }

data class CreateMaintenanceRequest(
    @field:NotNull val roomId: UUID,
    @field:NotBlank @field:Size(max = 100) val category: String,
    @field:NotBlank @field:Size(max = 2000) val description: String,
    val priority: MaintenancePriority = MaintenancePriority.MEDIUM,
)

data class MaintenanceRequestResponse(
    val id: UUID,
    val propertyId: UUID,
    val roomId: UUID,
    val category: String?,
    val description: String,
    val priority: MaintenancePriority,
    val status: MaintenanceRequestStatus,
    val createdAt: Instant,
    val replayed: Boolean = false,
)

data class CreateWorkOrderRequest(
    val requestId: UUID? = null,
    val roomId: UUID? = null,
    @field:NotBlank @field:Size(max = 200) val title: String,
    @field:Size(max = 2000) val description: String? = null,
    @field:NotBlank val priority: String = "normal",
    @field:NotBlank val category: String = "general",
)

data class AssignWorkOrderRequest(@field:NotNull val userId: UUID)
data class MaintenanceReasonRequest(
    @field:NotBlank @field:Size(min = 3, max = 1000) val reason: String,
)

data class WorkOrderResponse(
    val id: UUID,
    val propertyId: UUID,
    val requestId: UUID?,
    val roomId: UUID?,
    val assignedTo: UUID?,
    val title: String,
    val priority: String,
    val category: String,
    val status: WorkOrderStatus,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val verifiedBy: UUID?,
    val replayed: Boolean = false,
)

data class CreateRoomBlockRequest(
    val workOrderId: UUID? = null,
    @field:NotNull val type: RoomBlockType,
    @field:NotBlank @field:Size(min = 3, max = 1000) val reason: String,
)

data class RoomBlockResponse(
    val id: UUID,
    val propertyId: UUID,
    val roomId: UUID,
    val workOrderId: UUID?,
    val type: RoomBlockType,
    val status: RoomBlockStatus,
    val reason: String,
    val blockedAt: Instant,
    val releasedAt: Instant?,
    val replayed: Boolean = false,
)

open class MaintenanceException(
    message: String, status: HttpStatus, code: String,
) : BusinessException(message, status, code)
class MaintenanceNotFoundException(message: String) :
    MaintenanceException(message, HttpStatus.NOT_FOUND, "MAINTENANCE_NOT_FOUND")
class MaintenanceConflictException(message: String) :
    MaintenanceException(message, HttpStatus.CONFLICT, "MAINTENANCE_CONFLICT")
