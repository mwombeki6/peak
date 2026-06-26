package com.mwombeki.peak.property.internal

import com.mwombeki.peak.audit.api.AuditOutcome
import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.property.api.CreateBuildingRequest
import com.mwombeki.peak.property.api.CreateDepartmentRequest
import com.mwombeki.peak.property.api.CreateFloorRequest
import com.mwombeki.peak.property.api.CreatePropertyRequest
import com.mwombeki.peak.property.api.CreateRevenueCenterRequest
import com.mwombeki.peak.property.api.CreateRoomRequest
import com.mwombeki.peak.property.api.CreateRoomTypeRequest
import com.mwombeki.peak.property.api.CreateTaxRateRequest
import com.mwombeki.peak.property.api.PropertyPort
import com.mwombeki.peak.property.api.PropertyReadinessResponse
import com.mwombeki.peak.property.api.PropertyResponse
import com.mwombeki.peak.property.api.SetBaseRateRequest
import com.mwombeki.peak.property.api.TaxRateResponse
import com.mwombeki.peak.property.api.UpdatePropertyRequest
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PropertyManagementService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val auditPort: AuditPort,
) : PropertyPort {

    @Transactional
    override fun createProperty(request: CreatePropertyRequest): UUID {
        val tenantId = resolveActiveTenantId()
        val propertyId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, location, code, type, status, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, true)
            """.trimIndent(),
            propertyId,
            tenantId,
            request.name,
            request.location,
            request.code,
            request.type,
            PROPERTY_STATUS_ACTIVE,
        )
        enablePropertyManagementModule(tenantId, propertyId)

        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = "property.created",
                resource = AuditResource("property", propertyId),
                after = mapOf("name" to request.name, "type" to request.type),
            ),
        )

        return propertyId
    }

    @Transactional
    override fun updateProperty(propertyId: UUID, request: UpdatePropertyRequest) {
        val tenantId = resolveActiveTenantId()
        requireProperty(tenantId, propertyId)

        val sql = StringBuilder("UPDATE properties SET updated_at = now()")
        val params = mutableListOf<Any>()

        request.name?.let { sql.append(", name = ?"); params.add(it) }
        request.location?.let { sql.append(", location = ?"); params.add(it) }
        request.code?.let { sql.append(", code = ?"); params.add(it) }
        request.type?.let { sql.append(", type = ?"); params.add(it) }

        sql.append(" WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL")
        params.add(propertyId)
        params.add(tenantId)

        ensureUpdated(
            jdbcTemplate.update(sql.toString(), *params.toTypedArray()),
            "Property record not found or access denied.",
        )

        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = "property.updated",
                resource = AuditResource("property", propertyId),
                after = mapOf("name" to request.name, "type" to request.type),
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun getProperty(propertyId: UUID): PropertyResponse? {
        val tenantId = resolveActiveTenantId()
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, name, location, code, type, status, is_active
            FROM properties
            WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
            """.trimIndent(),
            { rs, _ ->
                PropertyResponse(
                    id = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    name = rs.getString("name"),
                    location = rs.getString("location"),
                    code = rs.getString("code"),
                    type = rs.getString("type"),
                    status = rs.getString("status"),
                    isActive = rs.getBoolean("is_active"),
                )
            },
            propertyId,
            tenantId,
        ).firstOrNull()
    }

    @Transactional(readOnly = true)
    override fun listProperties(): List<PropertyResponse> {
        val tenantId = resolveActiveTenantId()
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, name, location, code, type, status, is_active
            FROM properties
            WHERE tenant_id = ? AND deleted_at IS NULL
            ORDER BY name
            """.trimIndent(),
            { rs, _ ->
                PropertyResponse(
                    id = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    name = rs.getString("name"),
                    location = rs.getString("location"),
                    code = rs.getString("code"),
                    type = rs.getString("type"),
                    status = rs.getString("status"),
                    isActive = rs.getBoolean("is_active"),
                )
            },
            tenantId,
        )
    }

    @Transactional
    override fun deleteProperty(propertyId: UUID) {
        val tenantId = resolveActiveTenantId()
        archivePropertyRecord(tenantId, propertyId, "property.deleted")
    }

    @Transactional
    override fun suspendProperty(propertyId: UUID) {
        val tenantId = resolveActiveTenantId()
        ensureUpdated(
            jdbcTemplate.update(
                """
                UPDATE properties
                SET status = ?, is_active = false, updated_at = now()
                WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
                """.trimIndent(),
                PROPERTY_STATUS_SUSPENDED,
                propertyId,
                tenantId,
            ),
            "Property record not found or access denied.",
        )
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = "property.suspended",
                resource = AuditResource("property", propertyId),
            ),
        )
    }

    @Transactional
    override fun archiveProperty(propertyId: UUID) {
        val tenantId = resolveActiveTenantId()
        archivePropertyRecord(tenantId, propertyId, "property.archived")
    }

    @Transactional
    fun createBuilding(propertyId: UUID, request: CreateBuildingRequest): UUID {
        val tenantId = resolveActiveTenantId()
        requireProperty(tenantId, propertyId)
        val buildingId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO buildings (id, tenant_id, property_id, name)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            buildingId,
            tenantId,
            propertyId,
            request.name,
        )
        return buildingId
    }

    @Transactional
    fun createRoom(propertyId: UUID, request: CreateRoomRequest): UUID {
        val tenantId = resolveActiveTenantId()
        requireProperty(tenantId, propertyId)
        requireBuildingBelongsToProperty(tenantId, propertyId, request.buildingId)
        requireFloorBelongsToBuilding(tenantId, request.buildingId, request.floorNumber)
        requireRoomTypeBelongsToProperty(tenantId, propertyId, request.roomTypeId)
        val roomId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO rooms (id, tenant_id, property_id, room_type_id, room_number, floor, status)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            roomId,
            tenantId,
            propertyId,
            request.roomTypeId,
            request.roomNumber,
            request.floorNumber,
            ROOM_STATUS_VACANT_CLEAN,
        )
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
        return roomId
    }

    @Transactional(readOnly = true)
    override fun checkReadiness(propertyId: UUID): PropertyReadinessResponse {
        val tenantId = resolveActiveTenantId()
        val missing = mutableListOf<String>()

        val propertyInfo = requireProperty(tenantId, propertyId)
        if (propertyInfo.status == PROPERTY_STATUS_ARCHIVED) {
            missing.add("Property is archived.")
        }
        if (!propertyInfo.isActive) {
            missing.add("Property is inactive.")
        }

        val buildingCount = count(
            """
            SELECT COUNT(*)
            FROM buildings
            WHERE property_id = ? AND tenant_id = ?
            """.trimIndent(),
            propertyId,
            tenantId,
        )
        if (buildingCount == 0) {
            missing.add("Property must have at least one building configured.")
        }

        val floorCount = count(
            """
            SELECT COUNT(*)
            FROM floors f
            JOIN buildings b ON b.id = f.building_id AND b.tenant_id = f.tenant_id
            WHERE b.property_id = ? AND f.tenant_id = ?
            """.trimIndent(),
            propertyId,
            tenantId,
        )
        if (floorCount == 0) {
            missing.add("Property must have at least one floor configured.")
        }

        val roomTypeCount = count(
            """
            SELECT COUNT(*)
            FROM room_types
            WHERE property_id = ? AND tenant_id = ? AND deleted_at IS NULL
            """.trimIndent(),
            propertyId,
            tenantId,
        )
        if (roomTypeCount == 0) {
            missing.add("Property must have at least one room type configured.")
        }

        val roomCount = count(
            """
            SELECT COUNT(*)
            FROM rooms
            WHERE property_id = ? AND tenant_id = ? AND deleted_at IS NULL
            """.trimIndent(),
            propertyId,
            tenantId,
        )
        if (roomCount == 0) {
            missing.add("Property must have at least one room configured.")
        }

        val revenueCenterCount = count(
            """
            SELECT COUNT(*)
            FROM revenue_centers
            WHERE property_id = ? AND tenant_id = ? AND deleted_at IS NULL
            """.trimIndent(),
            propertyId,
            tenantId,
        )
        if (revenueCenterCount == 0) {
            missing.add("Property must have at least one revenue center configured.")
        }

        val taxConfigured = exists(
            """
            SELECT EXISTS(
                SELECT 1
                FROM tax_rates
                WHERE tenant_id = ? AND is_active = true
            )
            """.trimIndent(),
            tenantId,
        )
        if (!taxConfigured) {
            missing.add("Property lacks standard tax configuration records.")
        }

        val roomTypesWithoutRates = count(
            """
            SELECT COUNT(*)
            FROM room_types
            WHERE property_id = ?
              AND tenant_id = ?
              AND deleted_at IS NULL
              AND base_price <= 0
            """.trimIndent(),
            propertyId,
            tenantId,
        )
        if (roomTypesWithoutRates > 0) {
            missing.add("All room types must have base rates configured.")
        }

        val contactExists = exists(
            """
            SELECT EXISTS(
                SELECT 1
                FROM tenant_contacts
                WHERE tenant_id = ? AND deleted_at IS NULL AND status = 'active'
            )
            """.trimIndent(),
            tenantId,
        )
        if (!contactExists) {
            missing.add("Active business contacts must be present for the tenant.")
        }

        return PropertyReadinessResponse(
            propertyId = propertyId,
            isReady = missing.isEmpty(),
            missingRequirements = missing,
        )
    }

    @Transactional
    override fun activateProperty(propertyId: UUID): PropertyReadinessResponse {
        val tenantId = resolveActiveTenantId()
        val readiness = checkReadiness(propertyId)

        if (!readiness.isReady) {
            auditPort.recordTenantEvent(
                TenantAuditEvent(
                    tenantId = tenantId,
                    action = "property.activation_failed",
                    resource = AuditResource("property", propertyId),
                    outcome = AuditOutcome.FAILURE,
                    after = mapOf("missing" to readiness.missingRequirements),
                ),
            )
            throw IllegalStateException("Cannot activate property: structural setup criteria unmet.")
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
                tenantId,
            ),
            "Property record not found or access denied.",
        )

        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = "property.activated",
                resource = AuditResource("property", propertyId),
            ),
        )

        return readiness
    }

    @Transactional
    fun updateRoomStatus(propertyId: UUID, roomId: UUID, newStatus: String) {
        val tenantId = resolveActiveTenantId()
        val canonicalStatus = canonicalRoomStatus(newStatus)

        ensureUpdated(
            jdbcTemplate.update(
                """
                UPDATE rooms
                SET status = ?, updated_at = now(), last_status_changed_at = now()
                WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                """.trimIndent(),
                canonicalStatus,
                roomId,
                tenantId,
                propertyId,
            ),
            "Room record not found or access denied.",
        )

        jdbcTemplate.update(
            """
            INSERT INTO room_status_log (tenant_id, room_id, status, changed_by)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            tenantId,
            roomId,
            canonicalStatus,
            currentTenantUserIdOrNull(),
        )
    }

    @Transactional
    fun createFloor(propertyId: UUID, request: CreateFloorRequest): UUID {
        val tenantId = resolveActiveTenantId()
        requireProperty(tenantId, propertyId)
        requireBuildingBelongsToProperty(tenantId, propertyId, request.buildingId)
        val floorId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO floors (id, tenant_id, building_id, floor_number)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            floorId,
            tenantId,
            request.buildingId,
            request.floorNumber,
        )
        return floorId
    }

    @Transactional
    fun createRoomType(propertyId: UUID, request: CreateRoomTypeRequest): UUID {
        val tenantId = resolveActiveTenantId()
        requireProperty(tenantId, propertyId)
        val roomTypeId = UUID.randomUUID()
        val occupancy = request.baseCapacity.coerceAtLeast(1)

        jdbcTemplate.update(
            """
            INSERT INTO room_types (id, tenant_id, property_id, name, code, max_adults, max_occupancy)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            roomTypeId,
            tenantId,
            propertyId,
            request.name,
            request.code,
            occupancy,
            occupancy,
        )
        return roomTypeId
    }

    @Transactional
    fun createRevenueCenter(propertyId: UUID, request: CreateRevenueCenterRequest): UUID {
        val tenantId = resolveActiveTenantId()
        requireProperty(tenantId, propertyId)
        val centerId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO revenue_centers (id, tenant_id, property_id, code, name)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            centerId,
            tenantId,
            propertyId,
            request.code,
            request.name,
        )
        return centerId
    }

    @Transactional
    fun createDepartment(propertyId: UUID, request: CreateDepartmentRequest): UUID {
        val tenantId = resolveActiveTenantId()
        requireProperty(tenantId, propertyId)
        val departmentId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO departments (id, tenant_id, property_id, name)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            departmentId,
            tenantId,
            propertyId,
            request.name,
        )
        return departmentId
    }

    @Transactional
    fun setRoomTypeBaseRate(propertyId: UUID, request: SetBaseRateRequest) {
        val tenantId = resolveActiveTenantId()
        requireProperty(tenantId, propertyId)
        ensureUpdated(
            jdbcTemplate.update(
                """
                UPDATE room_types
                SET base_price = ?, updated_at = now()
                WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                """.trimIndent(),
                request.amount,
                request.roomTypeId,
                tenantId,
                propertyId,
            ),
            "Room type record not found or access denied.",
        )
    }

    @Transactional
    override fun createTaxRate(request: CreateTaxRateRequest): UUID {
        val tenantId = resolveActiveTenantId()
        val taxRateId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO tax_rates (id, tenant_id, name, code, rate, tax_type, is_compound, is_inclusive)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            taxRateId,
            tenantId,
            request.name,
            request.code,
            request.rate,
            request.taxType,
            request.isCompound,
            request.isInclusive,
        )
        return taxRateId
    }

    @Transactional(readOnly = true)
    override fun listTaxRates(): List<TaxRateResponse> {
        val tenantId = resolveActiveTenantId()
        return jdbcTemplate.query(
            """
            SELECT id, name, code, rate, tax_type, is_compound, is_inclusive
            FROM tax_rates
            WHERE tenant_id = ? AND is_active = true
            ORDER BY name
            """.trimIndent(),
            { rs, _ ->
                TaxRateResponse(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    code = rs.getString("code"),
                    rate = rs.getDouble("rate"),
                    taxType = rs.getString("tax_type"),
                    isCompound = rs.getBoolean("is_compound"),
                    isInclusive = rs.getBoolean("is_inclusive"),
                )
            },
            tenantId,
        )
    }

    @Transactional
    override fun enableModule(propertyId: UUID, moduleId: String) {
        val tenantId = resolveActiveTenantId()
        requireProperty(tenantId, propertyId)
        jdbcTemplate.update(
            """
            INSERT INTO property_modules (tenant_id, property_id, module_id, is_enabled, is_configured, configured_at)
            VALUES (?, ?, ?, true, true, now())
            ON CONFLICT (tenant_id, property_id, module_id)
            DO UPDATE SET is_enabled = true, is_configured = true, configured_at = now(), updated_at = now()
            """.trimIndent(),
            tenantId,
            propertyId,
            moduleId,
        )
    }

    @Transactional
    override fun disableModule(propertyId: UUID, moduleId: String) {
        val tenantId = resolveActiveTenantId()
        ensureUpdated(
            jdbcTemplate.update(
                """
                UPDATE property_modules
                SET is_enabled = false, updated_at = now()
                WHERE tenant_id = ? AND property_id = ? AND module_id = ?
                """.trimIndent(),
                tenantId,
                propertyId,
                moduleId,
            ),
            "Property module record not found or access denied.",
        )
    }

    @Transactional(readOnly = true)
    override fun listEnabledModules(propertyId: UUID): List<String> {
        val tenantId = resolveActiveTenantId()
        requireProperty(tenantId, propertyId)
        return jdbcTemplate.query(
            """
            SELECT module_id
            FROM property_modules
            WHERE tenant_id = ? AND property_id = ? AND is_enabled = true
            ORDER BY module_id
            """.trimIndent(),
            { rs, _ -> rs.getString("module_id") },
            tenantId,
            propertyId,
        )
    }

    private fun archivePropertyRecord(tenantId: UUID, propertyId: UUID, action: String) {
        ensureUpdated(
            jdbcTemplate.update(
                """
                UPDATE properties
                SET deleted_at = COALESCE(deleted_at, now()),
                    status = ?,
                    is_active = false,
                    updated_at = now()
                WHERE id = ? AND tenant_id = ?
                """.trimIndent(),
                PROPERTY_STATUS_ARCHIVED,
                propertyId,
                tenantId,
            ),
            "Property record not found or access denied.",
        )
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = action,
                resource = AuditResource("property", propertyId),
            ),
        )
    }

    private fun enablePropertyManagementModule(tenantId: UUID, propertyId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured, source, configured_at)
            VALUES (?, ?, true, true, 'system', now())
            ON CONFLICT (tenant_id, module_id)
            DO UPDATE SET is_enabled = true, is_configured = true, updated_at = now()
            """.trimIndent(),
            tenantId,
            PROPERTY_MODULE_ID,
        )
        jdbcTemplate.update(
            """
            INSERT INTO property_modules (tenant_id, property_id, module_id, is_enabled, is_configured, configured_at)
            VALUES (?, ?, ?, true, true, now())
            ON CONFLICT (tenant_id, property_id, module_id)
            DO UPDATE SET is_enabled = true, is_configured = true, configured_at = now(), updated_at = now()
            """.trimIndent(),
            tenantId,
            propertyId,
            PROPERTY_MODULE_ID,
        )
    }

    private fun resolveActiveTenantId(): UUID {
        val context = requestContextHolder.current()
        return when (val identity = context.identity) {
            is RequestIdentity.Tenant -> identity.tenantId
            else -> throw IllegalStateException("Action requires an active tenant identity.")
        }
    }

    private fun currentTenantUserIdOrNull(): UUID? {
        return when (val identity = requestContextHolder.current().identity) {
            is RequestIdentity.Tenant -> identity.tenantUserId
            else -> null
        }
    }

    private fun requireProperty(tenantId: UUID, propertyId: UUID): PropertyState {
        return jdbcTemplate.query(
            """
            SELECT status, is_active
            FROM properties
            WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
            """.trimIndent(),
            { rs, _ ->
                PropertyState(
                    status = rs.getString("status"),
                    isActive = rs.getBoolean("is_active"),
                )
            },
            propertyId,
            tenantId,
        ).firstOrNull()
            ?: throw NoSuchElementException("Property record not found or access denied.")
    }

    private fun requireBuildingBelongsToProperty(tenantId: UUID, propertyId: UUID, buildingId: UUID) {
        if (!exists(
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM buildings
                    WHERE id = ? AND tenant_id = ? AND property_id = ?
                )
                """.trimIndent(),
                buildingId,
                tenantId,
                propertyId,
            )
        ) {
            throw NoSuchElementException("Building record not found or access denied.")
        }
    }

    private fun requireFloorBelongsToBuilding(tenantId: UUID, buildingId: UUID, floorNumber: Int) {
        if (!exists(
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM floors
                    WHERE tenant_id = ? AND building_id = ? AND floor_number = ?
                )
                """.trimIndent(),
                tenantId,
                buildingId,
                floorNumber,
            )
        ) {
            throw NoSuchElementException("Floor record not found or access denied.")
        }
    }

    private fun requireRoomTypeBelongsToProperty(tenantId: UUID, propertyId: UUID, roomTypeId: UUID) {
        if (!exists(
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM room_types
                    WHERE id = ? AND tenant_id = ? AND property_id = ? AND deleted_at IS NULL
                )
                """.trimIndent(),
                roomTypeId,
                tenantId,
                propertyId,
            )
        ) {
            throw NoSuchElementException("Room type record not found or access denied.")
        }
    }

    private fun canonicalRoomStatus(status: String): String {
        val normalized = status.trim().lowercase()
        return ROOM_STATUS_ALIASES[normalized]
            ?: normalized.takeIf { it in CANONICAL_ROOM_STATUSES }
            ?: throw IllegalArgumentException("Invalid room lifecycle status: $status")
    }

    private fun count(sql: String, vararg args: Any): Int {
        return requireNotNull(jdbcTemplate.queryForObject(sql, Int::class.java, *args))
    }

    private fun exists(sql: String, vararg args: Any): Boolean {
        return requireNotNull(jdbcTemplate.queryForObject(sql, Boolean::class.java, *args))
    }

    private fun ensureUpdated(rowsUpdated: Int, message: String) {
        if (rowsUpdated == 0) {
            throw NoSuchElementException(message)
        }
    }

    private data class PropertyState(
        val status: String,
        val isActive: Boolean,
    )

    private companion object {
        private const val PROPERTY_STATUS_ACTIVE = "active"
        private const val PROPERTY_STATUS_SUSPENDED = "suspended"
        private const val PROPERTY_STATUS_ARCHIVED = "archived"
        private const val PROPERTY_MODULE_ID = "property"
        private const val ROOM_STATUS_VACANT_CLEAN = "vacant_clean"

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
