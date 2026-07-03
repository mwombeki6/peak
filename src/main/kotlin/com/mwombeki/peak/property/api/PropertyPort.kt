package com.mwombeki.peak.property.api

import java.util.UUID
import java.time.LocalDate
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface PropertyPort {
    fun createProperty(request: CreatePropertyRequest): PropertyMutationReceipt
    fun updateProperty(propertyId: UUID, request: UpdatePropertyRequest): PropertyMutationReceipt
    fun getProperty(propertyId: UUID): PropertyResponse?
    fun listProperties(): List<PropertyResponse>
    fun deleteProperty(propertyId: UUID): PropertyMutationReceipt
    fun suspendProperty(propertyId: UUID): PropertyMutationReceipt
    fun archiveProperty(propertyId: UUID): PropertyMutationReceipt
    fun checkReadiness(propertyId: UUID): PropertyReadinessResponse
    fun activateProperty(propertyId: UUID): PropertyReadinessResponse

    fun createBuilding(propertyId: UUID, request: CreateBuildingRequest): PropertyChildMutationReceipt
    fun listBuildings(propertyId: UUID): List<BuildingResponse>
    fun getBuilding(propertyId: UUID, buildingId: UUID): BuildingResponse?
    fun updateBuilding(
        propertyId: UUID,
        buildingId: UUID,
        request: UpdateBuildingRequest,
    ): PropertyChildMutationReceipt
    fun deleteBuilding(propertyId: UUID, buildingId: UUID): PropertyChildMutationReceipt

    fun createFloor(propertyId: UUID, request: CreateFloorRequest): PropertyChildMutationReceipt
    fun listFloors(propertyId: UUID): List<FloorResponse>
    fun getFloor(propertyId: UUID, floorId: UUID): FloorResponse?
    fun updateFloor(propertyId: UUID, floorId: UUID, request: UpdateFloorRequest): PropertyChildMutationReceipt
    fun deleteFloor(propertyId: UUID, floorId: UUID): PropertyChildMutationReceipt

    fun createRoomType(propertyId: UUID, request: CreateRoomTypeRequest): PropertyChildMutationReceipt
    fun listRoomTypes(propertyId: UUID): List<RoomTypeResponse>
    fun getRoomType(propertyId: UUID, roomTypeId: UUID): RoomTypeResponse?
    fun updateRoomType(
        propertyId: UUID,
        roomTypeId: UUID,
        request: UpdateRoomTypeRequest,
    ): PropertyChildMutationReceipt
    fun deleteRoomType(propertyId: UUID, roomTypeId: UUID): PropertyChildMutationReceipt

    fun createRoom(propertyId: UUID, request: CreateRoomRequest): PropertyChildMutationReceipt
    fun listRooms(propertyId: UUID): List<RoomResponse>
    fun getRoom(propertyId: UUID, roomId: UUID): RoomResponse?
    fun updateRoom(propertyId: UUID, roomId: UUID, request: UpdateRoomRequest): PropertyChildMutationReceipt
    fun deleteRoom(propertyId: UUID, roomId: UUID): PropertyChildMutationReceipt
    fun updateRoomStatus(
        propertyId: UUID,
        roomId: UUID,
        request: UpdateRoomStatusRequest,
    ): RoomStatusMutationReceipt

    fun createRevenueCenter(
        propertyId: UUID,
        request: CreateRevenueCenterRequest,
    ): PropertyChildMutationReceipt
    fun listRevenueCenters(propertyId: UUID): List<RevenueCenterResponse>
    fun getRevenueCenter(propertyId: UUID, revenueCenterId: UUID): RevenueCenterResponse?
    fun updateRevenueCenter(
        propertyId: UUID,
        revenueCenterId: UUID,
        request: UpdateRevenueCenterRequest,
    ): PropertyChildMutationReceipt
    fun deleteRevenueCenter(propertyId: UUID, revenueCenterId: UUID): PropertyChildMutationReceipt

    fun createDepartment(propertyId: UUID, request: CreateDepartmentRequest): PropertyChildMutationReceipt
    fun listDepartments(propertyId: UUID): List<DepartmentResponse>
    fun getDepartment(propertyId: UUID, departmentId: UUID): DepartmentResponse?
    fun updateDepartment(
        propertyId: UUID,
        departmentId: UUID,
        request: UpdateDepartmentRequest,
    ): PropertyChildMutationReceipt
    fun deleteDepartment(propertyId: UUID, departmentId: UUID): PropertyChildMutationReceipt

    fun setRoomTypeBaseRate(propertyId: UUID, request: SetBaseRateRequest): PropertyChildMutationReceipt

    fun createTaxRate(request: CreateTaxRateRequest): PropertyChildMutationReceipt
    fun listTaxRates(): List<TaxRateResponse>
    fun getTaxRate(taxRateId: UUID): TaxRateResponse?
    fun updateTaxRate(taxRateId: UUID, request: UpdateTaxRateRequest): PropertyChildMutationReceipt
    fun deleteTaxRate(taxRateId: UUID): PropertyChildMutationReceipt

    fun enableModule(propertyId: UUID, moduleId: String): PropertyModuleMutationReceipt
    fun disableModule(propertyId: UUID, moduleId: String): PropertyModuleMutationReceipt
    fun listEnabledModules(propertyId: UUID): List<String>
}

@NamedInterface("api")
interface PropertyOperationsPort {
    fun requireAssignableRoom(
        tenantId: UUID,
        propertyId: UUID,
        roomTypeId: UUID,
        roomId: UUID,
    )

    fun markRoomOccupied(tenantId: UUID, propertyId: UUID, roomId: UUID)
    fun markRoomVacantDirty(tenantId: UUID, propertyId: UUID, roomId: UUID)
    fun currentBusinessDate(tenantId: UUID, propertyId: UUID): LocalDate
    fun advanceBusinessDate(
        tenantId: UUID,
        propertyId: UUID,
        expectedBusinessDate: LocalDate,
    ): LocalDate
}
