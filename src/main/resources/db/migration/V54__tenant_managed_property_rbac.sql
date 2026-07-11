-- ================================================================================
-- Tenant-managed property RBAC
-- ================================================================================

INSERT INTO permission_catalog (
    code,
    namespace,
    access_scope,
    description,
    is_platform_permission,
    is_tenant_permission
) VALUES
    (
        'tenant.properties.manage_access',
        'tenant',
        'tenant',
        'Manage property-scoped user access for properties owned by the tenant',
        false,
        true
    ),
    (
        'property.roles.view',
        'property',
        'property',
        'View property role templates and property-scoped user assignments',
        false,
        true
    ),
    (
        'property.roles.manage',
        'property',
        'property',
        'Manage property role templates and property-scoped user assignments',
        false,
        true
    )
ON CONFLICT (code) DO UPDATE SET
    namespace = EXCLUDED.namespace,
    access_scope = EXCLUDED.access_scope,
    description = EXCLUDED.description,
    is_platform_permission = EXCLUDED.is_platform_permission,
    is_tenant_permission = EXCLUDED.is_tenant_permission,
    updated_at = now();

INSERT INTO permissions (id, tenant_id, code, description)
SELECT gen_random_uuid(), t.id, pc.code, pc.description
FROM tenants t
JOIN permission_catalog pc
  ON pc.code IN (
      'tenant.properties.manage_access',
      'property.roles.view',
      'property.roles.manage'
  )
WHERE t.deleted_at IS NULL
ON CONFLICT (tenant_id, code) DO UPDATE SET
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT tr.id, manage_access.id
FROM tenant_roles tr
JOIN permissions manage_access
  ON manage_access.tenant_id = tr.tenant_id
 AND manage_access.code = 'tenant.properties.manage_access'
WHERE tr.is_active = true
  AND (
      tr.code = 'tenant_admin'
      OR EXISTS (
          SELECT 1
          FROM tenant_role_permissions trp
          JOIN permissions p
            ON p.id = trp.permission_id
           AND p.tenant_id = tr.tenant_id
          WHERE trp.tenant_role_id = tr.id
            AND p.code = 'tenant.admin.all'
      )
  )
ON CONFLICT ON CONSTRAINT tenant_role_permissions_pkey DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p
  ON p.tenant_id = r.tenant_id
 AND p.code IN ('property.roles.view', 'property.roles.manage')
WHERE r.is_active = true
  AND EXISTS (
      SELECT 1
      FROM role_permissions rp
      JOIN permissions admin_permission
        ON admin_permission.id = rp.permission_id
       AND admin_permission.tenant_id = r.tenant_id
      WHERE rp.role_id = r.id
        AND admin_permission.code = 'admin.all'
  )
ON CONFLICT ON CONSTRAINT role_permissions_pkey DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_roles_tenant_active_name
    ON roles (tenant_id, is_active, lower(name));

CREATE INDEX IF NOT EXISTS idx_user_property_roles_tenant_property_user
    ON user_property_roles (tenant_id, property_id, user_id);

CREATE INDEX IF NOT EXISTS idx_user_property_roles_tenant_role
    ON user_property_roles (tenant_id, role_id);

CREATE INDEX IF NOT EXISTS idx_role_permissions_role
    ON role_permissions (role_id);

INSERT INTO module_access_matrix (
    module_id,
    screen_key,
    screen_label,
    http_method,
    api_pattern,
    permission_code,
    route_scope,
    guard_mode,
    access_scope,
    is_tanzania_v1,
    is_enabled_by_default,
    notes
) VALUES
    (
        'tenant_admin',
        'tenant.properties.roles.list',
        'Property Roles',
        'GET',
        '/api/tenants/:tenantId/properties/:propertyId/roles',
        'tenant.properties.manage_access',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'List tenant-owned property role templates while validating the property belongs to the tenant'
    ),
    (
        'tenant_admin',
        'tenant.properties.roles.view',
        'Property Role',
        'GET',
        '/api/tenants/:tenantId/properties/:propertyId/roles/:propertyRoleId',
        'tenant.properties.manage_access',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'View a tenant-owned property role template in property-management context'
    ),
    (
        'tenant_admin',
        'tenant.properties.roles.create',
        'Create Property Role',
        'POST',
        '/api/tenants/:tenantId/properties/:propertyId/roles',
        'tenant.properties.manage_access',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Create a tenant-owned property role template for property-scoped assignments'
    ),
    (
        'tenant_admin',
        'tenant.properties.roles.update',
        'Update Property Role',
        'PUT',
        '/api/tenants/:tenantId/properties/:propertyId/roles/:propertyRoleId',
        'tenant.properties.manage_access',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Update a mutable tenant-owned property role template'
    ),
    (
        'tenant_admin',
        'tenant.properties.roles.deactivate',
        'Deactivate Property Role',
        'DELETE',
        '/api/tenants/:tenantId/properties/:propertyId/roles/:propertyRoleId',
        'tenant.properties.manage_access',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Deactivate a mutable tenant-owned property role template and remove active property assignments'
    ),
    (
        'tenant_admin',
        'tenant.properties.users.roles.list',
        'User Property Roles',
        'GET',
        '/api/tenants/:tenantId/properties/:propertyId/users/:userId/roles',
        'tenant.properties.manage_access',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'List property-scoped roles assigned to a tenant user for a property'
    ),
    (
        'tenant_admin',
        'tenant.properties.users.roles.assign',
        'Assign User Property Role',
        'POST',
        '/api/tenants/:tenantId/properties/:propertyId/users/:userId/roles/:propertyRoleId/assign',
        'tenant.properties.manage_access',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Assign a property role to a tenant user for one property'
    ),
    (
        'tenant_admin',
        'tenant.properties.users.roles.revoke',
        'Revoke User Property Role',
        'POST',
        '/api/tenants/:tenantId/properties/:propertyId/users/:userId/roles/:propertyRoleId/revoke',
        'tenant.properties.manage_access',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Revoke a property role from a tenant user for one property'
    )
ON CONFLICT (module_id, screen_key, http_method, api_pattern, permission_code)
DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    is_tanzania_v1 = EXCLUDED.is_tanzania_v1,
    is_enabled_by_default = EXCLUDED.is_enabled_by_default,
    notes = EXCLUDED.notes,
    updated_at = now();
