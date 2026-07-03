package com.mwombeki.peak.maintenance.internal

import com.mwombeki.peak.maintenance.api.AssignWorkOrderRequest
import com.mwombeki.peak.maintenance.api.CreateMaintenanceRequest
import com.mwombeki.peak.maintenance.api.CreateRoomBlockRequest
import com.mwombeki.peak.maintenance.api.CreateWorkOrderRequest
import com.mwombeki.peak.maintenance.api.MaintenanceConflictException
import com.mwombeki.peak.maintenance.api.MaintenanceNotFoundException
import com.mwombeki.peak.maintenance.api.MaintenancePort
import com.mwombeki.peak.maintenance.api.MaintenancePriority
import com.mwombeki.peak.maintenance.api.MaintenanceReasonRequest
import com.mwombeki.peak.maintenance.api.MaintenanceRequestResponse
import com.mwombeki.peak.maintenance.api.MaintenanceRequestStatus
import com.mwombeki.peak.maintenance.api.RoomBlockResponse
import com.mwombeki.peak.maintenance.api.RoomBlockStatus
import com.mwombeki.peak.maintenance.api.RoomBlockType
import com.mwombeki.peak.maintenance.api.WorkOrderResponse
import com.mwombeki.peak.maintenance.api.WorkOrderStatus
import com.mwombeki.peak.property.api.PropertyOperationsPort
import java.sql.ResultSet
import java.util.Locale
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class MaintenanceService(
    private val jdbc: JdbcTemplate,
    private val commands: MaintenanceCommandExecutor,
    private val property: PropertyOperationsPort,
) : MaintenancePort {
    override fun listRequests(propertyId: UUID) = commands.read(propertyId) { actor ->
        jdbc.query(
            "$REQUEST_SELECT ORDER BY created_at DESC",
            ::mapRequest, actor.tenantId, propertyId,
        )
    }

    override fun createRequest(
        propertyId: UUID,
        request: CreateMaintenanceRequest,
    ) = commands.mutate(
        propertyId, "maintenance.request.create", request, REQUESTS,
        MaintenanceRequestResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        requireRoom(actor.tenantId, propertyId, request.roomId)
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO maintenance_requests (
                id, tenant_id, property_id, room_id, reported_by, category,
                description, priority, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'open')
            """.trimIndent(),
            id, actor.tenantId, propertyId, request.roomId, actor.tenantUserId,
            request.category.required(), request.description.required(), request.priority.db(),
        )
        request(actor.tenantId, propertyId, id).also {
            commands.effects(
                actor, propertyId, "maintenance.request.created", REQUESTS, id,
                mapOf("requestId" to id, "roomId" to request.roomId), key,
            )
        }
    }

    override fun listWorkOrders(propertyId: UUID) = commands.read(propertyId) { actor ->
        jdbc.query(
            "$WORK_SELECT ORDER BY created_at DESC",
            ::mapWork, actor.tenantId, propertyId,
        )
    }

    override fun createWorkOrder(
        propertyId: UUID,
        request: CreateWorkOrderRequest,
    ) = commands.mutate(
        propertyId, "maintenance.work_order.create", request, WORK_ORDERS,
        WorkOrderResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        val roomId = request.requestId?.let {
            request(actor.tenantId, propertyId, it, true).roomId
        } ?: request.roomId
        roomId?.let { requireRoom(actor.tenantId, propertyId, it) }
        val priority = request.priority.lowercase(Locale.ROOT)
        require(priority in setOf("low", "normal", "high", "emergency")) {
            "Unsupported work-order priority"
        }
        val category = request.category.lowercase(Locale.ROOT)
        require(category in setOf(
            "plumbing", "electrical", "hvac", "carpentry", "painting",
            "cleaning", "pest_control", "it", "general",
        )) { "Unsupported corrective work-order category" }
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO work_orders (
                id, tenant_id, property_id, request_id, room_id, title,
                description, priority, category, status, created_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', ?)
            """.trimIndent(),
            id, actor.tenantId, propertyId, request.requestId, roomId,
            request.title.required(), request.description.clean(), priority,
            category, actor.tenantUserId,
        )
        work(actor.tenantId, propertyId, id).also {
            commands.effects(
                actor, propertyId, "maintenance.work_order.created", WORK_ORDERS, id,
                mapOf("workOrderId" to id, "roomId" to roomId), key,
            )
        }
    }

    override fun assignWorkOrder(
        propertyId: UUID,
        id: UUID,
        request: AssignWorkOrderRequest,
    ) = workTransition(propertyId, id, "assign", request) { actor, current ->
        require(current.status == WorkOrderStatus.OPEN) { "Only open work orders can be assigned" }
        jdbc.update(
            """
            UPDATE work_orders SET status = 'assigned', assigned_to = ?, updated_at = now()
            WHERE tenant_id = ? AND property_id = ? AND id = ? AND status = 'open'
            """.trimIndent(),
            request.userId, actor.tenantId, propertyId, id,
        )
    }

    override fun transitionWorkOrder(
        propertyId: UUID,
        id: UUID,
        action: String,
        request: MaintenanceReasonRequest?,
    ) = workTransition(propertyId, id, action, request ?: emptyMap<String, String>()) { actor, current ->
        when (action.lowercase(Locale.ROOT)) {
            "start" -> {
                require(current.status == WorkOrderStatus.ASSIGNED)
                require(current.assignedTo == actor.tenantUserId) {
                    "Only the assigned technician can start work"
                }
                jdbc.update(
                    """
                    UPDATE work_orders SET status = 'in_progress',
                        started_at = COALESCE(started_at, now()), updated_at = now()
                    WHERE tenant_id = ? AND property_id = ? AND id = ? AND status = 'assigned'
                    """.trimIndent(),
                    actor.tenantId, propertyId, id,
                )
            }
            "hold" -> {
                require(current.status == WorkOrderStatus.IN_PROGRESS)
                val reason = requireNotNull(request).reason.required()
                jdbc.update(
                    """
                    UPDATE work_orders SET status = 'on_hold', hold_reason = ?, updated_at = now()
                    WHERE tenant_id = ? AND property_id = ? AND id = ? AND status = 'in_progress'
                    """.trimIndent(),
                    reason, actor.tenantId, propertyId, id,
                )
            }
            "complete" -> {
                require(current.status in setOf(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.ON_HOLD))
                jdbc.update(
                    """
                    UPDATE work_orders SET status = 'awaiting_verification',
                        completed_at = now(), completion_notes = ?, updated_at = now()
                    WHERE tenant_id = ? AND property_id = ? AND id = ?
                      AND status IN ('in_progress', 'on_hold')
                    """.trimIndent(),
                    request?.reason?.required(), actor.tenantId, propertyId, id,
                )
            }
            "verify" -> {
                require(current.status == WorkOrderStatus.AWAITING_VERIFICATION)
                require(current.assignedTo != actor.tenantUserId) {
                    "Assigned technician cannot verify their own work"
                }
                jdbc.update(
                    """
                    UPDATE work_orders SET status = 'verified', verified_by = ?,
                        verified_at = now(), updated_at = now()
                    WHERE tenant_id = ? AND property_id = ? AND id = ?
                      AND status = 'awaiting_verification'
                    """.trimIndent(),
                    actor.tenantUserId, actor.tenantId, propertyId, id,
                )
                current.requestId?.let {
                    jdbc.update(
                        """
                        UPDATE maintenance_requests SET status = 'resolved',
                            resolved_at = now(), updated_at = now()
                        WHERE tenant_id = ? AND property_id = ? AND id = ?
                          AND status <> 'cancelled'
                        """.trimIndent(),
                        actor.tenantId, propertyId, it,
                    )
                }
            }
            "cancel" -> {
                require(current.status !in setOf(WorkOrderStatus.VERIFIED, WorkOrderStatus.CANCELLED))
                val reason = requireNotNull(request).reason.required()
                jdbc.update(
                    """
                    UPDATE work_orders SET status = 'cancelled', notes = ?, updated_at = now()
                    WHERE tenant_id = ? AND property_id = ? AND id = ?
                      AND status NOT IN ('verified', 'cancelled')
                    """.trimIndent(),
                    reason, actor.tenantId, propertyId, id,
                )
            }
            else -> throw IllegalArgumentException("Unsupported work-order action")
        }
    }

    override fun blockRoom(
        propertyId: UUID,
        roomId: UUID,
        request: CreateRoomBlockRequest,
    ) = commands.mutate(
        propertyId, "maintenance.room_block.create",
        mapOf("roomId" to roomId, "request" to request), BLOCKS,
        RoomBlockResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        requireRoom(actor.tenantId, propertyId, roomId)
        request.workOrderId?.let { work(actor.tenantId, propertyId, it, true) }
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO room_blocks (
                id, tenant_id, property_id, room_id, work_order_id, block_type,
                reason, blocked_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, actor.tenantId, propertyId, roomId, request.workOrderId,
            request.type.db(), request.reason.required(), actor.tenantUserId,
        )
        property.placeMaintenanceBlock(
            actor.tenantId, propertyId, roomId, request.type.db(),
            request.reason.required(), id, actor.tenantUserId,
        )
        block(actor.tenantId, propertyId, id).also {
            commands.effects(
                actor, propertyId, "maintenance.room_block.created", BLOCKS, id,
                mapOf("blockId" to id, "roomId" to roomId, "type" to request.type), key,
            )
        }
    }

    override fun releaseBlock(
        propertyId: UUID,
        blockId: UUID,
        request: MaintenanceReasonRequest,
    ) = commands.mutate(
        propertyId, "maintenance.room_block.release",
        mapOf("blockId" to blockId, "request" to request), BLOCKS,
        RoomBlockResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        val current = block(actor.tenantId, propertyId, blockId, true)
        if (current.status != RoomBlockStatus.ACTIVE) {
            throw MaintenanceConflictException("Room block is already released")
        }
        jdbc.update(
            """
            UPDATE room_blocks SET status = 'released', released_by = ?,
                released_at = now(), release_reason = ?, updated_at = now()
            WHERE tenant_id = ? AND property_id = ? AND id = ? AND status = 'active'
            """.trimIndent(),
            actor.tenantUserId, request.reason.required(),
            actor.tenantId, propertyId, blockId,
        )
        property.releaseMaintenanceBlock(
            actor.tenantId, propertyId, current.roomId, current.type.db(),
            request.reason.required(), blockId, actor.tenantUserId,
        )
        block(actor.tenantId, propertyId, blockId).also {
            commands.effects(
                actor, propertyId, "maintenance.room_block.released", BLOCKS, blockId,
                mapOf("blockId" to blockId, "roomId" to current.roomId), key,
            )
        }
    }

    private fun workTransition(
        propertyId: UUID,
        id: UUID,
        action: String,
        payload: Any,
        change: (com.mwombeki.peak.shared.context.TenantActor, WorkOrderResponse) -> Unit,
    ) = commands.mutate(
        propertyId, "maintenance.work_order.$action", mapOf("id" to id, "payload" to payload),
        WORK_ORDERS, WorkOrderResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        val current = work(actor.tenantId, propertyId, id, true)
        try {
            change(actor, current)
        } catch (ex: IllegalArgumentException) {
            throw MaintenanceConflictException(ex.message ?: "Invalid work-order transition")
        }
        work(actor.tenantId, propertyId, id).also {
            commands.effects(
                actor, propertyId, "maintenance.work_order.$action", WORK_ORDERS, id,
                mapOf("workOrderId" to id, "status" to it.status), key,
            )
        }
    }

    private fun request(
        tenantId: UUID, propertyId: UUID, id: UUID, lock: Boolean = false,
    ) = jdbc.query(
        "$REQUEST_SELECT AND id = ? ${if (lock) "FOR UPDATE" else ""}",
        ::mapRequest, tenantId, propertyId, id,
    ).singleOrNull() ?: throw MaintenanceNotFoundException("Maintenance request was not found")

    private fun work(
        tenantId: UUID, propertyId: UUID, id: UUID, lock: Boolean = false,
    ) = jdbc.query(
        "$WORK_SELECT AND id = ? ${if (lock) "FOR UPDATE" else ""}",
        ::mapWork, tenantId, propertyId, id,
    ).singleOrNull() ?: throw MaintenanceNotFoundException("Work order was not found")

    private fun block(
        tenantId: UUID, propertyId: UUID, id: UUID, lock: Boolean = false,
    ) = jdbc.query(
        "$BLOCK_SELECT AND id = ? ${if (lock) "FOR UPDATE" else ""}",
        ::mapBlock, tenantId, propertyId, id,
    ).singleOrNull() ?: throw MaintenanceNotFoundException("Room block was not found")

    private fun requireRoom(tenantId: UUID, propertyId: UUID, roomId: UUID) {
        val exists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM rooms WHERE tenant_id = ? AND property_id = ? AND id = ? AND deleted_at IS NULL)",
            Boolean::class.java, tenantId, propertyId, roomId,
        ) == true
        if (!exists) throw MaintenanceNotFoundException("Room was not found")
    }

    private fun mapRequest(rs: ResultSet, ignored: Int) = MaintenanceRequestResponse(
        rs.uuid("id"), rs.uuid("property_id"), rs.uuid("room_id"),
        rs.getString("category"), rs.getString("description"),
        MaintenancePriority.valueOf(rs.getString("priority").uppercase(Locale.ROOT)),
        MaintenanceRequestStatus.valueOf(rs.getString("status").uppercase(Locale.ROOT)),
        rs.getTimestamp("created_at").toInstant(),
    )

    private fun mapWork(rs: ResultSet, ignored: Int) = WorkOrderResponse(
        rs.uuid("id"), rs.uuid("property_id"), rs.uuidOrNull("request_id"),
        rs.uuidOrNull("room_id"), rs.uuidOrNull("assigned_to"), rs.getString("title"),
        rs.getString("priority"), rs.getString("category"),
        WorkOrderStatus.valueOf(rs.getString("status").uppercase(Locale.ROOT)),
        rs.getTimestamp("started_at")?.toInstant(), rs.getTimestamp("completed_at")?.toInstant(),
        rs.uuidOrNull("verified_by"),
    )

    private fun mapBlock(rs: ResultSet, ignored: Int) = RoomBlockResponse(
        rs.uuid("id"), rs.uuid("property_id"), rs.uuid("room_id"),
        rs.uuidOrNull("work_order_id"),
        RoomBlockType.valueOf(rs.getString("block_type").uppercase(Locale.ROOT)),
        RoomBlockStatus.valueOf(rs.getString("status").uppercase(Locale.ROOT)),
        rs.getString("reason"), rs.getTimestamp("blocked_at").toInstant(),
        rs.getTimestamp("released_at")?.toInstant(),
    )

    private fun ResultSet.uuid(column: String) = getObject(column, UUID::class.java)
    private fun ResultSet.uuidOrNull(column: String) = getObject(column, UUID::class.java)
    private fun Enum<*>.db() = name.lowercase(Locale.ROOT)
    private fun String.required() = trim().takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Value is required")
    private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val REQUESTS = "maintenance_requests"
        const val WORK_ORDERS = "work_orders"
        const val BLOCKS = "room_blocks"
        const val REQUEST_SELECT = """
            SELECT id, property_id, room_id, category, description, priority,
                   status, created_at
            FROM maintenance_requests
            WHERE tenant_id = ? AND property_id = ?
        """
        const val WORK_SELECT = """
            SELECT id, property_id, request_id, room_id, assigned_to, title,
                   priority, category, status, started_at, completed_at, verified_by,
                   created_at
            FROM work_orders
            WHERE tenant_id = ? AND property_id = ? AND deleted_at IS NULL
        """
        const val BLOCK_SELECT = """
            SELECT id, property_id, room_id, work_order_id, block_type, status,
                   reason, blocked_at, released_at
            FROM room_blocks
            WHERE tenant_id = ? AND property_id = ?
        """
    }
}
