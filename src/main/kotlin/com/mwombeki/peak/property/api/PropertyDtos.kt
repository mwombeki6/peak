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

class PropertyManagementConflictException(
    message: String,
) : PropertyManagementException(message)

class PropertyManagementInProgressException(
    message: String,
) : PropertyManagementException(message)
