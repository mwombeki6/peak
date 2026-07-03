package com.mwombeki.peak.maintenance.internal.web

import com.mwombeki.peak.maintenance.api.AssignWorkOrderRequest
import com.mwombeki.peak.maintenance.api.CreateMaintenanceRequest
import com.mwombeki.peak.maintenance.api.CreateRoomBlockRequest
import com.mwombeki.peak.maintenance.api.CreateWorkOrderRequest
import com.mwombeki.peak.maintenance.api.MaintenancePort
import com.mwombeki.peak.maintenance.api.MaintenanceReasonRequest
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/maintenance")
class MaintenanceController(private val port: MaintenancePort) {
    @GetMapping("/requests")
    fun requests(@PathVariable propertyId: UUID) = port.listRequests(propertyId)

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    fun createRequest(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: CreateMaintenanceRequest,
    ) = port.createRequest(propertyId, request)

    @GetMapping("/work-orders")
    fun workOrders(@PathVariable propertyId: UUID) = port.listWorkOrders(propertyId)

    @PostMapping("/work-orders")
    @ResponseStatus(HttpStatus.CREATED)
    fun createWorkOrder(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: CreateWorkOrderRequest,
    ) = port.createWorkOrder(propertyId, request)

    @PostMapping("/work-orders/{id}/assign")
    fun assign(
        @PathVariable propertyId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody request: AssignWorkOrderRequest,
    ) = port.assignWorkOrder(propertyId, id, request)

    @PostMapping("/work-orders/{id}/start")
    fun start(@PathVariable propertyId: UUID, @PathVariable id: UUID) =
        port.transitionWorkOrder(propertyId, id, "start", null)

    @PostMapping("/work-orders/{id}/hold")
    fun hold(
        @PathVariable propertyId: UUID, @PathVariable id: UUID,
        @Valid @RequestBody request: MaintenanceReasonRequest,
    ) = port.transitionWorkOrder(propertyId, id, "hold", request)

    @PostMapping("/work-orders/{id}/complete")
    fun complete(
        @PathVariable propertyId: UUID, @PathVariable id: UUID,
        @Valid @RequestBody request: MaintenanceReasonRequest,
    ) = port.transitionWorkOrder(propertyId, id, "complete", request)

    @PostMapping("/work-orders/{id}/verify")
    fun verify(@PathVariable propertyId: UUID, @PathVariable id: UUID) =
        port.transitionWorkOrder(propertyId, id, "verify", null)

    @PostMapping("/work-orders/{id}/cancel")
    fun cancel(
        @PathVariable propertyId: UUID, @PathVariable id: UUID,
        @Valid @RequestBody request: MaintenanceReasonRequest,
    ) = port.transitionWorkOrder(propertyId, id, "cancel", request)

    @PostMapping("/rooms/{roomId}/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    fun block(
        @PathVariable propertyId: UUID, @PathVariable roomId: UUID,
        @Valid @RequestBody request: CreateRoomBlockRequest,
    ) = port.blockRoom(propertyId, roomId, request)

    @PostMapping("/room-blocks/{blockId}/release")
    fun release(
        @PathVariable propertyId: UUID, @PathVariable blockId: UUID,
        @Valid @RequestBody request: MaintenanceReasonRequest,
    ) = port.releaseBlock(propertyId, blockId, request)
}
