package com.mwombeki.peak.property.internal

import com.mwombeki.peak.audit.api.AuditOutcome
import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.property.api.*
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

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
            VALUES (?, ?, ?, ?, ?, ?, 'draft', true)
            """.trimIndent(),
            propertyId,
            tenantId,
            request.name,
            request.location,
            request.code,
            request.type
        )
        
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = "property.created",
                resource = AuditResource("property", propertyId),
                after = mapOf("name" to request.name, "type" to request.type)
            )
        )
        
        return propertyId
    }

    @Transactional
    override fun updateProperty(propertyId: UUID, request: UpdatePropertyRequest) {
        val tenantId = resolveActiveTenantId()
        
        val sql = StringBuilder("UPDATE properties SET updated_at = now()")
        val params = mutableListOf<Any>()

        request.name?.let { sql.append(", name = ?"); params.add(it) }
        request.location?.let { sql.append(", location = ?"); params.add(it) }
        request.code?.let { sql.append(", code = ?"); params.add(it) }
        request.type?.let { sql.append(", type = ?"); params.add(it) }

        sql.append(" WHERE id = ? AND tenant_id = ?")
        params.add(propertyId)
        params.add(tenantId)

        jdbcTemplate.update(sql.toString(), *params.toTypedArray())
        
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = "property.updated",
                resource = AuditResource("property", propertyId),
                after = mapOf("name" to request.name, "type" to request.type)
            )
        )
    }

    @Transactional(readOnly = true)
    override fun getProperty(propertyId: UUID): PropertyResponse? {
        val tenantId = resolveActiveTenantId()
        return jdbcTemplate.query(
            "SELECT id, tenant_id, name, location, code, type, status, is_active FROM properties WHERE id = ? AND tenant_id = ?",
            { rs, _ ->
                PropertyResponse(
                    id = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    name = rs.getString("name"),
                    location = rs.getString("location"),
                    code = rs.getString("code"),
                    type = rs.getString("type"),
                    status = rs.getString("status"),
                    isActive = rs.getBoolean("is_active")
                )
            },
            propertyId, tenantId
        ).firstOrNull()
    }

    @Transactional(readOnly = true)
    override fun listProperties(): List<PropertyResponse> {
        val tenantId = resolveActiveTenantId()
        return jdbcTemplate.query(
            "SELECT id, tenant_id, name, location, code, type, status, is_active FROM properties WHERE tenant_id = ? AND deleted_at IS NULL",
            { rs, _ ->
                PropertyResponse(
                    id = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    name = rs.getString("name"),
                    location = rs.getString("location"),
                    code = rs.getString("code"),
                    type = rs.getString("type"),
                    status = rs.getString("status"),
                    isActive = rs.getBoolean("is_active")
                )
            },
            tenantId
        )
    }

    @Transactional
    override fun deleteProperty(propertyId: UUID) {
        val tenantId = resolveActiveTenantId()
        jdbcTemplate.update(
            "UPDATE properties SET deleted_at = now(), status = 'archived', is_active = false WHERE id = ? AND tenant_id = ?",
            propertyId, tenantId
        )
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = "property.deleted",
                resource = AuditResource("property", propertyId)
            )
        )
    }

    @Transactional
    override fun suspendProperty(propertyId: UUID) {
        val tenantId = resolveActiveTenantId()
        jdbcTemplate.update(
            "UPDATE properties SET status = 'suspended', is_active = false WHERE id = ? AND tenant_id = ?",
            propertyId, tenantId
        )
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = "property.suspended",
                resource = AuditResource("property", propertyId)
            )
        )
    }

    @Transactional
    override fun archiveProperty(propertyId: UUID) {
        val tenantId = resolveActiveTenantId()
        jdbcTemplate.update(
            "UPDATE properties SET status = 'archived', is_active = false, deleted_at = now() WHERE id = ? AND tenant_id = ?",
            propertyId, tenantId
        )
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = "property.archived",
                resource = AuditResource("property", propertyId)
            )
        )
    }

    private fun resolveActiveTenantId(): UUID {
        val context = requestContextHolder.current()
        return when (val identity = context.identity) {
            is RequestIdentity.Tenant -> identity.tenantId
            else -> throw IllegalStateException("Security Violation: Action requires an active Tenant identity.")
        }
    }

    @Transactional
    fun createBuilding(propertyId: UUID, request: CreateBuildingRequest): UUID {
        val tenantId = resolveActiveTenantId()
        val buildingId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO buildings (id, tenant_id, property_id, name, description)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            buildingId,
            tenantId,
            propertyId,
            request.name,
            request.description
        )
        return buildingId
    }

    @Transactional
    fun createRoom(propertyId: UUID, request: CreateRoomRequest): UUID {
        val tenantId = resolveActiveTenantId()
        val roomId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO rooms (id, tenant_id, property_id, building_id, room_number, room_type_id, floor_number, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'AVAILABLE')
            """.trimIndent(),
            roomId,
            tenantId,
            propertyId,
            request.buildingId,
            request.roomNumber,
            request.roomTypeId,
            request.floorNumber
        )
        return roomId
    }

    @Transactional(readOnly = true)
    override fun checkReadiness(propertyId: UUID): PropertyReadinessResponse {
        val tenantId = resolveActiveTenantId()
        val missing = mutableListOf<String>()

        // 1. Verify Property Profile existence and active status
        val propertyInfo = jdbcTemplate.queryForMap(
            "SELECT status, is_active FROM properties WHERE id = ? AND tenant_id = ?",
            propertyId, tenantId
        )
        if (propertyInfo["status"] == "archived") missing.add("Property is archived.")
        if (propertyInfo["is_active"] == false && propertyInfo["status"] != "draft") missing.add("Property is inactive.")

        // 2. Verify existence of at least one Building
        val buildingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM buildings WHERE property_id = ? AND tenant_id = ?",
            Int::class.java, propertyId, tenantId
        ) ?: 0
        if (buildingCount == 0) missing.add("Property must have at least one building configured.")

        // 3. Verify existence of at least one Floor
        val floorCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM floors WHERE property_id = ? AND tenant_id = ?",
            Int::class.java, propertyId, tenantId
        ) ?: 0
        if (floorCount == 0) missing.add("Property must have at least one floor configured.")

        // 4. Verify existence of at least one Room Type
        val roomTypeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM room_types WHERE property_id = ? AND tenant_id = ?",
            Int::class.java, propertyId, tenantId
        ) ?: 0
        if (roomTypeCount == 0) missing.add("Property must have at least one room type configured.")

        // 5. Verify existence of at least one Room
        val roomCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM rooms WHERE property_id = ? AND tenant_id = ?",
            Int::class.java, propertyId, tenantId
        ) ?: 0
        if (roomCount == 0) missing.add("Property must have rooms allocated to buildings.")

        // 6. Verify Revenue Centers
        val revCenterCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM revenue_centers WHERE property_id = ? AND tenant_id = ?",
            Int::class.java, propertyId, tenantId
        ) ?: 0
        if (revCenterCount == 0) missing.add("Property must have at least one revenue center configured.")

        // 7. Verify Tax Setup (Tax Rates)
        val taxConfigured = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM tax_rates WHERE tenant_id = ? AND is_active = true)",
            Boolean::class.java, tenantId
        ) ?: false
        if (!taxConfigured) missing.add("Property lacks standard tax configuration records (VAT/Levies).")

        // 8. Verify Base Rates for all room types
        val roomTypesWithoutRates = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM room_types rt
            LEFT JOIN room_type_rates rtr ON rt.id = rtr.room_type_id
            WHERE rt.property_id = ? AND rt.tenant_id = ? AND rtr.room_type_id IS NULL
            """.trimIndent(),
            Int::class.java, propertyId, tenantId
        ) ?: 0
        if (roomTypesWithoutRates > 0) missing.add("All room types must have base rates configured.")

        // 9. Verify at least one business contact exists (from tenant_contacts)
        val contactExists = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM tenant_contacts WHERE tenant_id = ?)",
            Boolean::class.java, tenantId
        ) ?: false
        if (!contactExists) missing.add("Verified business contacts must be present for the tenant.")

        return PropertyReadinessResponse(
            propertyId = propertyId,
            isReady = missing.isEmpty(),
            missingRequirements = missing
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
                    after = mapOf("missing" to readiness.missingRequirements)
                )
            )
            throw IllegalStateException("Cannot activate property: structural setup criteria unmet.")
        }

        jdbcTemplate.update(
            "UPDATE properties SET status = 'ACTIVE' WHERE id = ? AND tenant_id = ?",
            propertyId, tenantId
        )

        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = "property.activated",
                resource = AuditResource("property", propertyId)
            )
        )

        return readiness
    }

    @Transactional
    fun updateRoomStatus(roomId: UUID, newStatus: String) {
        val tenantId = resolveActiveTenantId()

        // Validate the incoming status is a clean system status
        val validStatuses = listOf("AVAILABLE", "DIRTY", "MAINTENANCE", "SUSPENDED", "OUT_OF_ORDER")
        if (!validStatuses.contains(newStatus.uppercase())) {
            throw IllegalArgumentException("Invalid room lifecycle status: $newStatus")
        }

        // Execute the state change securely via raw JDBC templates
        val rowsUpdated = jdbcTemplate.update(
            """
        UPDATE rooms 
        SET status = ?, updated_at = now() 
        WHERE id = ? AND tenant_id = ?
        """.trimIndent(),
            newStatus.uppercase(),
            roomId,
            tenantId
        )

        if (rowsUpdated == 0) {
            throw NoSuchElementException("Room record not found or access denied.")
        }

        println("[Room State] Room $roomId updated to status: $newStatus")
    }

    @Transactional
    fun createFloor(propertyId: UUID, request: CreateFloorRequest): UUID {
        val tenantId = resolveActiveTenantId()
        val floorId = UUID.randomUUID()

        jdbcTemplate.update(
            """
                INSERT INTO floors (id, tenant_id, property_id, building_id, floor_number, name)
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            floorId,
            tenantId,
            propertyId,
            request.buildingId,
            request.floorNumber,
            request.name
        )
        return floorId
    }

    @Transactional
    fun createRoomType(propertyId: UUID, request: CreateRoomTypeRequest): UUID {
        val tenantId = resolveActiveTenantId()
        val roomTypeId = UUID.randomUUID()

        jdbcTemplate.update(
            """
                INSERT INTO room_types (id, tenant_id, property_id, name, code, baseCapacity)
              VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            roomTypeId,
            tenantId,
            propertyId,
            request.name,
            request.code,
            request.baseCapacity
        )
        return roomTypeId
    }

    @Transactional
    fun createRevenueCenter(propertyId: UUID, request: CreateRevenueCenterRequest): UUID {
        val tenantId = resolveActiveTenantId()
        val centerId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO revenue_centers (id, tenant_id, property_id, name, code)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            centerId,
            tenantId,
            propertyId,
            request.name,
            request.code
        )
        return centerId
    }

    @Transactional
    fun createDepartment(propertyId: UUID, request: CreateDepartmentRequest): UUID {
        val tenantId = resolveActiveTenantId()
        val deptId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO departments (id, tenant_id, property_id, name, code)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            deptId,
            tenantId,
            propertyId,
            request.name,
            request.code
        )
        return deptId
    }

    @Transactional
    fun setRoomTypeBaseRate(propertyId: UUID, request: SetBaseRateRequest) {
        val tenantId = resolveActiveTenantId()

        // Upsert style: Insert new price tag, or update it if it already exists
        jdbcTemplate.update(
            """
            INSERT INTO room_type_rates (tenant_id, property_id, room_type_id, base_amount, currency)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (property_id, room_type_id) 
            DO UPDATE SET base_amount = EXCLUDED.base_amount, currency = EXCLUDED.currency
            """.trimIndent(),
            tenantId,
            propertyId,
            request.roomTypeId,
            request.amount,
            request.currency
        )
        println("[Rate Configuration] Base rate for room type ${request.roomTypeId} set to ${request.amount} ${request.currency}")
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
            request.isInclusive
        )
        return taxRateId
    }

    @Transactional(readOnly = true)
    override fun listTaxRates(): List<TaxRateResponse> {
        val tenantId = resolveActiveTenantId()
        return jdbcTemplate.query(
            "SELECT id, name, code, rate, tax_type, is_compound, is_inclusive FROM tax_rates WHERE tenant_id = ? AND is_active = true",
            { rs, _ ->
                TaxRateResponse(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    code = rs.getString("code"),
                    rate = rs.getDouble("rate"),
                    taxType = rs.getString("tax_type"),
                    isCompound = rs.getBoolean("is_compound"),
                    isInclusive = rs.getBoolean("is_inclusive")
                )
            },
            tenantId
        )
    }

    @Transactional
    override fun enableModule(propertyId: UUID, moduleId: String) {
        val tenantId = resolveActiveTenantId()
        jdbcTemplate.update(
            """
            INSERT INTO property_modules (property_id, tenant_id, module_id, is_enabled)
            VALUES (?, ?, ?, true)
            ON CONFLICT (property_id, module_id) DO UPDATE SET is_enabled = true, updated_at = now()
            """.trimIndent(),
            propertyId, tenantId, moduleId
        )
    }

    @Transactional
    override fun disableModule(propertyId: UUID, moduleId: String) {
        val tenantId = resolveActiveTenantId()
        jdbcTemplate.update(
            "UPDATE property_modules SET is_enabled = false, updated_at = now() WHERE property_id = ? AND module_id = ? AND tenant_id = ?",
            propertyId, moduleId, tenantId
        )
    }

    @Transactional(readOnly = true)
    override fun listEnabledModules(propertyId: UUID): List<String> {
        val tenantId = resolveActiveTenantId()
        return jdbcTemplate.query(
            "SELECT module_id FROM property_modules WHERE property_id = ? AND tenant_id = ? AND is_enabled = true",
            { rs, _ -> rs.getString("module_id") },
            propertyId, tenantId
        )
    }
}