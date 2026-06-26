package com.mwombeki.peak.property.internal.web

import com.mwombeki.peak.property.api.*
import com.mwombeki.peak.property.internal.PropertyManagementService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/properties")
class PropertyManagementController(
    private val propertyService: PropertyManagementService,
) {
    @PostMapping
    fun createProperty(
        @RequestBody request: CreatePropertyRequest,
    ): ResponseEntity<Map<String, UUID>> {
        val id = propertyService.createProperty(request)
        return ResponseEntity.ok(mapOf("propertyId" to id))
    }

    @GetMapping
    fun listProperties(): ResponseEntity<List<PropertyResponse>> {
        return ResponseEntity.ok(propertyService.listProperties())
    }

    @GetMapping("/{propertyId}")
    fun getProperty(
        @PathVariable propertyId: UUID,
    ): ResponseEntity<PropertyResponse> {
        val property = propertyService.getProperty(propertyId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(property)
    }

    @PutMapping("/{propertyId}")
    fun updateProperty(
        @PathVariable propertyId: UUID,
        @RequestBody request: UpdatePropertyRequest,
    ): ResponseEntity<Void> {
        propertyService.updateProperty(propertyId, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{propertyId}")
    fun deleteProperty(
        @PathVariable propertyId: UUID,
    ): ResponseEntity<Void> {
        propertyService.deleteProperty(propertyId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{propertyId}/buildings")
    fun addBuilding(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateBuildingRequest,
    ): ResponseEntity<Map<String, UUID>>{
        val id = propertyService.createBuilding(propertyId, request)
        return ResponseEntity.ok(mapOf("buildingId" to id))
    }

    @PostMapping("/{propertyId}/rooms")
    fun addRoom(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateRoomRequest,
    ): ResponseEntity<Map<String, UUID>>{
        val id = propertyService.createRoom(propertyId, request)
        return ResponseEntity.ok(mapOf("roomId" to id))
    }

    @GetMapping("/{propertyId}/readiness")
    fun getReadinessReport(
        @PathVariable propertyId: UUID,
    ): ResponseEntity<PropertyReadinessResponse> {
        return ResponseEntity.ok(propertyService.checkReadiness(propertyId))
    }

    @PostMapping("/{propertyId}/activate")
    fun activateProperty(
        @PathVariable propertyId: UUID,
    ):ResponseEntity<PropertyReadinessResponse> {
        return ResponseEntity.ok(propertyService.activateProperty(propertyId))
    }

    @PostMapping("/{propertyId}/suspend")
    fun suspendProperty(
        @PathVariable propertyId: UUID,
    ): ResponseEntity<Void> {
        propertyService.suspendProperty(propertyId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{propertyId}/archive")
    fun archiveProperty(
        @PathVariable propertyId: UUID,
    ): ResponseEntity<Void> {
        propertyService.archiveProperty(propertyId)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/{propertyId}/rooms/{roomId}/status")
    fun changeRoomStatus(
        @PathVariable propertyId: UUID,
        @PathVariable roomId: UUID,
        @RequestParam status: String,
    ): ResponseEntity<Map<String, String>>{
        propertyService.updateRoomStatus(propertyId, roomId, status)
        return ResponseEntity.ok(mapOf("status" to "Successfully updated room state to ${status.uppercase()}"))
    }

    @PostMapping("/{propertyId}/floors")
    fun addFloor(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateFloorRequest
    ): ResponseEntity<Map<String, UUID>> {
        val id = propertyService.createFloor(propertyId, request)
        return ResponseEntity.ok(mapOf("floorId" to id))
    }

    @PostMapping("/{propertyId}/room-types")
    fun addRoomType(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateRoomTypeRequest
    ): ResponseEntity<Map<String, UUID>> {
        val id = propertyService.createRoomType(propertyId, request)
        return ResponseEntity.ok(mapOf("roomTypeId" to id))
    }

    @PostMapping("/{propertyId}/revenue-centers")
    fun addRevenueCenter(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateRevenueCenterRequest
    ): ResponseEntity<Map<String, UUID>> {
        val id = propertyService.createRevenueCenter(propertyId, request)
        return ResponseEntity.ok(mapOf("revenueCenterId" to id))
    }

    @PostMapping("/{propertyId}/departments")
    fun addDepartment(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateDepartmentRequest
    ): ResponseEntity<Map<String, UUID>> {
        val id = propertyService.createDepartment(propertyId, request)
        return ResponseEntity.ok(mapOf("departmentId" to id))
    }

    @PostMapping("/{propertyId}/rates")
    fun configureBaseRate(
        @PathVariable propertyId: UUID,
        @RequestBody request: SetBaseRateRequest
    ): ResponseEntity<Map<String, String>> {
        propertyService.setRoomTypeBaseRate(propertyId, request)
        return ResponseEntity.ok(mapOf("status" to "Base rate applied successfully."))
    }

    // Tax Configuration
    @PostMapping("/taxes")
    fun createTaxRate(
        @RequestBody request: CreateTaxRateRequest
    ): ResponseEntity<Map<String, UUID>> {
        val id = propertyService.createTaxRate(request)
        return ResponseEntity.ok(mapOf("taxRateId" to id))
    }

    @GetMapping("/taxes")
    fun listTaxRates(): ResponseEntity<List<TaxRateResponse>> {
        return ResponseEntity.ok(propertyService.listTaxRates())
    }

    // Module Management
    @PostMapping("/{propertyId}/modules")
    fun enableModule(
        @PathVariable propertyId: UUID,
        @RequestBody request: EnableModuleRequest
    ): ResponseEntity<Void> {
        propertyService.enableModule(propertyId, request.moduleId)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{propertyId}/modules/{moduleId}")
    fun disableModule(
        @PathVariable propertyId: UUID,
        @PathVariable moduleId: String
    ): ResponseEntity<Void> {
        propertyService.disableModule(propertyId, moduleId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{propertyId}/modules")
    fun listEnabledModules(
        @PathVariable propertyId: UUID
    ): ResponseEntity<List<String>> {
        return ResponseEntity.ok(propertyService.listEnabledModules(propertyId))
    }

}
