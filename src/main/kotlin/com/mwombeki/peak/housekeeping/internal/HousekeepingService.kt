package com.mwombeki.peak.housekeeping.internal

import com.mwombeki.peak.frontdesk.api.HousekeepingStaySummaryPort
import com.mwombeki.peak.housekeeping.api.AssignHousekeepingTaskRequest
import com.mwombeki.peak.housekeeping.api.CompleteHousekeepingTaskRequest
import com.mwombeki.peak.housekeeping.api.CreateHousekeepingTaskRequest
import com.mwombeki.peak.housekeeping.api.CreateLostAndFoundRequest
import com.mwombeki.peak.housekeeping.api.HousekeepingBoardResponse
import com.mwombeki.peak.housekeeping.api.HousekeepingConflictException
import com.mwombeki.peak.housekeeping.api.HousekeepingNotFoundException
import com.mwombeki.peak.housekeeping.api.HousekeepingPort
import com.mwombeki.peak.housekeeping.api.HousekeepingReasonRequest
import com.mwombeki.peak.housekeeping.api.HousekeepingSettingsResponse
import com.mwombeki.peak.housekeeping.api.HousekeepingTaskResponse
import com.mwombeki.peak.housekeeping.api.HousekeepingTaskStatus
import com.mwombeki.peak.housekeeping.api.HousekeepingTaskType
import com.mwombeki.peak.housekeeping.api.InspectHousekeepingTaskRequest
import com.mwombeki.peak.housekeeping.api.LostAndFoundResponse
import com.mwombeki.peak.housekeeping.api.LostAndFoundStatus
import com.mwombeki.peak.housekeeping.api.LostAndFoundTransitionRequest
import com.mwombeki.peak.housekeeping.api.UpdateHousekeepingSettingsRequest
import com.mwombeki.peak.property.api.PropertyOperationsPort
import java.sql.ResultSet
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class HousekeepingService(
    private val jdbcTemplate: JdbcTemplate,
    private val executor: HousekeepingCommandExecutor,
    private val propertyOperationsPort: PropertyOperationsPort,
    private val frontDeskHousekeepingPort: HousekeepingStaySummaryPort,
) : HousekeepingPort {

    override fun getSettings(propertyId: UUID): HousekeepingSettingsResponse =
        executor.read(propertyId) { actor -> settings(actor.tenantId, propertyId) }

    override fun updateSettings(
        propertyId: UUID,
        request: UpdateHousekeepingSettingsRequest,
    ): HousekeepingSettingsResponse = executor.mutate(
        propertyId, "housekeeping.settings.update", request, SETTINGS,
        HousekeepingSettingsResponse::class.java, { it.propertyId }, { it },
    ) { actor, key ->
        jdbcTemplate.update(
            """
            INSERT INTO property_housekeeping_settings (
                tenant_id, property_id, inspection_required, stayover_enabled,
                midstay_interval_days, turnover_timer_mins,
                supervisor_inspection_lock
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, property_id) DO UPDATE SET
                inspection_required = EXCLUDED.inspection_required,
                stayover_enabled = EXCLUDED.stayover_enabled,
                midstay_interval_days = EXCLUDED.midstay_interval_days,
                turnover_timer_mins = EXCLUDED.turnover_timer_mins,
                supervisor_inspection_lock = EXCLUDED.supervisor_inspection_lock,
                updated_at = now()
            """.trimIndent(),
            actor.tenantId, propertyId, request.inspectionRequired,
            request.stayoverEnabled, request.stayoverIntervalDays,
            request.turnoverMinutes,
            if (request.inspectionRequired) "required" else "auto_release",
        )
        settings(actor.tenantId, propertyId).also {
            executor.sideEffects(
                actor, propertyId, "housekeeping.settings.updated",
                SETTINGS, propertyId, mapOf("settings" to it), key,
            )
        }
    }

    override fun board(propertyId: UUID, date: LocalDate?): HousekeepingBoardResponse =
        executor.read(propertyId) { actor ->
            val businessDate = date
                ?: propertyOperationsPort.currentBusinessDate(actor.tenantId, propertyId)
            generateStayovers(actor.tenantId, propertyId, businessDate)
            val tasks = jdbcTemplate.query(
                "$TASK_SELECT AND ht.scheduled_date = ? ORDER BY ht.priority DESC, r.room_number",
                ::mapTask, actor.tenantId, propertyId, businessDate,
            )
            HousekeepingBoardResponse(
                propertyId, businessDate, tasks,
                HousekeepingTaskStatus.entries.associateWith { status ->
                    tasks.count { it.status == status }
                },
            )
        }

    override fun createTask(
        propertyId: UUID,
        request: CreateHousekeepingTaskRequest,
    ): HousekeepingTaskResponse = executor.mutate(
        propertyId, "housekeeping.task.create", request, TASKS,
        HousekeepingTaskResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        requireRoom(actor.tenantId, propertyId, request.roomId)
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO housekeeping_tasks (
                id, tenant_id, property_id, room_id, type, status, priority,
                scheduled_date, notes, created_by
            ) VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?)
            """.trimIndent(),
            id, actor.tenantId, propertyId, request.roomId,
            request.type.db(), request.priority, request.scheduledDate,
            request.notes.clean(), actor.tenantUserId,
        )
        requireTask(actor.tenantId, propertyId, id).also {
            executor.sideEffects(
                actor, propertyId, "housekeeping.task.created", TASKS, id,
                mapOf("taskId" to id, "roomId" to request.roomId, "type" to request.type), key,
            )
        }
    }

    override fun assignTask(
        propertyId: UUID,
        taskId: UUID,
        request: AssignHousekeepingTaskRequest,
    ): HousekeepingTaskResponse = transition(
        propertyId, taskId, "assign", request, setOf("pending"),
    ) { actor ->
        jdbcTemplate.update(
            """
            UPDATE housekeeping_tasks SET status = 'assigned', assigned_to = ?, updated_at = now()
            WHERE tenant_id = ? AND property_id = ? AND id = ? AND status = 'pending'
            """.trimIndent(),
            request.userId, actor.tenantId, propertyId, taskId,
        )
    }

    override fun startTask(propertyId: UUID, taskId: UUID): HousekeepingTaskResponse =
        transition(propertyId, taskId, "start", emptyMap<String, String>(), setOf("assigned")) { actor ->
            jdbcTemplate.update(
                """
                UPDATE housekeeping_tasks
                SET status = 'in_progress', started_at = COALESCE(started_at, now()), updated_at = now()
                WHERE tenant_id = ? AND property_id = ? AND id = ?
                  AND status = 'assigned' AND assigned_to = ?
                """.trimIndent(),
                actor.tenantId, propertyId, taskId, actor.tenantUserId,
            )
        }

    override fun completeTask(
        propertyId: UUID,
        taskId: UUID,
        request: CompleteHousekeepingTaskRequest,
    ): HousekeepingTaskResponse = executor.mutate(
        propertyId, "housekeeping.task.complete", mapOf("taskId" to taskId, "request" to request),
        TASKS, HousekeepingTaskResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        val task = requireTask(actor.tenantId, propertyId, taskId, true)
        if (task.status != HousekeepingTaskStatus.IN_PROGRESS || task.assignedTo != actor.tenantUserId) {
            throw HousekeepingConflictException("Only the assigned in-progress task can be completed")
        }
        val inspection = settings(actor.tenantId, propertyId).inspectionRequired
        val next = if (inspection) "awaiting_inspection" else "completed"
        jdbcTemplate.update(
            """
            UPDATE housekeeping_tasks
            SET status = ?, completed_at = now(), completed_by = ?,
                notes = COALESCE(?, notes), updated_at = now()
            WHERE tenant_id = ? AND property_id = ? AND id = ? AND status = 'in_progress'
            """.trimIndent(),
            next, actor.tenantUserId, request.notes.clean(),
            actor.tenantId, propertyId, taskId,
        )
        if (!inspection && task.type.needsVacantClean()) {
            propertyOperationsPort.markRoomVacantClean(
                actor.tenantId, propertyId, task.roomId, "housekeeping_completed", taskId,
                actor.tenantUserId,
            )
        }
        requireTask(actor.tenantId, propertyId, taskId).also {
            executor.sideEffects(
                actor, propertyId, "housekeeping.task.completed", TASKS, taskId,
                mapOf("taskId" to taskId, "status" to it.status), key,
            )
        }
    }

    override fun inspectTask(
        propertyId: UUID,
        taskId: UUID,
        request: InspectHousekeepingTaskRequest,
    ): HousekeepingTaskResponse = executor.mutate(
        propertyId, "housekeeping.task.inspect", mapOf("taskId" to taskId, "request" to request),
        TASKS, HousekeepingTaskResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        val task = requireTask(actor.tenantId, propertyId, taskId, true)
        if (task.status != HousekeepingTaskStatus.AWAITING_INSPECTION) {
            throw HousekeepingConflictException("Task is not awaiting inspection")
        }
        if (task.assignedTo == actor.tenantUserId || task.completedBy == actor.tenantUserId) {
            throw HousekeepingConflictException("Cleaner cannot inspect their own task")
        }
        jdbcTemplate.update(
            """
            INSERT INTO housekeeping_inspections (
                tenant_id, property_id, task_id, inspected_by, result, notes
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            actor.tenantId, propertyId, taskId, actor.tenantUserId,
            if (request.passed) "passed" else "failed", request.notes.clean(),
        )
        val next = if (request.passed) "completed" else "in_progress"
        jdbcTemplate.update(
            """
            UPDATE housekeeping_tasks
            SET status = ?, inspected_by = ?, inspection_notes = ?,
                inspection_completed_at = now(), updated_at = now()
            WHERE tenant_id = ? AND property_id = ? AND id = ?
              AND status = 'awaiting_inspection'
            """.trimIndent(),
            next, actor.tenantUserId, request.notes.clean(),
            actor.tenantId, propertyId, taskId,
        )
        if (request.passed && task.type.needsVacantClean()) {
            propertyOperationsPort.markRoomVacantClean(
                actor.tenantId, propertyId, task.roomId, "housekeeping_inspection", taskId,
                actor.tenantUserId,
            )
        }
        requireTask(actor.tenantId, propertyId, taskId).also {
            executor.sideEffects(
                actor, propertyId, "housekeeping.task.inspected", TASKS, taskId,
                mapOf("taskId" to taskId, "passed" to request.passed), key,
            )
        }
    }

    override fun skipTask(
        propertyId: UUID,
        taskId: UUID,
        request: HousekeepingReasonRequest,
    ): HousekeepingTaskResponse = terminal(propertyId, taskId, "skip", "skipped", request)

    override fun cancelTask(
        propertyId: UUID,
        taskId: UUID,
        request: HousekeepingReasonRequest,
    ): HousekeepingTaskResponse = terminal(propertyId, taskId, "cancel", "cancelled", request)

    override fun listLostAndFound(propertyId: UUID): List<LostAndFoundResponse> =
        executor.read(propertyId) { actor ->
            jdbcTemplate.query(
                "$LOST_SELECT ORDER BY found_at DESC",
                ::mapLost, actor.tenantId, propertyId,
            )
        }

    override fun createLostAndFound(
        propertyId: UUID,
        request: CreateLostAndFoundRequest,
    ): LostAndFoundResponse = executor.mutate(
        propertyId, "housekeeping.lost_found.create", request, LOST,
        LostAndFoundResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        request.roomId?.let { requireRoom(actor.tenantId, propertyId, it) }
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO lost_and_found (
                id, tenant_id, property_id, room_id, description, found_by,
                status, storage_location, updated_by
            ) VALUES (?, ?, ?, ?, ?, ?, 'held', ?, ?)
            """.trimIndent(),
            id, actor.tenantId, propertyId, request.roomId,
            request.description.required("description"), actor.tenantUserId,
            request.storageLocation.required("storageLocation"), actor.tenantUserId,
        )
        custody(actor.tenantId, propertyId, id, null, "held", actor.tenantUserId, null, "Item recorded")
        requireLost(actor.tenantId, propertyId, id).also {
            executor.sideEffects(
                actor, propertyId, "housekeeping.lost_found.recorded", LOST, id,
                mapOf("itemId" to id, "status" to it.status), key,
            )
        }
    }

    override fun transitionLostAndFound(
        propertyId: UUID,
        itemId: UUID,
        target: LostAndFoundStatus,
        request: LostAndFoundTransitionRequest,
    ): LostAndFoundResponse = executor.mutate(
        propertyId, "housekeeping.lost_found.${target.db()}",
        mapOf("itemId" to itemId, "request" to request), LOST,
        LostAndFoundResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        val current = requireLost(actor.tenantId, propertyId, itemId, true)
        val allowed = when (target) {
            LostAndFoundStatus.CLAIMED -> current.status == LostAndFoundStatus.HELD
            LostAndFoundStatus.RETURNED -> current.status in setOf(
                LostAndFoundStatus.HELD, LostAndFoundStatus.CLAIMED,
            )
            LostAndFoundStatus.DISPOSED, LostAndFoundStatus.DONATED ->
                current.status == LostAndFoundStatus.HELD
            LostAndFoundStatus.HELD -> false
        }
        if (!allowed) {
            throw HousekeepingConflictException(
                "Cannot transition ${current.status} item to $target",
            )
        }
        jdbcTemplate.update(
            """
            UPDATE lost_and_found
            SET status = ?, claimed_at = CASE WHEN ? = 'claimed' THEN now() ELSE claimed_at END,
                claimed_by = CASE WHEN ? = 'claimed' THEN ? ELSE claimed_by END,
                disposition_reason = ?, updated_by = ?, updated_at = now()
            WHERE tenant_id = ? AND property_id = ? AND id = ?
            """.trimIndent(),
            target.db(), target.db(), target.db(), actor.tenantUserId,
            request.reason.required("reason"), actor.tenantUserId,
            actor.tenantId, propertyId, itemId,
        )
        custody(
            actor.tenantId, propertyId, itemId, current.status.db(), target.db(),
            actor.tenantUserId, request.claimantDetails.clean(), request.reason.required("reason"),
        )
        requireLost(actor.tenantId, propertyId, itemId).also {
            executor.sideEffects(
                actor, propertyId, "housekeeping.lost_found.${target.db()}", LOST, itemId,
                mapOf("itemId" to itemId, "from" to current.status, "to" to target), key,
            )
        }
    }

    private fun transition(
        propertyId: UUID,
        taskId: UUID,
        action: String,
        payload: Any,
        expected: Set<String>,
        update: (com.mwombeki.peak.shared.context.TenantActor) -> Int,
    ): HousekeepingTaskResponse = executor.mutate(
        propertyId, "housekeeping.task.$action", mapOf("taskId" to taskId, "payload" to payload),
        TASKS, HousekeepingTaskResponse::class.java, { it.id }, { it.copy(replayed = true) },
    ) { actor, key ->
        val task = requireTask(actor.tenantId, propertyId, taskId, true)
        if (task.status.db() !in expected || update(actor) != 1) {
            throw HousekeepingConflictException("Invalid housekeeping task transition")
        }
        requireTask(actor.tenantId, propertyId, taskId).also {
            executor.sideEffects(
                actor, propertyId, "housekeeping.task.$action", TASKS, taskId,
                mapOf("taskId" to taskId, "status" to it.status), key,
            )
        }
    }

    private fun terminal(
        propertyId: UUID,
        taskId: UUID,
        action: String,
        target: String,
        request: HousekeepingReasonRequest,
    ): HousekeepingTaskResponse = transition(
        propertyId, taskId, action, request,
        if (target == "skipped") setOf("pending", "assigned") else
            setOf("pending", "assigned", "in_progress", "awaiting_inspection"),
    ) { actor ->
        jdbcTemplate.update(
            """
            UPDATE housekeeping_tasks SET status = ?, notes = ?, updated_at = now()
            WHERE tenant_id = ? AND property_id = ? AND id = ?
              AND status NOT IN ('completed', 'skipped', 'cancelled')
            """.trimIndent(),
            target, request.reason.required("reason"), actor.tenantId, propertyId, taskId,
        )
    }

    private fun settings(tenantId: UUID, propertyId: UUID): HousekeepingSettingsResponse =
        jdbcTemplate.query(
            """
            SELECT inspection_required, stayover_enabled, midstay_interval_days,
                   turnover_timer_mins
            FROM property_housekeeping_settings
            WHERE tenant_id = ? AND property_id = ?
            """.trimIndent(),
            { rs, _ ->
                HousekeepingSettingsResponse(
                    propertyId, rs.getBoolean("inspection_required"),
                    rs.getBoolean("stayover_enabled"), rs.getInt("midstay_interval_days"),
                    rs.getInt("turnover_timer_mins"),
                )
            },
            tenantId, propertyId,
        ).singleOrNull() ?: HousekeepingSettingsResponse(propertyId, true, true, 3, 45)

    private fun generateStayovers(tenantId: UUID, propertyId: UUID, date: LocalDate) {
        val setting = settings(tenantId, propertyId)
        if (!setting.stayoverEnabled) return
        frontDeskHousekeepingPort.inHouseStaySummaries(tenantId, propertyId, date).forEach { stay ->
            val nights = ChronoUnit.DAYS.between(stay.checkInDate, date)
            if (nights <= 0 || nights % setting.stayoverIntervalDays != 0L) {
                return@forEach
            }
            jdbcTemplate.update(
                """
                INSERT INTO housekeeping_tasks (
                    tenant_id, property_id, room_id, source_stay_id, type,
                    status, scheduled_date, priority
                ) VALUES (?, ?, ?, ?, 'stayover_clean', 'pending', ?, 1)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                tenantId, propertyId, stay.roomId, stay.stayId, date,
            )
        }
    }

    private fun requireRoom(tenantId: UUID, propertyId: UUID, roomId: UUID) {
        val exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM rooms WHERE tenant_id = ? AND property_id = ? AND id = ? AND deleted_at IS NULL)",
            Boolean::class.java, tenantId, propertyId, roomId,
        ) == true
        if (!exists) throw HousekeepingNotFoundException("Room was not found")
    }

    private fun requireTask(
        tenantId: UUID,
        propertyId: UUID,
        taskId: UUID,
        lock: Boolean = false,
    ): HousekeepingTaskResponse = jdbcTemplate.query(
        "$TASK_SELECT AND ht.id = ? ${if (lock) "FOR UPDATE OF ht" else ""}",
        ::mapTask, tenantId, propertyId, taskId,
    ).singleOrNull() ?: throw HousekeepingNotFoundException("Housekeeping task was not found")

    private fun requireLost(
        tenantId: UUID,
        propertyId: UUID,
        itemId: UUID,
        lock: Boolean = false,
    ): LostAndFoundResponse = jdbcTemplate.query(
        "$LOST_SELECT AND id = ? ${if (lock) "FOR UPDATE" else ""}",
        ::mapLost, tenantId, propertyId, itemId,
    ).singleOrNull() ?: throw HousekeepingNotFoundException("Lost-and-found item was not found")

    private fun custody(
        tenantId: UUID,
        propertyId: UUID,
        itemId: UUID,
        from: String?,
        to: String,
        actorId: UUID,
        claimant: String?,
        reason: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO lost_and_found_custody_events (
                tenant_id, property_id, item_id, from_status, to_status,
                actor_id, claimant_details, reason
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            tenantId, propertyId, itemId, from, to, actorId, claimant, reason,
        )
    }

    private fun mapTask(rs: ResultSet, ignored: Int) = HousekeepingTaskResponse(
        id = rs.getObject("id", UUID::class.java),
        propertyId = rs.getObject("property_id", UUID::class.java),
        roomId = rs.getObject("room_id", UUID::class.java),
        sourceStayId = rs.getObject("source_stay_id", UUID::class.java),
        type = HousekeepingTaskType.valueOf(rs.getString("type").uppercase(Locale.ROOT)),
        status = HousekeepingTaskStatus.valueOf(rs.getString("status").uppercase(Locale.ROOT)),
        priority = rs.getInt("priority"),
        scheduledDate = rs.getObject("scheduled_date", LocalDate::class.java),
        assignedTo = rs.getObject("assigned_to", UUID::class.java),
        startedAt = rs.getTimestamp("started_at")?.toInstant(),
        completedAt = rs.getTimestamp("completed_at")?.toInstant(),
        completedBy = rs.getObject("completed_by", UUID::class.java),
        inspectedBy = rs.getObject("inspected_by", UUID::class.java),
        notes = rs.getString("notes"),
    )

    private fun mapLost(rs: ResultSet, ignored: Int) = LostAndFoundResponse(
        id = rs.getObject("id", UUID::class.java),
        propertyId = rs.getObject("property_id", UUID::class.java),
        roomId = rs.getObject("room_id", UUID::class.java),
        description = rs.getString("description"),
        storageLocation = rs.getString("storage_location"),
        status = LostAndFoundStatus.valueOf(rs.getString("status").uppercase(Locale.ROOT)),
        foundAt = rs.getTimestamp("found_at").toInstant(),
        claimedAt = rs.getTimestamp("claimed_at")?.toInstant(),
    )

    private fun Enum<*>.db() = name.lowercase(Locale.ROOT)
    private fun HousekeepingTaskType.needsVacantClean() =
        this in setOf(HousekeepingTaskType.DEPARTURE_CLEAN, HousekeepingTaskType.DEEP_CLEAN)
    private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }
    private fun String.required(name: String) = trim().takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("$name is required")

    private companion object {
        const val TASKS = "housekeeping_tasks"
        const val SETTINGS = "property_housekeeping_settings"
        const val LOST = "lost_and_found"
        const val TASK_SELECT = """
            SELECT ht.id, ht.property_id, ht.room_id, ht.source_stay_id, ht.type,
                   ht.status, ht.priority, ht.scheduled_date, ht.assigned_to,
                   ht.started_at, ht.completed_at, ht.completed_by,
                   ht.inspected_by, ht.notes
            FROM housekeeping_tasks ht
            JOIN rooms r ON r.tenant_id = ht.tenant_id AND r.id = ht.room_id
            WHERE ht.tenant_id = ? AND ht.property_id = ?
        """
        const val LOST_SELECT = """
            SELECT id, property_id, room_id, description, storage_location,
                   status, found_at, claimed_at
            FROM lost_and_found
            WHERE tenant_id = ? AND property_id = ?
        """
    }
}
