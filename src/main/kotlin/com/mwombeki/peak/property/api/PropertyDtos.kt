package com.mwombeki.peak.property.api

import java.util.UUID

data class PropertyResponse(
    val id: UUID,
    val tenantId: UUID,
    val name: String,
    val location: String?,
    val code: String?,
    val type: String,
    val status: String,
    val isActive: Boolean,
    val totalRooms: Int,
    val timezone: String,
    val businessDateOffset: Int,
)

data class CreatePropertyRequest(
    val name: String,
    val location: String? = null,
    val code: String? = null,
    val type: String = "HOTEL",
    val timezone: String = "Africa/Dar_es_Salaam",
    val businessDateOffset: Int = 0,
)

data class UpdatePropertyRequest(
    val name: String? = null,
    val location: String? = null,
    val code: String? = null,
    val type: String? = null,
    val timezone: String? = null,
    val businessDateOffset: Int? = null,
)

data class PropertyMutationReceipt(
    val propertyId: UUID,
    val status: String,
    val changed: Boolean,
    val replayed: Boolean,
)

data class PropertyChildMutationReceipt(
    val propertyId: UUID,
    val resourceType: String,
    val resourceId: UUID,
    val changed: Boolean,
    val replayed: Boolean,
)

data class PropertyModuleMutationReceipt(
    val propertyId: UUID,
    val moduleId: String,
    val enabled: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)

data class PropertyReadinessResponse(
    val propertyId: UUID,
    val isReady: Boolean,
    val missingRequirements: List<String>,
    val workflowStatus: String = "running",
    val currentStep: String? = null,
    val steps: List<PropertyOnboardingStepView> = emptyList(),
    val blockers: List<PropertyGoLiveBlockerView> = emptyList(),
    val collectionEnabled: Boolean = false,
    val nextAction: OnboardingNextAction? = null,
    val operatorBlocker: OperatorBlockerView? = null,
)

data class PropertyOnboardingResponse(
    val propertyId: UUID,
    val tenantId: UUID,
    val workflowStatus: String,
    val currentStep: String?,
    val isReady: Boolean,
    val collectionEnabled: Boolean,
    val steps: List<PropertyOnboardingStepView>,
    val blockers: List<PropertyGoLiveBlockerView>,
    val nextAction: OnboardingNextAction? = null,
    val operatorBlocker: OperatorBlockerView? = null,
)

data class PropertyBootstrapResponse(
    val propertyId: UUID,
    val tenantId: UUID,
    val status: String,
    val changed: Boolean,
    val replayed: Boolean,
    val nextAction: OnboardingNextAction?,
    val workflowStatus: String,
    val currentStep: String?,
    val isReady: Boolean,
    val steps: List<PropertyOnboardingStepView>,
    val blockers: List<PropertyGoLiveBlockerView>,
    val operatorBlocker: OperatorBlockerView?,
)

/**
 * The one hotel-fixable thing to do now. [path] is the versioned API the wizard
 * should call. [bodyHint] is a sample body, not a secret or a fake rail.
 */
data class OnboardingNextAction(
    val step: String,
    val title: String,
    val why: String,
    val method: String,
    val path: String,
    val bodyHint: Map<String, Any?>? = null,
)

/**
 * A Peak-ops fact the hotel cannot change. SMS routing is
 * `PEAK_COMMUNICATION_ROUTING_SMS` / `peak.communication.routing.sms`.
 */
data class OperatorBlockerView(
    val code: String,
    val title: String,
    val why: String,
)

data class PropertyOnboardingStepView(
    val key: String,
    val sequence: Int,
    val status: String,
    val required: Boolean,
    val detail: String,
)

data class PropertyGoLiveBlockerView(
    val code: String,
    val stepKey: String,
    val detail: String,
)

data class BuildingResponse(
    val id: UUID,
    val propertyId: UUID,
    val name: String,
    val description: String?,
)

data class CreateBuildingRequest(
    val name: String,
    val description: String? = null,
)

data class UpdateBuildingRequest(
    val name: String? = null,
    val description: String? = null,
)

data class FloorResponse(
    val id: UUID,
    val propertyId: UUID,
    val buildingId: UUID,
    val floorNumber: Int,
    val name: String?,
    val capacity: Int,
)

data class CreateFloorRequest(
    val buildingId: UUID,
    val floorNumber: Int,
    val name: String? = null,
    val capacity: Int = 0,
)

data class UpdateFloorRequest(
    val floorNumber: Int? = null,
    val name: String? = null,
    val capacity: Int? = null,
)

data class RoomTypeResponse(
    val id: UUID,
    val propertyId: UUID,
    val name: String,
    val code: String?,
    val description: String?,
    val basePrice: Double,
    val maxAdults: Int,
    val maxChildren: Int,
    val maxOccupancy: Int,
    val isActive: Boolean,
)

data class CreateRoomTypeRequest(
    val name: String,
    val code: String,
    val description: String? = null,
    val basePrice: Double = 0.0,
    val maxAdults: Int = 2,
    val maxChildren: Int = 0,
    val maxOccupancy: Int? = null,
)

data class UpdateRoomTypeRequest(
    val name: String? = null,
    val code: String? = null,
    val description: String? = null,
    val basePrice: Double? = null,
    val maxAdults: Int? = null,
    val maxChildren: Int? = null,
    val maxOccupancy: Int? = null,
    val isActive: Boolean? = null,
)

data class RoomResponse(
    val id: UUID,
    val propertyId: UUID,
    val roomTypeId: UUID,
    val roomNumber: String,
    val floorNumber: Int?,
    val status: String,
    val isSmoking: Boolean,
    val isAccessible: Boolean,
)

data class CreateRoomRequest(
    val buildingId: UUID,
    val roomNumber: String,
    val roomTypeId: UUID,
    val floorNumber: Int,
    val isSmoking: Boolean = false,
    val isAccessible: Boolean = false,
    val notes: String? = null,
)

data class UpdateRoomRequest(
    val roomTypeId: UUID? = null,
    val roomNumber: String? = null,
    val floorNumber: Int? = null,
    val isSmoking: Boolean? = null,
    val isAccessible: Boolean? = null,
    val notes: String? = null,
)

data class UpdateRoomStatusRequest(
    val status: String,
)

data class RoomStatusMutationReceipt(
    val propertyId: UUID,
    val roomId: UUID,
    val status: String,
    val changed: Boolean,
    val replayed: Boolean,
)

data class RevenueCenterResponse(
    val id: UUID,
    val propertyId: UUID,
    val name: String,
    val code: String,
    val centerType: String,
    val isRoomsRevenue: Boolean,
    val isActive: Boolean,
    val displayOrder: Int,
)

data class CreateRevenueCenterRequest(
    val name: String,
    val code: String,
    val centerType: String = "other",
    val isRoomsRevenue: Boolean = false,
    val displayOrder: Int = 100,
)

data class UpdateRevenueCenterRequest(
    val name: String? = null,
    val code: String? = null,
    val centerType: String? = null,
    val isRoomsRevenue: Boolean? = null,
    val isActive: Boolean? = null,
    val displayOrder: Int? = null,
)

data class DepartmentResponse(
    val id: UUID,
    val propertyId: UUID,
    val name: String,
    val code: String?,
)

data class CreateDepartmentRequest(
    val name: String,
    val code: String,
)

data class UpdateDepartmentRequest(
    val name: String? = null,
    val code: String? = null,
)

data class SetBaseRateRequest(
    val roomTypeId: UUID,
    val amount: Double,
    val currency: String = "TZS",
)

/**
 * The nightly base rate a room type sells at, which is `room_types.base_price` — the column
 * `setRoomTypeBaseRate` writes.
 *
 * There is deliberately no currency here. [SetBaseRateRequest] accepts one, validates it is a
 * three-letter code, and then discards it; nothing in the schema stores a per-rate currency.
 * Echoing back "TZS" would be this response inventing a fact the row cannot support, and a
 * caller would reasonably believe a non-TZS rate had been recorded when it had not.
 */
data class BaseRateResponse(
    val roomTypeId: UUID,
    val propertyId: UUID,
    val roomTypeName: String,
    val roomTypeCode: String?,
    val basePrice: Double,
    val maxOccupancy: Int,
    val isActive: Boolean,
)

data class CreateTaxRateRequest(
    val name: String,
    val code: String,
    val rate: Double,
    val taxType: String,
    val isCompound: Boolean = false,
    val isInclusive: Boolean = false,
)

data class UpdateTaxRateRequest(
    val name: String? = null,
    val code: String? = null,
    val rate: Double? = null,
    val taxType: String? = null,
    val isCompound: Boolean? = null,
    val isInclusive: Boolean? = null,
    val isActive: Boolean? = null,
)

data class TaxRateResponse(
    val id: UUID,
    val name: String,
    val code: String,
    val rate: Double,
    val taxType: String,
    val isCompound: Boolean,
    val isInclusive: Boolean,
    val isActive: Boolean,
)

data class EnableModuleRequest(
    val moduleId: String,
)

sealed class PropertyManagementException(message: String) : RuntimeException(message)

class PropertyManagementNotFoundException(
    message: String,
) : PropertyManagementException(message)

open class PropertyManagementConflictException(
    message: String,
) : PropertyManagementException(message)

class PropertyActivationBlockedException(
    message: String,
    val nextAction: OnboardingNextAction?,
    val blockers: List<PropertyGoLiveBlockerView>,
    val operatorBlocker: OperatorBlockerView?,
) : PropertyManagementConflictException(message)

data class PropertyActivationBlockedResponse(
    val title: String,
    val detail: String,
    val status: Int = 409,
    val nextAction: OnboardingNextAction?,
    val blockers: List<PropertyGoLiveBlockerView>,
    val operatorBlocker: OperatorBlockerView?,
)

class PropertyManagementInProgressException(
    message: String,
) : PropertyManagementException(message)
