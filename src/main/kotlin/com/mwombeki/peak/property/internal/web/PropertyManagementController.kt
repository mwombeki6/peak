package com.mwombeki.peak.property.internal.web

import com.mwombeki.peak.shared.exception.ApiProblemFactory

import com.mwombeki.peak.property.api.BuildingResponse
import com.mwombeki.peak.property.api.CreateBuildingRequest
import com.mwombeki.peak.property.api.CreateDepartmentRequest
import com.mwombeki.peak.property.api.CreateFloorRequest
import com.mwombeki.peak.property.api.CreatePropertyRequest
import com.mwombeki.peak.property.api.CreateRevenueCenterRequest
import com.mwombeki.peak.property.api.CreateRoomRequest
import com.mwombeki.peak.property.api.CreateRoomTypeRequest
import com.mwombeki.peak.property.api.CreateTaxRateRequest
import com.mwombeki.peak.property.api.DepartmentResponse
import com.mwombeki.peak.property.api.EnableModuleRequest
import com.mwombeki.peak.property.api.FloorResponse
import com.mwombeki.peak.property.api.PropertyActivationBlockedException
import com.mwombeki.peak.property.api.PropertyActivationBlockedResponse
import com.mwombeki.peak.property.api.PropertyBootstrapResponse
import com.mwombeki.peak.property.api.PropertyChildMutationReceipt
import com.mwombeki.peak.property.api.PropertyManagementConflictException
import com.mwombeki.peak.property.api.PropertyManagementInProgressException
import com.mwombeki.peak.property.api.PropertyManagementNotFoundException
import com.mwombeki.peak.property.api.PropertyModuleMutationReceipt
import com.mwombeki.peak.property.api.PropertyMutationReceipt
import com.mwombeki.peak.property.api.PropertyOnboardingResponse
import com.mwombeki.peak.property.api.PropertyPort
import com.mwombeki.peak.property.api.PropertyReadinessResponse
import com.mwombeki.peak.property.api.PropertyResponse
import com.mwombeki.peak.property.api.RevenueCenterResponse
import com.mwombeki.peak.property.api.RoomResponse
import com.mwombeki.peak.property.api.RoomStatusMutationReceipt
import com.mwombeki.peak.property.api.RoomTypeResponse
import com.mwombeki.peak.property.api.SetBaseRateRequest
import com.mwombeki.peak.property.api.TaxRateResponse
import com.mwombeki.peak.property.api.UpdateBuildingRequest
import com.mwombeki.peak.property.api.UpdateDepartmentRequest
import com.mwombeki.peak.property.api.UpdateFloorRequest
import com.mwombeki.peak.property.api.UpdatePropertyRequest
import com.mwombeki.peak.property.api.UpdateRevenueCenterRequest
import com.mwombeki.peak.property.api.UpdateRoomRequest
import com.mwombeki.peak.property.api.UpdateRoomStatusRequest
import com.mwombeki.peak.property.api.UpdateRoomTypeRequest
import com.mwombeki.peak.property.api.UpdateTaxRateRequest
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties")
class PropertyManagementController(
    private val propertyPort: PropertyPort,
    private val apiProblemFactory: ApiProblemFactory,
) {
    @PostMapping
    fun createProperty(
        @RequestBody request: CreatePropertyRequest,
    ): PropertyMutationReceipt {
        return propertyPort.createProperty(request)
    }

    @PostMapping("/bootstrap")
    fun bootstrapFirstProperty(
        @RequestBody request: CreatePropertyRequest,
    ): PropertyBootstrapResponse {
        return propertyPort.bootstrapFirstProperty(request)
    }

    @GetMapping
    fun listProperties(): List<PropertyResponse> {
        return propertyPort.listProperties()
    }

    @GetMapping("/{propertyId}")
    fun getProperty(
        @PathVariable propertyId: UUID,
    ): PropertyResponse {
        return propertyPort.getProperty(propertyId)
            ?: throw PropertyManagementNotFoundException("Property record not found or access denied")
    }

    @PutMapping("/{propertyId}")
    fun updateProperty(
        @PathVariable propertyId: UUID,
        @RequestBody request: UpdatePropertyRequest,
    ): PropertyMutationReceipt {
        return propertyPort.updateProperty(propertyId, request)
    }

    @DeleteMapping("/{propertyId}")
    fun deleteProperty(
        @PathVariable propertyId: UUID,
    ): PropertyMutationReceipt {
        return propertyPort.deleteProperty(propertyId)
    }

    @PostMapping("/{propertyId}/activate")
    fun activateProperty(
        @PathVariable propertyId: UUID,
    ): ResponseEntity<*> {
        return try {
            ResponseEntity.ok(propertyPort.activateProperty(propertyId))
        } catch (ex: PropertyActivationBlockedException) {
            handleActivationBlocked(ex)
        }
    }

    @PostMapping("/{propertyId}/suspend")
    fun suspendProperty(
        @PathVariable propertyId: UUID,
    ): PropertyMutationReceipt {
        return propertyPort.suspendProperty(propertyId)
    }

    @PostMapping("/{propertyId}/archive")
    fun archiveProperty(
        @PathVariable propertyId: UUID,
    ): PropertyMutationReceipt {
        return propertyPort.archiveProperty(propertyId)
    }

    @GetMapping("/{propertyId}/readiness")
    fun getReadinessReport(
        @PathVariable propertyId: UUID,
    ): PropertyReadinessResponse {
        return propertyPort.checkReadiness(propertyId)
    }

    @GetMapping("/{propertyId}/onboarding")
    fun getOnboarding(
        @PathVariable propertyId: UUID,
    ): PropertyOnboardingResponse {
        return propertyPort.getOnboarding(propertyId)
    }

    @PostMapping("/{propertyId}/buildings")
    fun addBuilding(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateBuildingRequest,
    ): PropertyChildMutationReceipt {
        return propertyPort.createBuilding(propertyId, request)
    }

    @GetMapping("/{propertyId}/buildings")
    fun listBuildings(
        @PathVariable propertyId: UUID,
    ): List<BuildingResponse> {
        return propertyPort.listBuildings(propertyId)
    }

    @GetMapping("/{propertyId}/buildings/{buildingId}")
    fun getBuilding(
        @PathVariable propertyId: UUID,
        @PathVariable buildingId: UUID,
    ): BuildingResponse {
        return propertyPort.getBuilding(propertyId, buildingId)
            ?: throw PropertyManagementNotFoundException("Building record not found or access denied")
    }

    @PutMapping("/{propertyId}/buildings/{buildingId}")
    fun updateBuilding(
        @PathVariable propertyId: UUID,
        @PathVariable buildingId: UUID,
        @RequestBody request: UpdateBuildingRequest,
    ): PropertyChildMutationReceipt {
        return propertyPort.updateBuilding(propertyId, buildingId, request)
    }

    @DeleteMapping("/{propertyId}/buildings/{buildingId}")
    fun deleteBuilding(
        @PathVariable propertyId: UUID,
        @PathVariable buildingId: UUID,
    ): PropertyChildMutationReceipt {
        return propertyPort.deleteBuilding(propertyId, buildingId)
    }

    @PostMapping("/{propertyId}/floors")
    fun addFloor(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateFloorRequest,
    ): PropertyChildMutationReceipt {
        return propertyPort.createFloor(propertyId, request)
    }

    @GetMapping("/{propertyId}/floors")
    fun listFloors(
        @PathVariable propertyId: UUID,
    ): List<FloorResponse> {
        return propertyPort.listFloors(propertyId)
    }

    @GetMapping("/{propertyId}/floors/{floorId}")
    fun getFloor(
        @PathVariable propertyId: UUID,
        @PathVariable floorId: UUID,
    ): FloorResponse {
        return propertyPort.getFloor(propertyId, floorId)
            ?: throw PropertyManagementNotFoundException("Floor record not found or access denied")
    }

    @PutMapping("/{propertyId}/floors/{floorId}")
    fun updateFloor(
        @PathVariable propertyId: UUID,
        @PathVariable floorId: UUID,
        @RequestBody request: UpdateFloorRequest,
    ): PropertyChildMutationReceipt {
        return propertyPort.updateFloor(propertyId, floorId, request)
    }

    @DeleteMapping("/{propertyId}/floors/{floorId}")
    fun deleteFloor(
        @PathVariable propertyId: UUID,
        @PathVariable floorId: UUID,
    ): PropertyChildMutationReceipt {
        return propertyPort.deleteFloor(propertyId, floorId)
    }

    @PostMapping("/{propertyId}/room-types")
    fun addRoomType(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateRoomTypeRequest,
    ): PropertyChildMutationReceipt {
        return propertyPort.createRoomType(propertyId, request)
    }

    @GetMapping("/{propertyId}/room-types")
    fun listRoomTypes(
        @PathVariable propertyId: UUID,
    ): List<RoomTypeResponse> {
        return propertyPort.listRoomTypes(propertyId)
    }

    @GetMapping("/{propertyId}/room-types/{roomTypeId}")
    fun getRoomType(
        @PathVariable propertyId: UUID,
        @PathVariable roomTypeId: UUID,
    ): RoomTypeResponse {
        return propertyPort.getRoomType(propertyId, roomTypeId)
            ?: throw PropertyManagementNotFoundException("Room type record not found or access denied")
    }

    @PutMapping("/{propertyId}/room-types/{roomTypeId}")
    fun updateRoomType(
        @PathVariable propertyId: UUID,
        @PathVariable roomTypeId: UUID,
        @RequestBody request: UpdateRoomTypeRequest,
    ): PropertyChildMutationReceipt {
        return propertyPort.updateRoomType(propertyId, roomTypeId, request)
    }

    @DeleteMapping("/{propertyId}/room-types/{roomTypeId}")
    fun deleteRoomType(
        @PathVariable propertyId: UUID,
        @PathVariable roomTypeId: UUID,
    ): PropertyChildMutationReceipt {
        return propertyPort.deleteRoomType(propertyId, roomTypeId)
    }

    @PostMapping("/{propertyId}/rooms")
    fun addRoom(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateRoomRequest,
    ): PropertyChildMutationReceipt {
        return propertyPort.createRoom(propertyId, request)
    }

    @GetMapping("/{propertyId}/rooms")
    fun listRooms(
        @PathVariable propertyId: UUID,
    ): List<RoomResponse> {
        return propertyPort.listRooms(propertyId)
    }

    @GetMapping("/{propertyId}/rooms/{roomId}")
    fun getRoom(
        @PathVariable propertyId: UUID,
        @PathVariable roomId: UUID,
    ): RoomResponse {
        return propertyPort.getRoom(propertyId, roomId)
            ?: throw PropertyManagementNotFoundException("Room record not found or access denied")
    }

    @PutMapping("/{propertyId}/rooms/{roomId}")
    fun updateRoom(
        @PathVariable propertyId: UUID,
        @PathVariable roomId: UUID,
        @RequestBody request: UpdateRoomRequest,
    ): PropertyChildMutationReceipt {
        return propertyPort.updateRoom(propertyId, roomId, request)
    }

    @DeleteMapping("/{propertyId}/rooms/{roomId}")
    fun deleteRoom(
        @PathVariable propertyId: UUID,
        @PathVariable roomId: UUID,
    ): PropertyChildMutationReceipt {
        return propertyPort.deleteRoom(propertyId, roomId)
    }

    @PutMapping("/{propertyId}/rooms/{roomId}/status")
    fun changeRoomStatus(
        @PathVariable propertyId: UUID,
        @PathVariable roomId: UUID,
        @RequestBody request: UpdateRoomStatusRequest,
    ): RoomStatusMutationReceipt {
        return propertyPort.updateRoomStatus(propertyId, roomId, request)
    }

    @PostMapping("/{propertyId}/revenue-centers")
    fun addRevenueCenter(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateRevenueCenterRequest,
    ): PropertyChildMutationReceipt {
        return propertyPort.createRevenueCenter(propertyId, request)
    }

    @GetMapping("/{propertyId}/revenue-centers")
    fun listRevenueCenters(
        @PathVariable propertyId: UUID,
    ): List<RevenueCenterResponse> {
        return propertyPort.listRevenueCenters(propertyId)
    }

    @GetMapping("/{propertyId}/revenue-centers/{revenueCenterId}")
    fun getRevenueCenter(
        @PathVariable propertyId: UUID,
        @PathVariable revenueCenterId: UUID,
    ): RevenueCenterResponse {
        return propertyPort.getRevenueCenter(propertyId, revenueCenterId)
            ?: throw PropertyManagementNotFoundException("Revenue center record not found or access denied")
    }

    @PutMapping("/{propertyId}/revenue-centers/{revenueCenterId}")
    fun updateRevenueCenter(
        @PathVariable propertyId: UUID,
        @PathVariable revenueCenterId: UUID,
        @RequestBody request: UpdateRevenueCenterRequest,
    ): PropertyChildMutationReceipt {
        return propertyPort.updateRevenueCenter(propertyId, revenueCenterId, request)
    }

    @DeleteMapping("/{propertyId}/revenue-centers/{revenueCenterId}")
    fun deleteRevenueCenter(
        @PathVariable propertyId: UUID,
        @PathVariable revenueCenterId: UUID,
    ): PropertyChildMutationReceipt {
        return propertyPort.deleteRevenueCenter(propertyId, revenueCenterId)
    }

    @PostMapping("/{propertyId}/departments")
    fun addDepartment(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateDepartmentRequest,
    ): PropertyChildMutationReceipt {
        return propertyPort.createDepartment(propertyId, request)
    }

    @GetMapping("/{propertyId}/departments")
    fun listDepartments(
        @PathVariable propertyId: UUID,
    ): List<DepartmentResponse> {
        return propertyPort.listDepartments(propertyId)
    }

    @GetMapping("/{propertyId}/departments/{departmentId}")
    fun getDepartment(
        @PathVariable propertyId: UUID,
        @PathVariable departmentId: UUID,
    ): DepartmentResponse {
        return propertyPort.getDepartment(propertyId, departmentId)
            ?: throw PropertyManagementNotFoundException("Department record not found or access denied")
    }

    @PutMapping("/{propertyId}/departments/{departmentId}")
    fun updateDepartment(
        @PathVariable propertyId: UUID,
        @PathVariable departmentId: UUID,
        @RequestBody request: UpdateDepartmentRequest,
    ): PropertyChildMutationReceipt {
        return propertyPort.updateDepartment(propertyId, departmentId, request)
    }

    @DeleteMapping("/{propertyId}/departments/{departmentId}")
    fun deleteDepartment(
        @PathVariable propertyId: UUID,
        @PathVariable departmentId: UUID,
    ): PropertyChildMutationReceipt {
        return propertyPort.deleteDepartment(propertyId, departmentId)
    }

    @PostMapping("/{propertyId}/rates")
    fun configureBaseRate(
        @PathVariable propertyId: UUID,
        @RequestBody request: SetBaseRateRequest,
    ): PropertyChildMutationReceipt {
        return propertyPort.setRoomTypeBaseRate(propertyId, request)
    }

    @PostMapping("/taxes")
    fun createTaxRate(
        @RequestBody request: CreateTaxRateRequest,
    ): PropertyChildMutationReceipt {
        return propertyPort.createTaxRate(request)
    }

    @GetMapping("/taxes")
    fun listTaxRates(): List<TaxRateResponse> {
        return propertyPort.listTaxRates()
    }

    @GetMapping("/taxes/{taxRateId}")
    fun getTaxRate(
        @PathVariable taxRateId: UUID,
    ): TaxRateResponse {
        return propertyPort.getTaxRate(taxRateId)
            ?: throw PropertyManagementNotFoundException("Tax rate record not found or access denied")
    }

    @PutMapping("/taxes/{taxRateId}")
    fun updateTaxRate(
        @PathVariable taxRateId: UUID,
        @RequestBody request: UpdateTaxRateRequest,
    ): PropertyChildMutationReceipt {
        return propertyPort.updateTaxRate(taxRateId, request)
    }

    @DeleteMapping("/taxes/{taxRateId}")
    fun deleteTaxRate(
        @PathVariable taxRateId: UUID,
    ): PropertyChildMutationReceipt {
        return propertyPort.deleteTaxRate(taxRateId)
    }

    @PostMapping("/{propertyId}/modules")
    fun enableModule(
        @PathVariable propertyId: UUID,
        @RequestBody request: EnableModuleRequest,
    ): PropertyModuleMutationReceipt {
        return propertyPort.enableModule(propertyId, request.moduleId)
    }

    @DeleteMapping("/{propertyId}/modules/{moduleId}")
    fun disableModule(
        @PathVariable propertyId: UUID,
        @PathVariable moduleId: String,
    ): PropertyModuleMutationReceipt {
        return propertyPort.disableModule(propertyId, moduleId)
    }

    @GetMapping("/{propertyId}/modules")
    fun listEnabledModules(
        @PathVariable propertyId: UUID,
    ): List<String> {
        return propertyPort.listEnabledModules(propertyId)
    }

    @ExceptionHandler(PropertyManagementNotFoundException::class)
    fun handleNotFound(ex: PropertyManagementNotFoundException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.NOT_FOUND, "Property resource not found", ex.publicMessage())
    }

    @ExceptionHandler(PropertyActivationBlockedException::class)
    fun handleActivationBlocked(ex: PropertyActivationBlockedException): ResponseEntity<PropertyActivationBlockedResponse> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            PropertyActivationBlockedResponse(
                title = "Property is not ready to activate",
                detail = ex.publicMessage(),
                nextAction = ex.nextAction,
                blockers = ex.blockers,
                operatorBlocker = ex.operatorBlocker,
            ),
        )
    }

    @ExceptionHandler(PropertyManagementConflictException::class)
    fun handleConflict(ex: PropertyManagementConflictException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Property command conflict", ex.publicMessage())
    }

    @ExceptionHandler(PropertyManagementInProgressException::class)
    fun handleInProgress(ex: PropertyManagementInProgressException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Property command in progress", ex.publicMessage())
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidRequest(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.BAD_REQUEST, "Invalid property request", ex.publicMessage())
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        return apiProblemFactory.response(status, title, detail)
    }

    private fun RuntimeException.publicMessage(): String {
        val message = message.orEmpty()
        return if (message.startsWith("ERROR:")) {
            message.removePrefix("ERROR:").lineSequence().first().trim()
        } else {
            message
        }
    }
}
