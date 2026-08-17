package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.usermanagement.api.TenantModulePermissionBootstrapPort
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Materialises a module's permissions for a tenant and grants them to the tenant-admin role.
 *
 * Two steps, both additive:
 *
 * 1. `permission_catalog` is global; `permissions` is per tenant. A tenant that has never
 *    held POS has no `pos.*` rows, so they are created from the catalog first.
 * 2. Those permissions are attached to the system tenant-admin role, which is the only role
 *    that can be assumed to exist and to want them.
 *
 * Nothing is granted to any other role. Deciding that the night manager should also see POS
 * is an operator's judgement about their own organisation, not something a payment should
 * make on their behalf.
 */
@Component
class JdbcTenantModulePermissionBootstrap(
    private val jdbcTemplate: JdbcTemplate,
) : TenantModulePermissionBootstrapPort {

    override fun bootstrapModulePermissions(tenantId: UUID, moduleId: String): Int {
        // Derived from the route matrix, so what a module grants is exactly what its routes
        // require. ON CONFLICT DO NOTHING rather than DO UPDATE: this must never rewrite
        // the description of a permission a tenant already holds.
        jdbcTemplate.update(
            """
            INSERT INTO permissions (tenant_id, code, description)
            SELECT ?, catalog.code, catalog.description
            FROM permission_catalog catalog
            WHERE catalog.is_tenant_permission = true
              AND catalog.code IN (
                  SELECT DISTINCT matrix.permission_code
                  FROM module_access_matrix matrix
                  WHERE matrix.module_id = ?
                    AND matrix.permission_code IS NOT NULL
                    AND matrix.is_enabled_by_default = true
              )
            ON CONFLICT ON CONSTRAINT permissions_tenant_id_code_key DO NOTHING
            """.trimIndent(),
            tenantId,
            moduleId,
        )

        return jdbcTemplate.update(
            """
            INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
            SELECT role.id, permission.id
            FROM tenant_roles role
            JOIN permissions permission ON permission.tenant_id = role.tenant_id
            WHERE role.tenant_id = ?
              AND role.code = 'tenant_admin'
              AND role.is_system = true
              AND permission.code IN (
                  SELECT DISTINCT matrix.permission_code
                  FROM module_access_matrix matrix
                  WHERE matrix.module_id = ?
                    AND matrix.permission_code IS NOT NULL
                    AND matrix.is_enabled_by_default = true
              )
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            tenantId,
            moduleId,
        )
    }
}
