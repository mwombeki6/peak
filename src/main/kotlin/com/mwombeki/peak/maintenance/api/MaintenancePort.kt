package com.mwombeki.peak.maintenance.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface MaintenancePort {
    fun listRequests(propertyId: UUID): List<MaintenanceRequestResponse>
    fun createRequest(propertyId: UUID, request: CreateMaintenanceRequest): MaintenanceRequestResponse
    fun listWorkOrders(propertyId: UUID): List<WorkOrderResponse>
    fun createWorkOrder(propertyId: UUID, request: CreateWorkOrderRequest): WorkOrderResponse
    fun assignWorkOrder(propertyId: UUID, id: UUID, request: AssignWorkOrderRequest): WorkOrderResponse
    fun transitionWorkOrder(
        propertyId: UUID,
        id: UUID,
        action: String,
        request: MaintenanceReasonRequest?,
    ): WorkOrderResponse
    fun blockRoom(propertyId: UUID, roomId: UUID, request: CreateRoomBlockRequest): RoomBlockResponse
    fun releaseBlock(
        propertyId: UUID,
        blockId: UUID,
        request: MaintenanceReasonRequest,
    ): RoomBlockResponse
}
