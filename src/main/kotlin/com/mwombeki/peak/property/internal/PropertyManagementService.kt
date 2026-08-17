package com.mwombeki.peak.property.internal

import com.mwombeki.peak.audit.api.AuditOutcome
import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.property.api.BaseRateResponse
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
import com.mwombeki.peak.property.api.FloorResponse
import com.mwombeki.peak.property.api.PropertyActivationBlockedException
import com.mwombeki.peak.property.api.PropertyBootstrapResponse
import com.mwombeki.peak.property.api.PropertyChildMutationReceipt
import com.mwombeki.peak.property.api.PropertyManagementConflictException
import com.mwombeki.peak.property.api.PropertyManagementInProgressException
import com.mwombeki.peak.property.api.PropertyManagementNotFoundException
import com.mwombeki.peak.property.api.PropertyModuleMutationReceipt
import com.mwombeki.peak.property.api.PropertyMutationReceipt
import com.mwombeki.peak.property.api.PropertyOperationsPort
import com.mwombeki.peak.property.api.PropertyCloseSnapshotSummary
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
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.tenantmanagement.api.ConfigureTenantModuleCommand
import com.mwombeki.peak.tenantmanagement.api.TenantModuleConfigurationPort
import com.mwombeki.peak.usermanagement.api.EnsurePropertyAdministratorCommand
import com.mwombeki.peak.usermanagement.api.PropertyAccessBootstrapPort
import io.micrometer.core.instrument.MeterRegistry
import java.sql.ResultSet
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Service
class PropertyManagementService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val propertyAccessBootstrapPort: PropertyAccessBootstrapPort,
    private val tenantModuleConfigurationPort: TenantModuleConfigurationPort,
    private val goLiveEvaluator: PropertyGoLiveEvaluator,
) : PropertyPort, PropertyOperationsPort {

    override fun requireAssignableRoom(
        tenantId: UUID,
        propertyId: UUID,
        roomTypeId: UUID,
        roomId: UUID,
    ) {
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM rooms
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND room_type_id = ?
                  AND id = ?
                  AND status = 'vacant_clean'
                  AND deleted_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            propertyId,
            roomTypeId,
            roomId,
        ) == true
        if (!exists) {
            throw PropertyManagementConflictException(
                "Room is not assignable for check-in",
            )
        }
    }

    override fun markRoomOccupied(
        tenantId: UUID,
        propertyId: UUID,
        roomId: UUID,
    ) {
        val changed = jdbcTemplate.update(
            """
            UPDATE rooms
            SET status = 'occupied',
                last_status_changed_at = now(),
                updated_at = now()
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
              AND status = 'vacant_clean'
              AND deleted_at IS NULL
            """.trimIndent(),
            tenantId,
            propertyId,
            roomId,
        )
        if (changed != 1) {
            throw PropertyManagementConflictException(
                "Room is not available for occupancy",
            )
        }
    }

    override fun markRoomVacantDirty(
        tenantId: UUID,
        propertyId: UUID,
        roomId: UUID,
    ) {
        val changed = jdbcTemplate.update(
            """
            UPDATE rooms
            SET status = 'vacant_dirty',
                last_status_changed_at = now(),
                updated_at = now()
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
              AND status = 'occupied'
              AND deleted_at IS NULL
            """.trimIndent(),
            tenantId,
            propertyId,
            roomId,
        )
        if (changed != 1) {
            throw PropertyManagementConflictException(
                "Room is not currently occupied",
            )
        }
    }

    override fun markRoomVacantClean(
        tenantId: UUID,
        propertyId: UUID,
        roomId: UUID,
        reason: String,
        sourceId: UUID,
        changedBy: UUID?,
    ) {
        changeRoomStatus(
            tenantId, propertyId, roomId, setOf("vacant_dirty"), "vacant_clean",
            reason, "housekeeping_task", sourceId, changedBy,
        )
    }

    override fun placeMaintenanceBlock(
        tenantId: UUID,
        propertyId: UUID,
        roomId: UUID,
        blockStatus: String,
        reason: String,
        sourceId: UUID,
        changedBy: UUID,
    ) {
        require(blockStatus in setOf("out_of_service", "out_of_order")) {
            "Unsupported maintenance block status"
        }
        changeRoomStatus(
            tenantId, propertyId, roomId,
            setOf("vacant_clean", "vacant_dirty"), blockStatus,
            reason, "room_block", sourceId, changedBy,
        )
    }

    override fun releaseMaintenanceBlock(
        tenantId: UUID,
        propertyId: UUID,
        roomId: UUID,
        expectedBlockStatus: String,
        reason: String,
        sourceId: UUID,
        changedBy: UUID,
    ) {
        require(expectedBlockStatus in setOf("out_of_service", "out_of_order")) {
            "Unsupported maintenance block status"
        }
        changeRoomStatus(
            tenantId, propertyId, roomId, setOf(expectedBlockStatus), "vacant_dirty",
            reason, "room_block_release", sourceId, changedBy,
        )
    }

    private fun changeRoomStatus(
        tenantId: UUID,
        propertyId: UUID,
        roomId: UUID,
        expected: Set<String>,
        target: String,
        reason: String,
        sourceType: String,
        sourceId: UUID,
        changedBy: UUID?,
    ) {
        val current = jdbcTemplate.query(
            """
            SELECT status FROM rooms
            WHERE tenant_id = ? AND property_id = ? AND id = ? AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getString("status") },
            tenantId, propertyId, roomId,
        ).singleOrNull() ?: throw PropertyManagementNotFoundException("Room was not found")
        if (current !in expected) {
            throw PropertyManagementConflictException(
                "Room cannot transition from $current to $target",
            )
        }
        jdbcTemplate.update(
            """
            UPDATE rooms SET status = ?, last_status_changed_at = now(), updated_at = now()
            WHERE tenant_id = ? AND property_id = ? AND id = ? AND status = ?
            """.trimIndent(),
            target, tenantId, propertyId, roomId, current,
        )
        jdbcTemplate.update(
            """
            INSERT INTO room_state_transitions (
                tenant_id, property_id, room_id, from_status, to_status,
                reason, source_type, source_id, changed_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            tenantId, propertyId, roomId, current, target,
            reason, sourceType, sourceId, changedBy,
        )
    }

    override fun currentBusinessDate(tenantId: UUID, propertyId: UUID): LocalDate {
        return jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(
                business_date,
                ((now() AT TIME ZONE timezone)::date + business_date_offset)
            )
            FROM properties
            WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
            """.trimIndent(),
            LocalDate::class.java,
            tenantId,
            propertyId,
        ) ?: throw PropertyManagementNotFoundException("Property was not found")
    }

    override fun closeSnapshotSummary(
        tenantId: UUID,
        propertyId: UUID,
    ): PropertyCloseSnapshotSummary {
        return jdbcTemplate.query(
            """
            SELECT COALESCE(tenant.currency_code, 'TZS') AS currency_code,
                   COUNT(r.id) FILTER (WHERE r.deleted_at IS NULL) AS total_rooms
            FROM properties p
            JOIN tenants tenant ON tenant.id = p.tenant_id
            LEFT JOIN rooms r
              ON r.tenant_id = p.tenant_id
             AND r.property_id = p.id
            WHERE p.tenant_id = ?
              AND p.id = ?
              AND p.deleted_at IS NULL
            GROUP BY COALESCE(tenant.currency_code, 'TZS')
            """.trimIndent(),
            { rs, _ ->
                PropertyCloseSnapshotSummary(
                    totalRooms = rs.getInt("total_rooms"),
                    currency = rs.getString("currency_code").trim().uppercase(),
                )
            },
            tenantId,
            propertyId,
        ).singleOrNull() ?: throw PropertyManagementNotFoundException(
            "Property was not found",
        )
    }

    override fun advanceBusinessDate(
        tenantId: UUID,
        propertyId: UUID,
        expectedBusinessDate: LocalDate,
    ): LocalDate {
        return jdbcTemplate.query(
            """
            UPDATE properties
            SET business_date = ?::date + 1,
                updated_at = now()
            WHERE tenant_id = ?
              AND id = ?
              AND deleted_at IS NULL
              AND COALESCE(
                    business_date,
                    ((now() AT TIME ZONE timezone)::date + business_date_offset)
                  ) = ?
            RETURNING business_date
            """.trimIndent(),
            { rs, _ -> rs.getObject("business_date", LocalDate::class.java) },
            expectedBusinessDate,
            tenantId,
            propertyId,
            expectedBusinessDate,
        ).singleOrNull() ?: throw PropertyManagementConflictException(
            "Property business date has already advanced",
        )
    }

    override fun listProperties(): List<PropertyResponse> {
        return read { identity ->
            jdbcTemplate.query(
                """
                SELECT id, tenant_id, name, location, code, type, status, is_active,
                       total_rooms, timezone, business_date_offset
                FROM properties
                WHERE tenant_id = ? AND deleted_at IS NULL
                ORDER BY name
                """.trimIndent(),
                ::mapProperty,
                identity.tenantId,
            )
        }
    }

    override fun getProperty(propertyId: UUID): PropertyResponse? {
        return read { identity ->
            jdbcTemplate.query(
                """
                SELECT id, tenant_id, name, location, code, type, status, is_active,
                       total_rooms, timezone, business_date_offset
                FROM properties
                WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
                """.trimIndent(),
                ::mapProperty,
                propertyId,
                identity.tenantId,
            ).singleOrNull()
        }
    }

    override fun createProperty(request: CreatePropertyRequest): PropertyMutationReceipt {
        return mutate(
            operationType = "property.create",
            requestPayload = request,
            resourceType = "properties",
            replayType = PropertyMutationReceipt::class.java,
        ) { identity, reservationId ->
            val propertyId = insertDraftProperty(identity, request, reservationId)
            PropertyMutationReceipt(
                propertyId = propertyId,
                status = PROPERTY_STATUS_DRAFT,
                changed = true,
                replayed = false,
            )
        }
    }

    override fun bootstrapFirstProperty(request: CreatePropertyRequest): PropertyBootstrapResponse {
        return mutate(
            operationType = "property.bootstrap",
            requestPayload = request,
            resourceType = "properties",
            replayType = PropertyBootstrapResponse::class.java,
        ) { identity, reservationId ->
            val propertyId = insertDraftProperty(identity, request, reservationId)
            goLiveEvaluator.evaluateAndPersist(identity.tenantId, propertyId)
                .toBootstrapResponse(
                    status = PROPERTY_STATUS_DRAFT,
                    changed = true,
                    replayed = false,
                )
        }
    }

    override fun updateProperty(
        propertyId: UUID,
        request: UpdatePropertyRequest,
    ): PropertyMutationReceipt {
        return mutate(
            operationType = "property.update",
            requestPayload = mapOf("propertyId" to propertyId, "request" to request),
            resourceType = "properties",
            replayType = PropertyMutationReceipt::class.java,
        ) { identity, reservationId ->
            requireProperty(identity.tenantId, propertyId, lock = true)
            val rows = try {
                jdbcTemplate.update(
                    """
                    UPDATE properties
                    SET name = COALESCE(?, name),
                        location = COALESCE(?, location),
                        code = COALESCE(?, code),
                        type = COALESCE(?, type),
                        timezone = COALESCE(?, timezone),
                        business_date_offset = COALESCE(?, business_date_offset),
                        updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    request.name?.normalizedRequired("name"),
                    request.location?.trimmedOrNull(),
                    request.code?.normalizedCode(),
                    request.type?.normalizedRequired("type")?.uppercase(),
                    request.timezone?.validatedTimezone(),
                    request.businessDateOffset?.validatedBusinessDateOffset(),
                    propertyId,
                    identity.tenantId,
                )
            } catch (ex: DuplicateKeyException) {
                throw PropertyManagementConflictException("Property code is already in use")
            }
            ensureUpdated(rows, "Property record not found or access denied.")
            val status = propertyStatus(identity.tenantId, propertyId)
            PropertyMutationReceipt(propertyId, status, changed = true, replayed = false)
                .also {
                    recordPropertySideEffects(
                        tenantId = identity.tenantId,
                        propertyId = propertyId,
                        action = "property.updated",
                        eventType = "property.updated",
                        aggregateType = "properties",
                        aggregateId = propertyId,
                        payload = mapOf(
                            "propertyId" to propertyId,
                            "nameChanged" to (request.name != null),
                            "codeChanged" to (request.code != null),
                        ),
                        idempotencyKeyId = reservationId,
                    )
                }
        }
    }

    override fun deleteProperty(propertyId: UUID): PropertyMutationReceipt {
        return archiveOrDeleteProperty(
            propertyId = propertyId,
            operationType = "property.delete",
            action = "property.deleted",
            eventType = "property.deleted",
        )
    }

    override fun suspendProperty(propertyId: UUID): PropertyMutationReceipt {
        return mutate(
            operationType = "property.suspend",
            requestPayload = mapOf("propertyId" to propertyId),
            resourceType = "properties",
            replayType = PropertyMutationReceipt::class.java,
        ) { identity, reservationId ->
            requireProperty(identity.tenantId, propertyId, lock = true)
            ensureUpdated(
                jdbcTemplate.update(
                    """
                    UPDATE properties
                    SET status = ?, is_active = false, updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    PROPERTY_STATUS_SUSPENDED,
                    propertyId,
                    identity.tenantId,
                ),
                "Property record not found or access denied.",
            )
            PropertyMutationReceipt(propertyId, PROPERTY_STATUS_SUSPENDED, changed = true, replayed = false)
                .also { receipt ->
                    recordPropertySideEffects(
                        tenantId = identity.tenantId,
                        propertyId = propertyId,
                        action = "property.suspended",
                        eventType = "property.suspended",
                        aggregateType = "properties",
                        aggregateId = propertyId,
                        payload = mapOf("propertyId" to propertyId, "status" to receipt.status),
                        idempotencyKeyId = reservationId,
                    )
                }
        }
    }

    override fun archiveProperty(propertyId: UUID): PropertyMutationReceipt {
        return archiveOrDeleteProperty(
            propertyId = propertyId,
            operationType = "property.archive",
            action = "property.archived",
            eventType = "property.archived",
        )
    }

    override fun checkReadiness(propertyId: UUID): PropertyReadinessResponse {
        return read { identity ->
            goLiveEvaluator.evaluateAndPersist(identity.tenantId, propertyId).toReadinessResponse()
        }
    }

    override fun getOnboarding(propertyId: UUID): PropertyOnboardingResponse {
        return read { identity ->
            goLiveEvaluator.evaluateAndPersist(identity.tenantId, propertyId).toOnboardingResponse()
        }
    }

    override fun activateProperty(propertyId: UUID): PropertyReadinessResponse {
        return mutate(
            operationType = "property.activate",
            requestPayload = mapOf("propertyId" to propertyId),
            resourceType = "properties",
            replayType = PropertyReadinessResponse::class.java,
        ) { identity, reservationId ->
            val readiness = goLiveEvaluator.evaluateAndPersist(identity.tenantId, propertyId)
            if (!readiness.isReady) {
                auditPort.recordTenantEvent(
                    TenantAuditEvent(
                        tenantId = identity.tenantId,
                        action = "property.activation_failed",
                        resource = AuditResource("property", propertyId),
                        outcome = AuditOutcome.FAILURE,
                        after = mapOf(
                            "missing" to readiness.blockers.map { it.code },
                            "nextAction" to readiness.nextAction?.step,
                            "collectionEnabled" to readiness.collectionEnabled,
                        ),
                    ),
                )
                throw PropertyActivationBlockedException(
                    readiness.nextAction?.why
                        ?: "Cannot activate property until readiness requirements are complete.",
                    nextAction = readiness.nextAction,
                    blockers = readiness.blockers,
                    operatorBlocker = readiness.operatorBlocker,
                )
            }

            ensureUpdated(
                jdbcTemplate.update(
                    """
                    UPDATE properties
                    SET status = ?, is_active = true, updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    PROPERTY_STATUS_ACTIVE,
                    propertyId,
                    identity.tenantId,
                ),
                "Property record not found or access denied.",
            )
            recordPropertySideEffects(
                tenantId = identity.tenantId,
                propertyId = propertyId,
                action = "property.activated",
                eventType = "property.activated",
                aggregateType = "properties",
                aggregateId = propertyId,
                payload = mapOf("propertyId" to propertyId, "status" to PROPERTY_STATUS_ACTIVE),
                idempotencyKeyId = reservationId,
            )
            goLiveEvaluator.evaluateAndPersist(identity.tenantId, propertyId).toReadinessResponse()
        }
    }

    override fun createBuilding(
        propertyId: UUID,
        request: CreateBuildingRequest,
    ): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.building.create",
            requestPayload = request,
            resourceType = BUILDINGS,
            eventType = "property.building.created",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            val buildingId = UUID.randomUUID()
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO buildings (id, tenant_id, property_id, name, description)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                    buildingId,
                    identity.tenantId,
                    propertyId,
                    request.name.normalizedRequired("name"),
                    request.description?.trimmedOrNull(),
                )
            } catch (ex: DuplicateKeyException) {
                throw PropertyManagementConflictException("Building name is already in use for this property")
            }
            buildingId
        }
    }

    override fun listBuildings(propertyId: UUID): List<BuildingResponse> {
        return readProperty(propertyId) { identity ->
            jdbcTemplate.query(
                """
                SELECT id, property_id, name, description
                FROM buildings
                WHERE tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                ORDER BY name
                """.trimIndent(),
                ::mapBuilding,
                identity.tenantId,
                propertyId,
            )
        }
    }

    override fun getBuilding(propertyId: UUID, buildingId: UUID): BuildingResponse? {
        return readProperty(propertyId) { identity ->
            jdbcTemplate.query(
                """
                SELECT id, property_id, name, description
                FROM buildings
                WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                """.trimIndent(),
                ::mapBuilding,
                buildingId,
                identity.tenantId,
                propertyId,
            ).singleOrNull()
        }
    }

    override fun updateBuilding(
        propertyId: UUID,
        buildingId: UUID,
        request: UpdateBuildingRequest,
    ): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.building.update",
            requestPayload = mapOf("buildingId" to buildingId, "request" to request),
            resourceType = BUILDINGS,
            resourceId = buildingId,
            eventType = "property.building.updated",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            val rows = try {
                jdbcTemplate.update(
                    """
                    UPDATE buildings
                    SET name = COALESCE(?, name),
                        description = COALESCE(?, description),
                        updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    request.name?.normalizedRequired("name"),
                    request.description?.trimmedOrNull(),
                    buildingId,
                    identity.tenantId,
                    propertyId,
                )
            } catch (ex: DuplicateKeyException) {
                throw PropertyManagementConflictException("Building name is already in use for this property")
            }
            ensureUpdated(rows, "Building record not found or access denied.")
            buildingId
        }
    }

    override fun deleteBuilding(propertyId: UUID, buildingId: UUID): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.building.delete",
            requestPayload = mapOf("buildingId" to buildingId),
            resourceType = BUILDINGS,
            resourceId = buildingId,
            eventType = "property.building.deleted",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            requireNoRows(
                """
                SELECT COUNT(*)
                FROM floors
                WHERE tenant_id = ? AND building_id = ? AND deleted_at IS NULL
                """.trimIndent(),
                "Building cannot be deleted while active floors exist.",
                identity.tenantId,
                buildingId,
            )
            ensureUpdated(
                jdbcTemplate.update(
                    """
                    UPDATE buildings
                    SET deleted_at = COALESCE(deleted_at, now()), updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    buildingId,
                    identity.tenantId,
                    propertyId,
                ),
                "Building record not found or access denied.",
            )
            buildingId
        }
    }

    override fun createFloor(propertyId: UUID, request: CreateFloorRequest): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.floor.create",
            requestPayload = request,
            resourceType = FLOORS,
            eventType = "property.floor.created",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            requireTenantCapacity(identity.tenantId, "limit.rooms")
            requireBuildingBelongsToProperty(identity.tenantId, propertyId, request.buildingId)
            val floorId = UUID.randomUUID()
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO floors (id, tenant_id, building_id, floor_number, name, capacity)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    floorId,
                    identity.tenantId,
                    request.buildingId,
                    request.floorNumber.requirePositive("floorNumber"),
                    request.name?.trimmedOrNull(),
                    request.capacity.requireNonNegative("capacity"),
                )
            } catch (ex: DuplicateKeyException) {
                throw PropertyManagementConflictException("Floor number is already in use for this building")
            }
            floorId
        }
    }

    override fun listFloors(propertyId: UUID): List<FloorResponse> {
        return readProperty(propertyId) { identity ->
            jdbcTemplate.query(
                """
                SELECT f.id, b.property_id, f.building_id, f.floor_number, f.name, f.capacity
                FROM floors f
                JOIN buildings b ON b.id = f.building_id AND b.tenant_id = f.tenant_id
                WHERE f.tenant_id = ?
                  AND b.property_id = ?
                  AND f.deleted_at IS NULL
                  AND b.deleted_at IS NULL
                ORDER BY f.floor_number, f.name NULLS LAST
                """.trimIndent(),
                ::mapFloor,
                identity.tenantId,
                propertyId,
            )
        }
    }

    override fun getFloor(propertyId: UUID, floorId: UUID): FloorResponse? {
        return readProperty(propertyId) { identity ->
            jdbcTemplate.query(
                """
                SELECT f.id, b.property_id, f.building_id, f.floor_number, f.name, f.capacity
                FROM floors f
                JOIN buildings b ON b.id = f.building_id AND b.tenant_id = f.tenant_id
                WHERE f.id = ?
                  AND f.tenant_id = ?
                  AND b.property_id = ?
                  AND f.deleted_at IS NULL
                  AND b.deleted_at IS NULL
                """.trimIndent(),
                ::mapFloor,
                floorId,
                identity.tenantId,
                propertyId,
            ).singleOrNull()
        }
    }

    override fun updateFloor(
        propertyId: UUID,
        floorId: UUID,
        request: UpdateFloorRequest,
    ): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.floor.update",
            requestPayload = mapOf("floorId" to floorId, "request" to request),
            resourceType = FLOORS,
            resourceId = floorId,
            eventType = "property.floor.updated",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            requireFloorBelongsToProperty(identity.tenantId, propertyId, floorId)
            val rows = try {
                jdbcTemplate.update(
                    """
                    UPDATE floors
                    SET floor_number = COALESCE(?, floor_number),
                        name = COALESCE(?, name),
                        capacity = COALESCE(?, capacity),
                        updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    request.floorNumber?.requirePositive("floorNumber"),
                    request.name?.trimmedOrNull(),
                    request.capacity?.requireNonNegative("capacity"),
                    floorId,
                    identity.tenantId,
                )
            } catch (ex: DuplicateKeyException) {
                throw PropertyManagementConflictException("Floor number is already in use for this building")
            }
            ensureUpdated(rows, "Floor record not found or access denied.")
            floorId
        }
    }

    override fun deleteFloor(propertyId: UUID, floorId: UUID): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.floor.delete",
            requestPayload = mapOf("floorId" to floorId),
            resourceType = FLOORS,
            resourceId = floorId,
            eventType = "property.floor.deleted",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            val floor = requireFloorSnapshot(identity.tenantId, propertyId, floorId)
            requireNoRows(
                """
                SELECT COUNT(*)
                FROM rooms
                WHERE tenant_id = ? AND property_id = ? AND floor = ? AND deleted_at IS NULL
                """.trimIndent(),
                "Floor cannot be deleted while active rooms exist.",
                identity.tenantId,
                propertyId,
                floor.floorNumber,
            )
            ensureUpdated(
                jdbcTemplate.update(
                    """
                    UPDATE floors
                    SET deleted_at = COALESCE(deleted_at, now()), updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    floorId,
                    identity.tenantId,
                ),
                "Floor record not found or access denied.",
            )
            floorId
        }
    }

    override fun createRoomType(
        propertyId: UUID,
        request: CreateRoomTypeRequest,
    ): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.room_type.create",
            requestPayload = request,
            resourceType = ROOM_TYPES,
            eventType = "property.room_type.created",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            val roomTypeId = UUID.randomUUID()
            val maxAdults = request.maxAdults.requirePositive("maxAdults")
            val maxChildren = request.maxChildren.requireNonNegative("maxChildren")
            val maxOccupancy = (request.maxOccupancy ?: (maxAdults + maxChildren)).coerceAtLeast(maxAdults)
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO room_types (
                        id, tenant_id, property_id, name, code, description, base_price,
                        max_adults, max_children, max_occupancy
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    roomTypeId,
                    identity.tenantId,
                    propertyId,
                    request.name.normalizedRequired("name"),
                    request.code.normalizedCode(),
                    request.description?.trimmedOrNull(),
                    request.basePrice.requireNonNegative("basePrice"),
                    maxAdults,
                    maxChildren,
                    maxOccupancy,
                )
            } catch (ex: DuplicateKeyException) {
                throw PropertyManagementConflictException("Room type code is already in use for this property")
            }
            roomTypeId
        }
    }

    override fun listRoomTypes(propertyId: UUID): List<RoomTypeResponse> {
        return readProperty(propertyId) { identity ->
            jdbcTemplate.query(
                """
                SELECT id, property_id, name, code, description, base_price,
                       max_adults, max_children, max_occupancy, is_active
                FROM room_types
                WHERE tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                ORDER BY name
                """.trimIndent(),
                ::mapRoomType,
                identity.tenantId,
                propertyId,
            )
        }
    }

    override fun getRoomType(propertyId: UUID, roomTypeId: UUID): RoomTypeResponse? {
        return readProperty(propertyId) { identity ->
            jdbcTemplate.query(
                """
                SELECT id, property_id, name, code, description, base_price,
                       max_adults, max_children, max_occupancy, is_active
                FROM room_types
                WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                """.trimIndent(),
                ::mapRoomType,
                roomTypeId,
                identity.tenantId,
                propertyId,
            ).singleOrNull()
        }
    }

    override fun updateRoomType(
        propertyId: UUID,
        roomTypeId: UUID,
        request: UpdateRoomTypeRequest,
    ): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.room_type.update",
            requestPayload = mapOf("roomTypeId" to roomTypeId, "request" to request),
            resourceType = ROOM_TYPES,
            resourceId = roomTypeId,
            eventType = "property.room_type.updated",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            requireRoomTypeBelongsToProperty(identity.tenantId, propertyId, roomTypeId)
            val maxAdults = request.maxAdults?.requirePositive("maxAdults")
            val maxChildren = request.maxChildren?.requireNonNegative("maxChildren")
            val maxOccupancy = request.maxOccupancy?.requirePositive("maxOccupancy")
            val rows = try {
                jdbcTemplate.update(
                    """
                    UPDATE room_types
                    SET name = COALESCE(?, name),
                        code = COALESCE(?, code),
                        description = COALESCE(?, description),
                        base_price = COALESCE(?, base_price),
                        max_adults = COALESCE(?, max_adults),
                        max_children = COALESCE(?, max_children),
                        max_occupancy = COALESCE(?, max_occupancy),
                        is_active = COALESCE(?, is_active),
                        updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    request.name?.normalizedRequired("name"),
                    request.code?.normalizedCode(),
                    request.description?.trimmedOrNull(),
                    request.basePrice?.requireNonNegative("basePrice"),
                    maxAdults,
                    maxChildren,
                    maxOccupancy,
                    request.isActive,
                    roomTypeId,
                    identity.tenantId,
                    propertyId,
                )
            } catch (ex: DuplicateKeyException) {
                throw PropertyManagementConflictException("Room type code is already in use for this property")
            }
            ensureUpdated(rows, "Room type record not found or access denied.")
            roomTypeId
        }
    }

    override fun deleteRoomType(propertyId: UUID, roomTypeId: UUID): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.room_type.delete",
            requestPayload = mapOf("roomTypeId" to roomTypeId),
            resourceType = ROOM_TYPES,
            resourceId = roomTypeId,
            eventType = "property.room_type.deleted",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            requireNoRows(
                """
                SELECT COUNT(*)
                FROM rooms
                WHERE tenant_id = ? AND property_id = ? AND room_type_id = ? AND deleted_at IS NULL
                """.trimIndent(),
                "Room type cannot be deleted while active rooms exist.",
                identity.tenantId,
                propertyId,
                roomTypeId,
            )
            ensureUpdated(
                jdbcTemplate.update(
                    """
                    UPDATE room_types
                    SET deleted_at = COALESCE(deleted_at, now()),
                        is_active = false,
                        updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    roomTypeId,
                    identity.tenantId,
                    propertyId,
                ),
                "Room type record not found or access denied.",
            )
            roomTypeId
        }
    }

    override fun createRoom(propertyId: UUID, request: CreateRoomRequest): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.room.create",
            requestPayload = request,
            resourceType = ROOMS,
            eventType = "property.room.created",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            requireBuildingBelongsToProperty(identity.tenantId, propertyId, request.buildingId)
            requireFloorBelongsToBuilding(identity.tenantId, request.buildingId, request.floorNumber)
            requireRoomTypeBelongsToProperty(identity.tenantId, propertyId, request.roomTypeId)
            val roomId = UUID.randomUUID()
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO rooms (
                        id, tenant_id, property_id, room_type_id, room_number,
                        floor, status, is_smoking, is_accessible, notes
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    roomId,
                    identity.tenantId,
                    propertyId,
                    request.roomTypeId,
                    request.roomNumber.normalizedRequired("roomNumber"),
                    request.floorNumber.requirePositive("floorNumber"),
                    ROOM_STATUS_VACANT_CLEAN,
                    request.isSmoking,
                    request.isAccessible,
                    request.notes?.trimmedOrNull(),
                )
            } catch (ex: DuplicateKeyException) {
                throw PropertyManagementConflictException("Room number is already in use for this property")
            }
            refreshPropertyRoomCount(identity.tenantId, propertyId)
            roomId
        }
    }

    override fun listRooms(propertyId: UUID): List<RoomResponse> {
        return readProperty(propertyId) { identity ->
            jdbcTemplate.query(
                """
                SELECT id, property_id, room_type_id, room_number, floor, status, is_smoking, is_accessible
                FROM rooms
                WHERE tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                ORDER BY room_number
                """.trimIndent(),
                ::mapRoom,
                identity.tenantId,
                propertyId,
            )
        }
    }

    override fun getRoom(propertyId: UUID, roomId: UUID): RoomResponse? {
        return readProperty(propertyId) { identity ->
            jdbcTemplate.query(
                """
                SELECT id, property_id, room_type_id, room_number, floor, status, is_smoking, is_accessible
                FROM rooms
                WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                """.trimIndent(),
                ::mapRoom,
                roomId,
                identity.tenantId,
                propertyId,
            ).singleOrNull()
        }
    }

    override fun updateRoom(
        propertyId: UUID,
        roomId: UUID,
        request: UpdateRoomRequest,
    ): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.room.update",
            requestPayload = mapOf("roomId" to roomId, "request" to request),
            resourceType = ROOMS,
            resourceId = roomId,
            eventType = "property.room.updated",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            request.roomTypeId?.let { requireRoomTypeBelongsToProperty(identity.tenantId, propertyId, it) }
            val rows = try {
                jdbcTemplate.update(
                    """
                    UPDATE rooms
                    SET room_type_id = COALESCE(?, room_type_id),
                        room_number = COALESCE(?, room_number),
                        floor = COALESCE(?, floor),
                        is_smoking = COALESCE(?, is_smoking),
                        is_accessible = COALESCE(?, is_accessible),
                        notes = COALESCE(?, notes),
                        updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    request.roomTypeId,
                    request.roomNumber?.normalizedRequired("roomNumber"),
                    request.floorNumber?.requirePositive("floorNumber"),
                    request.isSmoking,
                    request.isAccessible,
                    request.notes?.trimmedOrNull(),
                    roomId,
                    identity.tenantId,
                    propertyId,
                )
            } catch (ex: DuplicateKeyException) {
                throw PropertyManagementConflictException("Room number is already in use for this property")
            }
            ensureUpdated(rows, "Room record not found or access denied.")
            roomId
        }
    }

    override fun deleteRoom(propertyId: UUID, roomId: UUID): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.room.delete",
            requestPayload = mapOf("roomId" to roomId),
            resourceType = ROOMS,
            resourceId = roomId,
            eventType = "property.room.deleted",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            ensureUpdated(
                jdbcTemplate.update(
                    """
                    UPDATE rooms
                    SET deleted_at = COALESCE(deleted_at, now()), updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    roomId,
                    identity.tenantId,
                    propertyId,
                ),
                "Room record not found or access denied.",
            )
            refreshPropertyRoomCount(identity.tenantId, propertyId)
            roomId
        }
    }

    override fun updateRoomStatus(
        propertyId: UUID,
        roomId: UUID,
        request: UpdateRoomStatusRequest,
    ): RoomStatusMutationReceipt {
        return mutate(
            operationType = "property.room.status",
            requestPayload = mapOf("propertyId" to propertyId, "roomId" to roomId, "status" to request.status),
            resourceType = ROOMS,
            replayType = RoomStatusMutationReceipt::class.java,
        ) { identity, reservationId ->
            requireProperty(identity.tenantId, propertyId, lock = false)
            val canonicalStatus = canonicalRoomStatus(request.status)
            ensureUpdated(
                jdbcTemplate.update(
                    """
                    UPDATE rooms
                    SET status = ?, updated_at = now(), last_status_changed_at = now()
                    WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    canonicalStatus,
                    roomId,
                    identity.tenantId,
                    propertyId,
                ),
                "Room record not found or access denied.",
            )
            jdbcTemplate.update(
                """
                INSERT INTO room_status_log (tenant_id, room_id, status, changed_by)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                identity.tenantId,
                roomId,
                canonicalStatus,
                identity.tenantUserId,
            )
            RoomStatusMutationReceipt(propertyId, roomId, canonicalStatus, changed = true, replayed = false)
                .also { receipt ->
                    recordPropertySideEffects(
                        tenantId = identity.tenantId,
                        propertyId = propertyId,
                        action = "property.room.status_changed",
                        eventType = "property.room.status_changed",
                        aggregateType = ROOMS,
                        aggregateId = roomId,
                        payload = mapOf(
                            "propertyId" to propertyId,
                            "roomId" to roomId,
                            "status" to receipt.status,
                        ),
                        idempotencyKeyId = reservationId,
                    )
                }
        }
    }

    override fun createRevenueCenter(
        propertyId: UUID,
        request: CreateRevenueCenterRequest,
    ): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.revenue_center.create",
            requestPayload = request,
            resourceType = REVENUE_CENTERS,
            eventType = "property.revenue_center.created",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            val centerId = UUID.randomUUID()
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO revenue_centers (
                        id, tenant_id, property_id, code, name, center_type,
                        is_rooms_revenue, is_active, display_order
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, true, ?)
                    """.trimIndent(),
                    centerId,
                    identity.tenantId,
                    propertyId,
                    request.code.normalizedCode(),
                    request.name.normalizedRequired("name"),
                    request.centerType.normalizedRevenueCenterType(),
                    request.isRoomsRevenue,
                    request.displayOrder.requireNonNegative("displayOrder"),
                )
            } catch (ex: DuplicateKeyException) {
                throw PropertyManagementConflictException("Revenue center code is already in use for this property")
            }
            centerId
        }
    }

    override fun listRevenueCenters(propertyId: UUID): List<RevenueCenterResponse> {
        return readProperty(propertyId) { identity ->
            jdbcTemplate.query(
                """
                SELECT id, property_id, name, code, center_type, is_rooms_revenue, is_active, display_order
                FROM revenue_centers
                WHERE tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                ORDER BY display_order, name
                """.trimIndent(),
                ::mapRevenueCenter,
                identity.tenantId,
                propertyId,
            )
        }
    }

    override fun getRevenueCenter(propertyId: UUID, revenueCenterId: UUID): RevenueCenterResponse? {
        return readProperty(propertyId) { identity ->
            jdbcTemplate.query(
                """
                SELECT id, property_id, name, code, center_type, is_rooms_revenue, is_active, display_order
                FROM revenue_centers
                WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                """.trimIndent(),
                ::mapRevenueCenter,
                revenueCenterId,
                identity.tenantId,
                propertyId,
            ).singleOrNull()
        }
    }

    override fun updateRevenueCenter(
        propertyId: UUID,
        revenueCenterId: UUID,
        request: UpdateRevenueCenterRequest,
    ): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.revenue_center.update",
            requestPayload = mapOf("revenueCenterId" to revenueCenterId, "request" to request),
            resourceType = REVENUE_CENTERS,
            resourceId = revenueCenterId,
            eventType = "property.revenue_center.updated",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            val rows = try {
                jdbcTemplate.update(
                    """
                    UPDATE revenue_centers
                    SET name = COALESCE(?, name),
                        code = COALESCE(?, code),
                        center_type = COALESCE(?, center_type),
                        is_rooms_revenue = COALESCE(?, is_rooms_revenue),
                        is_active = COALESCE(?, is_active),
                        display_order = COALESCE(?, display_order),
                        updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    request.name?.normalizedRequired("name"),
                    request.code?.normalizedCode(),
                    request.centerType?.normalizedRevenueCenterType(),
                    request.isRoomsRevenue,
                    request.isActive,
                    request.displayOrder?.requireNonNegative("displayOrder"),
                    revenueCenterId,
                    identity.tenantId,
                    propertyId,
                )
            } catch (ex: DuplicateKeyException) {
                throw PropertyManagementConflictException("Revenue center code is already in use for this property")
            }
            ensureUpdated(rows, "Revenue center record not found or access denied.")
            revenueCenterId
        }
    }

    override fun deleteRevenueCenter(
        propertyId: UUID,
        revenueCenterId: UUID,
    ): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.revenue_center.delete",
            requestPayload = mapOf("revenueCenterId" to revenueCenterId),
            resourceType = REVENUE_CENTERS,
            resourceId = revenueCenterId,
            eventType = "property.revenue_center.deleted",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            ensureUpdated(
                jdbcTemplate.update(
                    """
                    UPDATE revenue_centers
                    SET deleted_at = COALESCE(deleted_at, now()),
                        is_active = false,
                        updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    revenueCenterId,
                    identity.tenantId,
                    propertyId,
                ),
                "Revenue center record not found or access denied.",
            )
            revenueCenterId
        }
    }

    override fun createDepartment(
        propertyId: UUID,
        request: CreateDepartmentRequest,
    ): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.department.create",
            requestPayload = request,
            resourceType = DEPARTMENTS,
            eventType = "property.department.created",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            val departmentId = UUID.randomUUID()
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO departments (id, tenant_id, property_id, name, code)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                    departmentId,
                    identity.tenantId,
                    propertyId,
                    request.name.normalizedRequired("name"),
                    request.code.normalizedCode(),
                )
            } catch (ex: DuplicateKeyException) {
                throw PropertyManagementConflictException("Department name or code is already in use")
            }
            departmentId
        }
    }

    override fun listDepartments(propertyId: UUID): List<DepartmentResponse> {
        return readProperty(propertyId) { identity ->
            jdbcTemplate.query(
                """
                SELECT id, property_id, name, code
                FROM departments
                WHERE tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                ORDER BY name
                """.trimIndent(),
                ::mapDepartment,
                identity.tenantId,
                propertyId,
            )
        }
    }

    override fun getDepartment(propertyId: UUID, departmentId: UUID): DepartmentResponse? {
        return readProperty(propertyId) { identity ->
            jdbcTemplate.query(
                """
                SELECT id, property_id, name, code
                FROM departments
                WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                """.trimIndent(),
                ::mapDepartment,
                departmentId,
                identity.tenantId,
                propertyId,
            ).singleOrNull()
        }
    }

    override fun updateDepartment(
        propertyId: UUID,
        departmentId: UUID,
        request: UpdateDepartmentRequest,
    ): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.department.update",
            requestPayload = mapOf("departmentId" to departmentId, "request" to request),
            resourceType = DEPARTMENTS,
            resourceId = departmentId,
            eventType = "property.department.updated",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            val rows = try {
                jdbcTemplate.update(
                    """
                    UPDATE departments
                    SET name = COALESCE(?, name),
                        code = COALESCE(?, code),
                        updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    request.name?.normalizedRequired("name"),
                    request.code?.normalizedCode(),
                    departmentId,
                    identity.tenantId,
                    propertyId,
                )
            } catch (ex: DuplicateKeyException) {
                throw PropertyManagementConflictException("Department name or code is already in use")
            }
            ensureUpdated(rows, "Department record not found or access denied.")
            departmentId
        }
    }

    override fun deleteDepartment(
        propertyId: UUID,
        departmentId: UUID,
    ): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.department.delete",
            requestPayload = mapOf("departmentId" to departmentId),
            resourceType = DEPARTMENTS,
            resourceId = departmentId,
            eventType = "property.department.deleted",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            ensureUpdated(
                jdbcTemplate.update(
                    """
                    UPDATE departments
                    SET deleted_at = COALESCE(deleted_at, now()), updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    departmentId,
                    identity.tenantId,
                    propertyId,
                ),
                "Department record not found or access denied.",
            )
            departmentId
        }
    }

    override fun setRoomTypeBaseRate(
        propertyId: UUID,
        request: SetBaseRateRequest,
    ): PropertyChildMutationReceipt {
        return childMutation(
            propertyId = propertyId,
            operationType = "property.room_type.base_rate",
            requestPayload = request,
            resourceType = ROOM_TYPES,
            resourceId = request.roomTypeId,
            eventType = "property.room_type.base_rate_set",
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity ->
            request.currency.normalizedCurrency()
            ensureUpdated(
                jdbcTemplate.update(
                    """
                    UPDATE room_types
                    SET base_price = ?, updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    request.amount.requirePositive("amount"),
                    request.roomTypeId,
                    identity.tenantId,
                    propertyId,
                ),
                "Room type record not found or access denied.",
            )
            request.roomTypeId
        }
    }

    /**
     * A room type that has never had a rate set reads back at its `base_price` of 0, and is
     * listed rather than filtered out. The onboarding wizard's question is "which room types
     * still need a price", so the unpriced ones are the rows it most needs to see.
     */
    override fun listRoomTypeBaseRates(propertyId: UUID): List<BaseRateResponse> {
        return readProperty(propertyId) { identity ->
            jdbcTemplate.query(
                """
                SELECT id, property_id, name, code, base_price, max_occupancy, is_active
                FROM room_types
                WHERE tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                ORDER BY name, id
                """.trimIndent(),
                ::mapBaseRate,
                identity.tenantId,
                propertyId,
            )
        }
    }

    override fun createTaxRate(request: CreateTaxRateRequest): PropertyChildMutationReceipt {
        return mutate(
            operationType = "property.tax_rate.create",
            requestPayload = request,
            resourceType = TAX_RATES,
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity, reservationId ->
            val taxRateId = UUID.randomUUID()
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO tax_rates (id, tenant_id, name, code, rate, tax_type, is_compound, is_inclusive)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    taxRateId,
                    identity.tenantId,
                    request.name.normalizedRequired("name"),
                    request.code.normalizedCode(),
                    request.rate.requireRate(),
                    request.taxType.normalizedTaxType(),
                    request.isCompound,
                    request.isInclusive,
                )
            } catch (ex: DuplicateKeyException) {
                throw PropertyManagementConflictException("Tax rate code is already in use")
            }
            PropertyChildMutationReceipt(
                propertyId = UUID_ZERO,
                resourceType = TAX_RATES,
                resourceId = taxRateId,
                changed = true,
                replayed = false,
            ).also {
                recordPropertySideEffects(
                    tenantId = identity.tenantId,
                    propertyId = null,
                    action = "property.tax_rate.created",
                    eventType = "property.tax_rate.created",
                    aggregateType = TAX_RATES,
                    aggregateId = taxRateId,
                    payload = mapOf("taxRateId" to taxRateId, "code" to request.code.normalizedCode()),
                    idempotencyKeyId = reservationId,
                )
            }
        }
    }

    override fun listTaxRates(): List<TaxRateResponse> {
        return read { identity ->
            jdbcTemplate.query(
                """
                SELECT id, name, code, rate, tax_type, is_compound, is_inclusive, is_active
                FROM tax_rates
                WHERE tenant_id = ?
                ORDER BY name
                """.trimIndent(),
                ::mapTaxRate,
                identity.tenantId,
            )
        }
    }

    override fun getTaxRate(taxRateId: UUID): TaxRateResponse? {
        return read { identity ->
            jdbcTemplate.query(
                """
                SELECT id, name, code, rate, tax_type, is_compound, is_inclusive, is_active
                FROM tax_rates
                WHERE id = ? AND tenant_id = ?
                """.trimIndent(),
                ::mapTaxRate,
                taxRateId,
                identity.tenantId,
            ).singleOrNull()
        }
    }

    override fun updateTaxRate(
        taxRateId: UUID,
        request: UpdateTaxRateRequest,
    ): PropertyChildMutationReceipt {
        return mutate(
            operationType = "property.tax_rate.update",
            requestPayload = mapOf("taxRateId" to taxRateId, "request" to request),
            resourceType = TAX_RATES,
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity, reservationId ->
            val rows = try {
                jdbcTemplate.update(
                    """
                    UPDATE tax_rates
                    SET name = COALESCE(?, name),
                        code = COALESCE(?, code),
                        rate = COALESCE(?, rate),
                        tax_type = COALESCE(?, tax_type),
                        is_compound = COALESCE(?, is_compound),
                        is_inclusive = COALESCE(?, is_inclusive),
                        is_active = COALESCE(?, is_active),
                        updated_at = now()
                    WHERE id = ? AND tenant_id = ?
                    """.trimIndent(),
                    request.name?.normalizedRequired("name"),
                    request.code?.normalizedCode(),
                    request.rate?.requireRate(),
                    request.taxType?.normalizedTaxType(),
                    request.isCompound,
                    request.isInclusive,
                    request.isActive,
                    taxRateId,
                    identity.tenantId,
                )
            } catch (ex: DuplicateKeyException) {
                throw PropertyManagementConflictException("Tax rate code is already in use")
            }
            ensureUpdated(rows, "Tax rate record not found or access denied.")
            PropertyChildMutationReceipt(
                propertyId = UUID_ZERO,
                resourceType = TAX_RATES,
                resourceId = taxRateId,
                changed = true,
                replayed = false,
            ).also {
                recordPropertySideEffects(
                    tenantId = identity.tenantId,
                    propertyId = null,
                    action = "property.tax_rate.updated",
                    eventType = "property.tax_rate.updated",
                    aggregateType = TAX_RATES,
                    aggregateId = taxRateId,
                    payload = mapOf("taxRateId" to taxRateId),
                    idempotencyKeyId = reservationId,
                )
            }
        }
    }

    override fun deleteTaxRate(taxRateId: UUID): PropertyChildMutationReceipt {
        return mutate(
            operationType = "property.tax_rate.delete",
            requestPayload = mapOf("taxRateId" to taxRateId),
            resourceType = TAX_RATES,
            replayType = PropertyChildMutationReceipt::class.java,
        ) { identity, reservationId ->
            ensureUpdated(
                jdbcTemplate.update(
                    """
                    UPDATE tax_rates
                    SET is_active = false, updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND is_active = true
                    """.trimIndent(),
                    taxRateId,
                    identity.tenantId,
                ),
                "Tax rate record not found or access denied.",
            )
            PropertyChildMutationReceipt(
                propertyId = UUID_ZERO,
                resourceType = TAX_RATES,
                resourceId = taxRateId,
                changed = true,
                replayed = false,
            ).also {
                recordPropertySideEffects(
                    tenantId = identity.tenantId,
                    propertyId = null,
                    action = "property.tax_rate.deleted",
                    eventType = "property.tax_rate.deleted",
                    aggregateType = TAX_RATES,
                    aggregateId = taxRateId,
                    payload = mapOf("taxRateId" to taxRateId),
                    idempotencyKeyId = reservationId,
                )
            }
        }
    }

    override fun enableModule(
        propertyId: UUID,
        moduleId: String,
    ): PropertyModuleMutationReceipt {
        return mutateModule(propertyId, moduleId, enabled = true)
    }

    override fun disableModule(
        propertyId: UUID,
        moduleId: String,
    ): PropertyModuleMutationReceipt {
        val normalizedModuleId = moduleId.normalizedModuleId()
        require(normalizedModuleId != PROPERTY_MODULE_ID) {
            "The core property module cannot be disabled through property setup APIs."
        }
        return mutateModule(propertyId, normalizedModuleId, enabled = false)
    }

    override fun listEnabledModules(propertyId: UUID): List<String> {
        return readProperty(propertyId) { identity ->
            jdbcTemplate.query(
                """
                SELECT module_id
                FROM property_modules
                WHERE tenant_id = ? AND property_id = ? AND is_enabled = true
                ORDER BY module_id
                """.trimIndent(),
                { rs, _ -> rs.getString("module_id") },
                identity.tenantId,
                propertyId,
            )
        }
    }

    private fun archiveOrDeleteProperty(
        propertyId: UUID,
        operationType: String,
        action: String,
        eventType: String,
    ): PropertyMutationReceipt {
        return mutate(
            operationType = operationType,
            requestPayload = mapOf("propertyId" to propertyId),
            resourceType = "properties",
            replayType = PropertyMutationReceipt::class.java,
        ) { identity, reservationId ->
            requireProperty(identity.tenantId, propertyId, lock = true)
            ensureUpdated(
                jdbcTemplate.update(
                    """
                    UPDATE properties
                    SET deleted_at = COALESCE(deleted_at, now()),
                        status = ?,
                        is_active = false,
                        updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
                    """.trimIndent(),
                    PROPERTY_STATUS_ARCHIVED,
                    propertyId,
                    identity.tenantId,
                ),
                "Property record not found or access denied.",
            )
            PropertyMutationReceipt(propertyId, PROPERTY_STATUS_ARCHIVED, changed = true, replayed = false)
                .also { receipt ->
                    recordPropertySideEffects(
                        tenantId = identity.tenantId,
                        propertyId = propertyId,
                        action = action,
                        eventType = eventType,
                        aggregateType = "properties",
                        aggregateId = propertyId,
                        payload = mapOf("propertyId" to propertyId, "status" to receipt.status),
                        idempotencyKeyId = reservationId,
                    )
                }
        }
    }

    private fun mutateModule(
        propertyId: UUID,
        moduleId: String,
        enabled: Boolean,
    ): PropertyModuleMutationReceipt {
        val normalizedModuleId = moduleId.normalizedModuleId()
        return mutate(
            operationType = if (enabled) "property.module.enable" else "property.module.disable",
            requestPayload = mapOf("propertyId" to propertyId, "moduleId" to normalizedModuleId),
            resourceType = "property_modules",
            replayType = PropertyModuleMutationReceipt::class.java,
        ) { identity, reservationId ->
            requireProperty(identity.tenantId, propertyId, lock = false)
            requireActivePropertyModule(normalizedModuleId)
            if (enabled) {
                requireTenantModuleEnabled(identity.tenantId, normalizedModuleId)
            }

            val changed = upsertPropertyModule(identity.tenantId, propertyId, normalizedModuleId, enabled)
            PropertyModuleMutationReceipt(
                propertyId = propertyId,
                moduleId = normalizedModuleId,
                enabled = enabled,
                changed = changed,
                replayed = false,
            ).also {
                if (changed) {
                    recordPropertySideEffects(
                        tenantId = identity.tenantId,
                        propertyId = propertyId,
                        action = if (enabled) "property.module.enabled" else "property.module.disabled",
                        eventType = if (enabled) "property.module.enabled" else "property.module.disabled",
                        aggregateType = "property_modules",
                        aggregateId = propertyId,
                        payload = mapOf(
                            "propertyId" to propertyId,
                            "moduleId" to normalizedModuleId,
                            "enabled" to enabled,
                        ),
                        idempotencyKeyId = reservationId,
                    )
                }
            }
        }
    }

    private fun <T : Any> childMutation(
        propertyId: UUID,
        operationType: String,
        requestPayload: Any,
        resourceType: String,
        eventType: String,
        replayType: Class<T>,
        resourceId: UUID? = null,
        block: (TenantIdentity) -> UUID,
    ): T {
        return mutate(
            operationType = operationType,
            requestPayload = mapOf("propertyId" to propertyId, "request" to requestPayload),
            resourceType = resourceType,
            replayType = replayType,
        ) { identity, reservationId ->
            requireProperty(identity.tenantId, propertyId, lock = false)
            val resolvedResourceId = block(identity)
            val receipt = PropertyChildMutationReceipt(
                propertyId = propertyId,
                resourceType = resourceType,
                resourceId = resourceId ?: resolvedResourceId,
                changed = true,
                replayed = false,
            )
            recordPropertySideEffects(
                tenantId = identity.tenantId,
                propertyId = propertyId,
                action = eventType,
                eventType = eventType,
                aggregateType = resourceType,
                aggregateId = receipt.resourceId,
                payload = mapOf(
                    "propertyId" to propertyId,
                    "resourceType" to resourceType,
                    "resourceId" to receipt.resourceId,
                ),
                idempotencyKeyId = reservationId,
            )
            @Suppress("UNCHECKED_CAST")
            receipt as T
        }
    }

    private fun <T : Any> mutate(
        operationType: String,
        requestPayload: Any,
        resourceType: String,
        replayType: Class<T>,
        block: (TenantIdentity, UUID) -> T,
    ): T {
        return requireNotNull(
            transactionTemplate.execute {
                val identity = bindTenantContext()
                val reservation = idempotencyPort.reserve(
                    IdempotencyCommand(
                        operationType = operationType,
                        requestPayload = requestPayload,
                        resourceType = resourceType,
                    ),
                )

                when (reservation) {
                    is IdempotencyReservation.Started -> {
                        try {
                            val receipt = block(identity, reservation.recordId)
                            idempotencyPort.markSucceeded(
                                recordId = reservation.recordId,
                                responseCode = 200,
                                responseBody = receipt,
                                resourceId = resourceId(receipt),
                            )
                            meterRegistry.counter(
                                "peak.property.command",
                                "operation", operationType,
                                "result", "succeeded",
                            ).increment()
                            receipt
                        } catch (ex: DataIntegrityViolationException) {
                            throw PropertyManagementConflictException(ex.publicDatabaseMessage())
                        }
                    }

                    is IdempotencyReservation.Replay -> {
                        if (reservation.responseBody.isNullOrBlank()) {
                            throw PropertyManagementConflictException(
                                "Property command replay does not contain a stored response body",
                            )
                        }
                        objectMapper.readValue(reservation.responseBody, replayType).withReplayFlag()
                    }

                    is IdempotencyReservation.InProgress -> {
                        meterRegistry.counter(
                            "peak.property.command",
                            "operation", operationType,
                            "result", "in_progress",
                        ).increment()
                        throw PropertyManagementInProgressException(
                            "Property command is already being processed for this idempotency key",
                        )
                    }

                    is IdempotencyReservation.Conflict -> {
                        meterRegistry.counter(
                            "peak.property.command",
                            "operation", operationType,
                            "result", "conflict",
                        ).increment()
                        throw PropertyManagementConflictException(
                            "Idempotency key was already used for a different property request",
                        )
                    }
                }
            },
        )
    }

    private fun <T> read(block: (TenantIdentity) -> T): T {
        return transactionTemplate.execute {
            block(bindTenantContext())
        }
    }

    private fun <T> readProperty(
        propertyId: UUID,
        block: (TenantIdentity) -> T,
    ): T {
        return read { identity ->
            requireProperty(identity.tenantId, propertyId, lock = false)
            block(identity)
        }
    }

    private fun bindTenantContext(): TenantIdentity {
        val identity = requestContextHolder.current().identity
        require(identity is RequestIdentity.Tenant) {
            "Tenant user identity is required"
        }
        databaseSessionContext.bind(identity)
        requireTenantUsable(identity.tenantId)
        return TenantIdentity(identity.tenantId, identity.tenantUserId)
    }

    private fun requireTenantUsable(tenantId: UUID) {
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM tenants
                WHERE id = ?
                  AND status IN ('trial', 'active')
                  AND deleted_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
        ) == true
        if (!exists) {
            throw PropertyManagementNotFoundException("Active tenant was not found")
        }
    }

    private fun requireProperty(
        tenantId: UUID,
        propertyId: UUID,
        lock: Boolean,
    ): PropertyState {
        val lockClause = if (lock) " FOR UPDATE" else ""
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, name, status, is_active
            FROM properties
            WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL$lockClause
            """.trimIndent(),
            { rs, _ ->
                PropertyState(
                    id = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    name = rs.getString("name"),
                    status = rs.getString("status"),
                    isActive = rs.getBoolean("is_active"),
                )
            },
            propertyId,
            tenantId,
        ).singleOrNull()
            ?: throw PropertyManagementNotFoundException("Property record not found or access denied")
    }

    private fun requireBuildingBelongsToProperty(
        tenantId: UUID,
        propertyId: UUID,
        buildingId: UUID,
    ) {
        if (!exists(
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM buildings
                    WHERE id = ?
                      AND tenant_id = ?
                      AND property_id = ?
                      AND deleted_at IS NULL
                )
                """.trimIndent(),
                buildingId,
                tenantId,
                propertyId,
            )
        ) {
            throw PropertyManagementNotFoundException("Building record not found or access denied")
        }
    }

    private fun requireFloorBelongsToBuilding(
        tenantId: UUID,
        buildingId: UUID,
        floorNumber: Int,
    ) {
        if (!exists(
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM floors
                    WHERE tenant_id = ?
                      AND building_id = ?
                      AND floor_number = ?
                      AND deleted_at IS NULL
                )
                """.trimIndent(),
                tenantId,
                buildingId,
                floorNumber,
            )
        ) {
            throw PropertyManagementNotFoundException("Floor record not found or access denied")
        }
    }

    private fun requireFloorBelongsToProperty(
        tenantId: UUID,
        propertyId: UUID,
        floorId: UUID,
    ) {
        requireFloorSnapshot(tenantId, propertyId, floorId)
    }

    private fun requireFloorSnapshot(
        tenantId: UUID,
        propertyId: UUID,
        floorId: UUID,
    ): FloorSnapshot {
        return jdbcTemplate.query(
            """
            SELECT f.id, f.building_id, f.floor_number
            FROM floors f
            JOIN buildings b ON b.id = f.building_id AND b.tenant_id = f.tenant_id
            WHERE f.id = ?
              AND f.tenant_id = ?
              AND b.property_id = ?
              AND f.deleted_at IS NULL
              AND b.deleted_at IS NULL
            """.trimIndent(),
            { rs, _ ->
                FloorSnapshot(
                    id = rs.getObject("id", UUID::class.java),
                    buildingId = rs.getObject("building_id", UUID::class.java),
                    floorNumber = rs.getInt("floor_number"),
                )
            },
            floorId,
            tenantId,
            propertyId,
        ).singleOrNull()
            ?: throw PropertyManagementNotFoundException("Floor record not found or access denied")
    }

    private fun requireRoomTypeBelongsToProperty(
        tenantId: UUID,
        propertyId: UUID,
        roomTypeId: UUID,
    ) {
        if (!exists(
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM room_types
                    WHERE id = ?
                      AND tenant_id = ?
                      AND property_id = ?
                      AND deleted_at IS NULL
                      AND is_active = true
                )
                """.trimIndent(),
                roomTypeId,
                tenantId,
                propertyId,
            )
        ) {
            throw PropertyManagementNotFoundException("Active room type record not found or access denied")
        }
    }

    private fun insertDraftProperty(
        identity: TenantIdentity,
        request: CreatePropertyRequest,
        reservationId: UUID,
    ): UUID {
        requireTenantCapacity(identity.tenantId, "limit.properties")
        val propertyId = UUID.randomUUID()
        try {
            jdbcTemplate.update(
                """
                INSERT INTO properties (
                    id, tenant_id, name, location, code, type, status,
                    is_active, timezone, business_date_offset, business_date
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, false, ?, ?,
                        ((now() AT TIME ZONE ?)::date + ?))
                """.trimIndent(),
                propertyId,
                identity.tenantId,
                request.name.normalizedRequired("name"),
                request.location?.trimmedOrNull(),
                request.code?.normalizedCode(),
                request.type.normalizedRequired("type").uppercase(),
                PROPERTY_STATUS_DRAFT,
                request.timezone.validatedTimezone(),
                request.businessDateOffset.validatedBusinessDateOffset(),
                request.timezone.validatedTimezone(),
                request.businessDateOffset.validatedBusinessDateOffset(),
            )
        } catch (ex: DuplicateKeyException) {
            throw PropertyManagementConflictException("Property code is already in use")
        }

        tenantModuleConfigurationPort.enableConfiguredModule(
            ConfigureTenantModuleCommand(
                tenantId = identity.tenantId,
                moduleId = PROPERTY_MODULE_ID,
                source = "system",
            ),
        )
        upsertPropertyModule(identity.tenantId, propertyId, PROPERTY_MODULE_ID, enabled = true)
        propertyAccessBootstrapPort.ensurePropertyAdministrator(
            EnsurePropertyAdministratorCommand(
                tenantId = identity.tenantId,
                propertyId = propertyId,
                tenantUserId = identity.tenantUserId,
            ),
        )
        goLiveEvaluator.ensureWorkflow(identity.tenantId, propertyId)
        recordPropertySideEffects(
            tenantId = identity.tenantId,
            propertyId = propertyId,
            action = "property.created",
            eventType = "property.created",
            aggregateType = "properties",
            aggregateId = propertyId,
            payload = mapOf(
                "propertyId" to propertyId,
                "name" to request.name.normalizedRequired("name"),
                "code" to request.code?.normalizedCode(),
                "status" to PROPERTY_STATUS_DRAFT,
            ),
            idempotencyKeyId = reservationId,
        )
        return propertyId
    }

    private fun upsertPropertyModule(
        tenantId: UUID,
        propertyId: UUID,
        moduleId: String,
        enabled: Boolean,
    ): Boolean {
        val previous = jdbcTemplate.query(
            """
            SELECT is_enabled
            FROM property_modules
            WHERE tenant_id = ? AND property_id = ? AND module_id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getBoolean("is_enabled") },
            tenantId,
            propertyId,
            moduleId,
        ).singleOrNull()

        jdbcTemplate.update(
            """
            INSERT INTO property_modules (
                tenant_id, property_id, module_id, is_enabled, is_configured, configured_at
            )
            VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN now() ELSE NULL END)
            ON CONFLICT ON CONSTRAINT property_modules_tenant_id_property_id_module_id_key
            DO UPDATE SET is_enabled = EXCLUDED.is_enabled,
                          is_configured = property_modules.is_configured OR EXCLUDED.is_configured,
                          configured_at = CASE
                              WHEN EXCLUDED.is_enabled THEN COALESCE(property_modules.configured_at, now())
                              ELSE property_modules.configured_at
                          END,
                          updated_at = now()
            """.trimIndent(),
            tenantId,
            propertyId,
            moduleId,
            enabled,
            enabled,
            enabled,
        )

        return previous != enabled
    }

    private fun requireActivePropertyModule(moduleId: String) {
        if (!exists(
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM module_catalog
                    WHERE module_id = ?
                      AND launch_status = 'active'
                      AND access_scope IN ('property', 'both')
                )
                """.trimIndent(),
                moduleId,
            )
        ) {
            throw PropertyManagementNotFoundException("Property module was not found or is not active")
        }
    }

    private fun requireTenantModuleEnabled(
        tenantId: UUID,
        moduleId: String,
    ) {
        if (!tenantModuleEnabled(tenantId, moduleId)) {
            throw PropertyManagementConflictException(
                "Tenant module '$moduleId' must be enabled before enabling it for a property",
            )
        }
    }

    private fun tenantModuleEnabled(
        tenantId: UUID,
        moduleId: String,
    ): Boolean {
        return tenantModuleConfigurationPort.isEnabled(tenantId, moduleId)
    }

    private fun refreshPropertyRoomCount(
        tenantId: UUID,
        propertyId: UUID,
    ) {
        jdbcTemplate.update(
            """
            UPDATE properties
            SET total_rooms = (
                SELECT COUNT(*)::smallint
                FROM rooms
                WHERE tenant_id = ? AND property_id = ? AND deleted_at IS NULL
            ),
            updated_at = now()
            WHERE id = ? AND tenant_id = ?
            """.trimIndent(),
            tenantId,
            propertyId,
            propertyId,
            tenantId,
        )
    }

    private fun recordPropertySideEffects(
        tenantId: UUID,
        propertyId: UUID?,
        action: String,
        eventType: String,
        aggregateType: String,
        aggregateId: UUID,
        payload: Map<String, Any?>,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = action,
                resource = AuditResource(aggregateType, aggregateId),
                after = payload,
            ),
        )

        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = aggregateType,
                aggregateId = aggregateId,
                tenantId = tenantId,
                propertyId = propertyId,
                eventType = eventType,
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                idempotencyKeyId = idempotencyKeyId,
                priority = 4,
            ),
        )

    }

    private fun propertyStatus(
        tenantId: UUID,
        propertyId: UUID,
    ): String {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM properties WHERE tenant_id = ? AND id = ?",
            String::class.java,
            tenantId,
            propertyId,
        ) ?: throw PropertyManagementNotFoundException("Property record not found or access denied")
    }

    private fun canonicalRoomStatus(status: String): String {
        val normalized = status.trim().lowercase()
        return ROOM_STATUS_ALIASES[normalized]
            ?: normalized.takeIf { it in CANONICAL_ROOM_STATUSES }
            ?: throw IllegalArgumentException("Invalid room lifecycle status: $status")
    }

    private fun count(sql: String, vararg args: Any?): Int {
        return requireNotNull(jdbcTemplate.queryForObject(sql, Int::class.java, *args))
    }

    private fun exists(sql: String, vararg args: Any?): Boolean {
        return requireNotNull(jdbcTemplate.queryForObject(sql, Boolean::class.java, *args))
    }

    private fun ensureUpdated(rowsUpdated: Int, message: String) {
        if (rowsUpdated == 0) {
            throw PropertyManagementNotFoundException(message)
        }
    }

    private fun requireNoRows(sql: String, message: String, vararg args: Any?) {
        if (count(sql, *args) > 0) {
            throw PropertyManagementConflictException(message)
        }
    }

    private fun resourceId(receipt: Any): UUID? {
        return when (receipt) {
            is PropertyMutationReceipt -> receipt.propertyId
            is PropertyChildMutationReceipt -> receipt.resourceId.takeUnless { it == UUID_ZERO }
            is PropertyModuleMutationReceipt -> receipt.propertyId
            is RoomStatusMutationReceipt -> receipt.roomId
            is PropertyReadinessResponse -> receipt.propertyId
            is PropertyBootstrapResponse -> receipt.propertyId
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> T.withReplayFlag(): T {
        return when (this) {
            is PropertyMutationReceipt -> copy(replayed = true) as T
            is PropertyChildMutationReceipt -> copy(replayed = true) as T
            is PropertyModuleMutationReceipt -> copy(replayed = true) as T
            is RoomStatusMutationReceipt -> copy(replayed = true) as T
            is PropertyBootstrapResponse -> copy(replayed = true) as T
            else -> this
        }
    }

    private fun String.normalizedRequired(field: String): String {
        return trim().takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$field is required")
    }

    private fun String.trimmedOrNull(): String? {
        return trim().takeIf { it.isNotEmpty() }
    }

    private fun String.normalizedCode(): String {
        return normalizedRequired("code").uppercase()
    }

    private fun String.validatedTimezone(): String {
        val normalized = normalizedRequired("timezone")
        try {
            ZoneId.of(normalized)
        } catch (ex: Exception) {
            throw IllegalArgumentException("timezone must be a valid IANA timezone")
        }
        return normalized
    }

    private fun Int.validatedBusinessDateOffset(): Int {
        require(this in -1..1) {
            "businessDateOffset must be between -1 and 1"
        }
        return this
    }

    private fun String.normalizedModuleId(): String {
        return trim().lowercase().takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("moduleId is required")
    }

    private fun String.normalizedCurrency(): String {
        val currency = normalizedRequired("currency").uppercase()
        require(currency.length == 3) {
            "currency must be a 3-letter ISO code"
        }
        return currency
    }

    private fun String.normalizedRevenueCenterType(): String {
        val type = trim().lowercase().takeIf { it in REVENUE_CENTER_TYPES }
            ?: throw IllegalArgumentException("Invalid revenue center type: $this")
        return type
    }

    private fun String.normalizedTaxType(): String {
        val type = trim().lowercase().takeIf { it in TAX_TYPES }
            ?: throw IllegalArgumentException("Invalid tax type: $this")
        return type
    }

    private fun Int.requirePositive(field: String): Int {
        require(this > 0) {
            "$field must be positive"
        }
        return this
    }

    private fun Int.requireNonNegative(field: String): Int {
        require(this >= 0) {
            "$field must not be negative"
        }
        return this
    }

    private fun Double.requirePositive(field: String): Double {
        require(this > 0.0) {
            "$field must be positive"
        }
        return this
    }

    private fun Double.requireNonNegative(field: String): Double {
        require(this >= 0.0) {
            "$field must not be negative"
        }
        return this
    }

    private fun Double.requireRate(): Double {
        require(this in 0.0..1.0) {
            "rate must be between 0 and 1"
        }
        return this
    }

    private fun DataIntegrityViolationException.publicDatabaseMessage(): String {
        return "Property request conflicts with existing configuration data"
    }

    private fun mapProperty(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): PropertyResponse {
        return PropertyResponse(
            id = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            name = rs.getString("name"),
            location = rs.getString("location"),
            code = rs.getString("code"),
            type = rs.getString("type"),
            status = rs.getString("status"),
            isActive = rs.getBoolean("is_active"),
            totalRooms = rs.getInt("total_rooms"),
            timezone = rs.getString("timezone"),
            businessDateOffset = rs.getInt("business_date_offset"),
        )
    }

    private fun mapBuilding(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): BuildingResponse {
        return BuildingResponse(
            id = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            name = rs.getString("name"),
            description = rs.getString("description"),
        )
    }

    private fun mapFloor(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): FloorResponse {
        return FloorResponse(
            id = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            buildingId = rs.getObject("building_id", UUID::class.java),
            floorNumber = rs.getInt("floor_number"),
            name = rs.getString("name"),
            capacity = rs.getInt("capacity"),
        )
    }

    private fun mapRoomType(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): RoomTypeResponse {
        return RoomTypeResponse(
            id = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            name = rs.getString("name"),
            code = rs.getString("code"),
            description = rs.getString("description"),
            basePrice = rs.getDouble("base_price"),
            maxAdults = rs.getInt("max_adults"),
            maxChildren = rs.getInt("max_children"),
            maxOccupancy = rs.getInt("max_occupancy"),
            isActive = rs.getBoolean("is_active"),
        )
    }

    private fun mapBaseRate(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): BaseRateResponse {
        return BaseRateResponse(
            roomTypeId = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            roomTypeName = rs.getString("name"),
            roomTypeCode = rs.getString("code"),
            basePrice = rs.getDouble("base_price"),
            maxOccupancy = rs.getInt("max_occupancy"),
            isActive = rs.getBoolean("is_active"),
        )
    }

    private fun mapRoom(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): RoomResponse {
        val floor = rs.getObject("floor")
        return RoomResponse(
            id = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            roomTypeId = rs.getObject("room_type_id", UUID::class.java),
            roomNumber = rs.getString("room_number"),
            floorNumber = (floor as? Number)?.toInt(),
            status = rs.getString("status"),
            isSmoking = rs.getBoolean("is_smoking"),
            isAccessible = rs.getBoolean("is_accessible"),
        )
    }

    private fun mapRevenueCenter(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ): RevenueCenterResponse {
        return RevenueCenterResponse(
            id = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            name = rs.getString("name"),
            code = rs.getString("code"),
            centerType = rs.getString("center_type"),
            isRoomsRevenue = rs.getBoolean("is_rooms_revenue"),
            isActive = rs.getBoolean("is_active"),
            displayOrder = rs.getInt("display_order"),
        )
    }

    private fun mapDepartment(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): DepartmentResponse {
        return DepartmentResponse(
            id = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            name = rs.getString("name"),
            code = rs.getString("code"),
        )
    }

    private fun mapTaxRate(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): TaxRateResponse {
        return TaxRateResponse(
            id = rs.getObject("id", UUID::class.java),
            name = rs.getString("name"),
            code = rs.getString("code"),
            rate = rs.getDouble("rate"),
            taxType = rs.getString("tax_type"),
            isCompound = rs.getBoolean("is_compound"),
            isInclusive = rs.getBoolean("is_inclusive"),
            isActive = rs.getBoolean("is_active"),
        )
    }

    private fun requireTenantCapacity(tenantId: UUID, entitlementCode: String) {
        jdbcTemplate.queryForList(
            "SELECT assert_tenant_capacity(?, ?)",
            tenantId,
            entitlementCode,
        )
    }

    private data class TenantIdentity(
        val tenantId: UUID,
        val tenantUserId: UUID,
    )

    private data class PropertyState(
        val id: UUID,
        val tenantId: UUID,
        val name: String,
        val status: String,
        val isActive: Boolean,
    )

    private data class FloorSnapshot(
        val id: UUID,
        val buildingId: UUID,
        val floorNumber: Int,
    )

    private companion object {
        private const val PROPERTY_STATUS_DRAFT = "draft"
        private const val PROPERTY_STATUS_ACTIVE = "active"
        private const val PROPERTY_STATUS_SUSPENDED = "suspended"
        private const val PROPERTY_STATUS_ARCHIVED = "archived"
        private const val PROPERTY_MODULE_ID = "property"

        private const val BUILDINGS = "buildings"
        private const val FLOORS = "floors"
        private const val ROOM_TYPES = "room_types"
        private const val ROOMS = "rooms"
        private const val REVENUE_CENTERS = "revenue_centers"
        private const val DEPARTMENTS = "departments"
        private const val TAX_RATES = "tax_rates"
        private const val ROOM_STATUS_VACANT_CLEAN = "vacant_clean"

        private val UUID_ZERO = UUID.fromString("00000000-0000-0000-0000-000000000000")

        private val REVENUE_CENTER_TYPES = setOf(
            "rooms",
            "restaurant",
            "bar",
            "spa",
            "banquet",
            "events",
            "other",
        )

        private val TAX_TYPES = setOf(
            "vat",
            "levy",
            "service_charge",
            "tourism_levy",
            "exempt",
            "other",
        )

        private val CANONICAL_ROOM_STATUSES = setOf(
            "vacant_clean",
            "vacant_dirty",
            "occupied",
            "maintenance",
            "out_of_order",
            "blocked",
        )

        private val ROOM_STATUS_ALIASES = mapOf(
            "available" to "vacant_clean",
            "clean" to "vacant_clean",
            "dirty" to "vacant_dirty",
            "suspended" to "blocked",
            "out-of-order" to "out_of_order",
            "outoforder" to "out_of_order",
        )
    }
}
