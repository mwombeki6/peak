package com.mwombeki.peak.property.internal

import com.mwombeki.peak.property.api.PropertyModuleProjectionPort
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * The only writer of `property_modules` on the billing path.
 *
 * Runs on the caller's bound session, so row-level security still confines it to one
 * tenant. Nothing here elevates.
 */
@Component
class JdbcPropertyModuleProjection(
    private val jdbcTemplate: JdbcTemplate,
) : PropertyModuleProjectionPort {

    override fun activateModuleIfNeverActivated(
        tenantId: UUID,
        propertyId: UUID,
        moduleId: String,
    ): Boolean {
        // A conflict means this property already has a decision recorded for this module,
        // including a deliberate off. An automated process must not overturn it.
        return jdbcTemplate.update(
            """
            INSERT INTO property_modules (tenant_id, property_id, module_id, is_enabled, is_configured)
            VALUES (?, ?, ?, true, false)
            ON CONFLICT (tenant_id, property_id, module_id) DO NOTHING
            """.trimIndent(),
            tenantId,
            propertyId,
            moduleId,
        ) > 0
    }

    override fun deactivateModuleEverywhere(tenantId: UUID, moduleId: String): Int {
        return jdbcTemplate.update(
            """
            UPDATE property_modules
            SET is_enabled = false, updated_at = now()
            WHERE tenant_id = ? AND module_id = ? AND is_enabled = true
            """.trimIndent(),
            tenantId,
            moduleId,
        )
    }

    override fun deactivateModuleExcept(
        tenantId: UUID,
        moduleId: String,
        keepPropertyIds: Collection<UUID>,
    ): Int {
        if (keepPropertyIds.isEmpty()) {
            return deactivateModuleEverywhere(tenantId, moduleId)
        }
        return jdbcTemplate.update(
            """
            UPDATE property_modules
            SET is_enabled = false, updated_at = now()
            WHERE tenant_id = ? AND module_id = ? AND is_enabled = true
              AND property_id <> ALL(?)
            """.trimIndent(),
            tenantId,
            moduleId,
            keepPropertyIds.toTypedArray(),
        )
    }

    override fun propertiesWithModuleEnabled(tenantId: UUID, moduleId: String): Set<UUID> {
        return jdbcTemplate.query(
            """
            SELECT property_id FROM property_modules
            WHERE tenant_id = ? AND module_id = ? AND is_enabled = true
            """.trimIndent(),
            { rs, _ -> rs.getObject("property_id", UUID::class.java) },
            tenantId,
            moduleId,
        ).toSet()
    }
}
