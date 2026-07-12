-- Administrative read routes must not require mutation-level permissions when
-- a narrower view permission exists. Method-specific GET rows/updates keep
-- legacy write routes intact while allowing read-only operational roles.

INSERT INTO permission_catalog (
    code,
    namespace,
    access_scope,
    description,
    is_platform_permission,
    is_tenant_permission
) VALUES
    (
        'module.view',
        'module',
        'tenant',
        'View tenant and property module activation state',
        false,
        true
    ),
    (
        'tenant.roles.view',
        'tenant',
        'tenant',
        'View tenant roles and tenant permission catalog',
        false,
        true
    ),
    (
        'tenant.properties.roles.view',
        'tenant',
        'tenant',
        'View tenant-managed property roles and property role assignments',
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
      'module.view',
      'tenant.roles.view',
      'tenant.properties.roles.view'
  )
WHERE t.deleted_at IS NULL
ON CONFLICT (tenant_id, code) DO UPDATE SET
    description = EXCLUDED.description,
    updated_at = now();

WITH inherited_view_permissions(view_permission_code, source_permission_code) AS (
    VALUES
        ('module.view', 'module.manage'),
        ('tenant.roles.view', 'tenant.users.manage'),
        ('tenant.properties.roles.view', 'tenant.properties.manage_access')
)
INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT DISTINCT tr.id, view_permission.id
FROM tenant_roles tr
JOIN tenant_role_permissions source_assignment
  ON source_assignment.tenant_role_id = tr.id
JOIN permissions source_permission
  ON source_permission.id = source_assignment.permission_id
 AND source_permission.tenant_id = tr.tenant_id
JOIN inherited_view_permissions inherited
  ON source_permission.code IN (
      inherited.source_permission_code,
      'tenant.admin.all'
  )
JOIN permissions view_permission
  ON view_permission.tenant_id = tr.tenant_id
 AND view_permission.code = inherited.view_permission_code
WHERE tr.is_active = true
ON CONFLICT ON CONSTRAINT tenant_role_permissions_pkey DO NOTHING;

UPDATE module_access_matrix
SET permission_code = 'module.view',
    notes = 'List tenant-level module enablement state without requiring module mutation permission',
    updated_at = now()
WHERE module_id = 'tenant_admin'
  AND screen_key = 'tenant.modules.list'
  AND http_method = 'GET'
  AND api_pattern = '/api/tenants/:tenantId/modules';

UPDATE module_access_matrix
SET permission_code = 'tenant.roles.view',
    notes = 'List tenant roles without requiring tenant role mutation permission',
    updated_at = now()
WHERE module_id = 'tenant_admin'
  AND screen_key = 'tenant.users.roles.list'
  AND http_method = 'GET'
  AND api_pattern = '/api/tenants/:tenantId/roles';

UPDATE module_access_matrix
SET permission_code = 'tenant.roles.view',
    notes = 'List tenant permission codes available for tenant role configuration without requiring mutation permission',
    updated_at = now()
WHERE module_id = 'tenant_admin'
  AND screen_key = 'tenant.users.permissions.list'
  AND http_method = 'GET'
  AND api_pattern = '/api/tenants/:tenantId/permissions';

UPDATE module_access_matrix
SET permission_code = 'tenant.roles.view',
    notes = 'View tenant role permission assignments without requiring tenant role mutation permission',
    updated_at = now()
WHERE module_id = 'tenant_admin'
  AND screen_key = 'tenant.roles.view'
  AND http_method = 'GET'
  AND api_pattern = '/api/tenants/:tenantId/roles/:tenantRoleId';

UPDATE module_access_matrix
SET permission_code = 'tenant.properties.roles.view',
    notes = 'List tenant-owned property role templates without requiring property access mutation permission',
    updated_at = now()
WHERE module_id = 'tenant_admin'
  AND screen_key = 'tenant.properties.roles.list'
  AND http_method = 'GET'
  AND api_pattern = '/api/tenants/:tenantId/properties/:propertyId/roles';

UPDATE module_access_matrix
SET permission_code = 'tenant.properties.roles.view',
    notes = 'View tenant-owned property role template without requiring property access mutation permission',
    updated_at = now()
WHERE module_id = 'tenant_admin'
  AND screen_key = 'tenant.properties.roles.view'
  AND http_method = 'GET'
  AND api_pattern = '/api/tenants/:tenantId/properties/:propertyId/roles/:propertyRoleId';

UPDATE module_access_matrix
SET permission_code = 'tenant.properties.roles.view',
    notes = 'List property-scoped role assignments without requiring property access mutation permission',
    updated_at = now()
WHERE module_id = 'tenant_admin'
  AND screen_key = 'tenant.properties.users.roles.list'
  AND http_method = 'GET'
  AND api_pattern = '/api/tenants/:tenantId/properties/:propertyId/users/:userId/roles';

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
        'platform_admin',
        'platform.tenants.view',
        'Platform Tenant',
        'GET',
        '/api/platform/tenants/:id',
        'platform.tenants.view',
        'platform',
        'platform_permission',
        'platform',
        true,
        true,
        'View tenant account and lifecycle state without requiring tenant lifecycle mutation permission'
    ),
    (
        'inventory',
        'inventory.recipes.list',
        'Recipes',
        'GET',
        '/api/properties/:propertyId/inventory/recipes',
        'inventory.view',
        'property',
        'staff_permission',
        'property',
        true,
        true,
        'List POS recipe mappings without requiring inventory mutation permission'
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
