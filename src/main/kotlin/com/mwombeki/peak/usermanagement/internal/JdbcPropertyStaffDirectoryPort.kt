package com.mwombeki.peak.usermanagement.internal

import com.mwombeki.peak.usermanagement.api.PropertyStaffDirectoryPort
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class JdbcPropertyStaffDirectoryPort(
    private val jdbcTemplate: JdbcTemplate,
) : PropertyStaffDirectoryPort {
    override fun isActivePropertyStaff(
        tenantId: UUID,
        propertyId: UUID,
        userId: UUID,
    ): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM users staff
                WHERE staff.tenant_id = ?
                  AND staff.id = ?
                  AND staff.is_active
                  AND staff.deleted_at IS NULL
                  AND (
                      EXISTS (
                          SELECT 1
                          FROM user_property_roles assignment
                          JOIN roles role
                            ON role.tenant_id = assignment.tenant_id
                           AND role.id = assignment.role_id
                           AND role.is_active
                          WHERE assignment.tenant_id = staff.tenant_id
                            AND assignment.user_id = staff.id
                            AND assignment.property_id = ?
                      )
                      OR EXISTS (
                          SELECT 1
                          FROM user_tenant_roles assignment
                          JOIN tenant_roles role
                            ON role.tenant_id = assignment.tenant_id
                           AND role.id = assignment.tenant_role_id
                           AND role.is_active
                          WHERE assignment.tenant_id = staff.tenant_id
                            AND assignment.user_id = staff.id
                            AND role.code = 'tenant_admin'
                      )
                  )
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            userId,
            propertyId,
        ) == true
    }
}
