package com.mwombeki.peak.property.internal.web

import com.mwombeki.peak.property.api.*
import com.mwombeki.peak.property.internal.PropertyManagementService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/properties")
class PropertyManagementController(
    private val propertyService: PropertyManagementService,
) {
    @PostMapping
    @PreAuthorize("hasRole('ROLE_TENANT_ADMIN')")
    fun createProperty(
        @RequestBody request: CreatePropertyRequest,
    ): ResponseEntity<Map<String, UUID>> {
        val id = propertyService.createProperty(request)
        return ResponseEntity.ok(mapOf("propertyId" to id))
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PLATFORM_OPERATOR')")
    fun listProperties(): ResponseEntity<List<PropertyResponse>> {
        return ResponseEntity.ok(propertyService.listProperties())
    }

    @GetMapping("/{propertyId}")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER', 'ROLE_PLATFORM_OPERATOR')")
    fun getProperty(
        @PathVariable propertyId: UUID,
    ): ResponseEntity<PropertyResponse> {
        val property = propertyService.getProperty(propertyId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(property)
    }

    @PutMapping("/{propertyId}")
    @PreAuthorize("hasRole('ROLE_TENANT_ADMIN')")
    fun updateProperty(
        @PathVariable propertyId: UUID,
        @RequestBody request: UpdatePropertyRequest,
    ): ResponseEntity<Void> {
        propertyService.updateProperty(propertyId, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{propertyId}")
    @PreAuthorize("hasRole('ROLE_TENANT_ADMIN')")
    fun deleteProperty(
        @PathVariable propertyId: UUID,
    ): ResponseEntity<Void> {
        propertyService.deleteProperty(propertyId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{propertyId}/buildings")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER')")
    fun addBuilding(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateBuildingRequest,
    ): ResponseEntity<Map<String, UUID>>{
        val id = propertyService.createBuilding(propertyId, request)
        return ResponseEntity.ok(mapOf("buildingId" to id))
    }

    @PostMapping("/{propertyId}/rooms")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN')")
    fun addRoom(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateRoomRequest,
    ): ResponseEntity<Map<String, UUID>>{
        val id = propertyService.createRoom(propertyId, request)
        return ResponseEntity.ok(mapOf("roomId" to id))
    }

    @GetMapping("/{propertyId}/readiness")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER','ROLE_PLATFORM_OPERATOR')")
    fun getReadinessReport(
        @PathVariable propertyId: UUID,
    ): ResponseEntity<PropertyReadinessResponse> {
        return ResponseEntity.ok(propertyService.checkReadiness(propertyId))
    }

    @PostMapping("/{propertyId}/activate")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN')")
    fun activateProperty(
        @PathVariable propertyId: UUID,
    ):ResponseEntity<PropertyReadinessResponse> {
        return ResponseEntity.ok(propertyService.activateProperty(propertyId))
    }

    @PostMapping("/{propertyId}/suspend")
    @PreAuthorize("hasRole('ROLE_TENANT_ADMIN')")
    fun suspendProperty(
        @PathVariable propertyId: UUID,
    ): ResponseEntity<Void> {
        propertyService.suspendProperty(propertyId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{propertyId}/archive")
    @PreAuthorize("hasRole('ROLE_TENANT_ADMIN')")
    fun archiveProperty(
        @PathVariable propertyId: UUID,
    ): ResponseEntity<Void> {
        propertyService.archiveProperty(propertyId)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/{propertyId}/rooms/{roomId}/status")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER', 'ROLE_HOUSEKEEPER')")
    fun changeRoomStatus(
        @PathVariable propertyId: UUID,
        @PathVariable roomId: UUID,
        @RequestParam status: String,
    ): ResponseEntity<Map<String, String>>{
        propertyService.updateRoomStatus(roomId, status)
        return ResponseEntity.ok(mapOf("status" to "Successfully updated room state to ${status.uppercase()}"))
    }

    @PostMapping("/{propertyId}/floors")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER')")
    fun addFloor(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateFloorRequest
    ): ResponseEntity<Map<String, UUID>> {
        val id = propertyService.createFloor(propertyId, request)
        return ResponseEntity.ok(mapOf("floorId" to id))
    }

    @PostMapping("/{propertyId}/room-types")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER')")
    fun addRoomType(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateRoomTypeRequest
    ): ResponseEntity<Map<String, UUID>> {
        val id = propertyService.createRoomType(propertyId, request)
        return ResponseEntity.ok(mapOf("roomTypeId" to id))
    }

    @PostMapping("/{propertyId}/revenue-centers")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN')")
    fun addRevenueCenter(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateRevenueCenterRequest
    ): ResponseEntity<Map<String, UUID>> {
        val id = propertyService.createRevenueCenter(propertyId, request)
        return ResponseEntity.ok(mapOf("revenueCenterId" to id))
    }

    @PostMapping("/{propertyId}/departments")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER')")
    fun addDepartment(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateDepartmentRequest
    ): ResponseEntity<Map<String, UUID>> {
        val id = propertyService.createDepartment(propertyId, request)
        return ResponseEntity.ok(mapOf("departmentId" to id))
    }

    @PostMapping("/{propertyId}/rates")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER')")
    fun configureBaseRate(
        @PathVariable propertyId: UUID,
        @RequestBody request: SetBaseRateRequest
    ): ResponseEntity<Map<String, String>> {
        propertyService.setRoomTypeBaseRate(propertyId, request)
        return ResponseEntity.ok(mapOf("status" to "Base rate applied successfully."))
    }

    // Tax Configuration
    @PostMapping("/taxes")
    @PreAuthorize("hasRole('ROLE_TENANT_ADMIN')")
    fun createTaxRate(
        @RequestBody request: CreateTaxRateRequest
    ): ResponseEntity<Map<String, UUID>> {
        val id = propertyService.createTaxRate(request)
        return ResponseEntity.ok(mapOf("taxRateId" to id))
    }

    @GetMapping("/taxes")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER')")
    fun listTaxRates(): ResponseEntity<List<TaxRateResponse>> {
        return ResponseEntity.ok(propertyService.listTaxRates())
    }

    // Module Management
    @PostMapping("/{propertyId}/modules")
    @PreAuthorize("hasRole('ROLE_TENANT_ADMIN')")
    fun enableModule(
        @PathVariable propertyId: UUID,
        @RequestBody request: EnableModuleRequest
    ): ResponseEntity<Void> {
        propertyService.enableModule(propertyId, request.moduleId)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{propertyId}/modules/{moduleId}")
    @PreAuthorize("hasRole('ROLE_TENANT_ADMIN')")
    fun disableModule(
        @PathVariable propertyId: UUID,
        @PathVariable moduleId: String
    ): ResponseEntity<Void> {
        propertyService.disableModule(propertyId, moduleId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{propertyId}/modules")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER')")
    fun listEnabledModules(
        @PathVariable propertyId: UUID
    ): ResponseEntity<List<String>> {
        return ResponseEntity.ok(propertyService.listEnabledModules(propertyId))
    }

}