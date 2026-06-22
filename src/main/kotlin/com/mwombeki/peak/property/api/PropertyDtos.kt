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
)

data class CreatePropertyRequest(
    val name: String,
    val location: String? = null,
    val code: String? = null,
    val type: String = "HOTEL",
)

data class UpdatePropertyRequest(
    val name: String?,
    val location: String?,
    val code: String?,
    val type: String?,
)

data class CreateBuildingRequest(
    val name: String,
    val description: String,
)

data class CreateRoomRequest(
    val buildingId: UUID,
    val roomNumber: String,
    val roomTypeId: UUID,
    val floorNumber: Int,
)

data class PropertyReadinessResponse(
    val propertyId: UUID,
    val isReady: Boolean,
    val missingRequirements: List<String>,
)

data class CreateFloorRequest(
    val buildingId: UUID,
    val floorNumber: Int,
    val name: String?
)

data class CreateRoomTypeRequest(
    val name: String,
    val code: String,
    val baseCapacity: Int,
)

data class CreateRevenueCenterRequest(
    val name: String,         // e.g., "Peak Restaurant", "Main Front Desk"
    val code: String          // e.g., "REV-REST", "REV-FD"
)

data class CreateDepartmentRequest(
    val name: String,         // e.g., "Housekeeping", "Food & Beverage"
    val code: String          // e.g., "DEPT-HK", "DEPT-FB"
)

data class SetBaseRateRequest(
    val roomTypeId: UUID,
    val amount: Double,       // e.g., 250000.00
    val currency: String      // e.g., "TZS"
)

data class CreateTaxRateRequest(
    val name: String,
    val code: String,
    val rate: Double,         // 0.18 for 18%
    val taxType: String,      // vat, levy, service_charge, tourism_levy, exempt, other
    val isCompound: Boolean = false,
    val isInclusive: Boolean = false,
)

data class TaxRateResponse(
    val id: UUID,
    val name: String,
    val code: String,
    val rate: Double,
    val taxType: String,
    val isCompound: Boolean,
    val isInclusive: Boolean,
)

data class EnableModuleRequest(
    val moduleId: String,
)