-- Tenant-governed property administrator continuity and recovery.

-- Tenants running V55-V62 could create a dynamic role with the reserved name
-- before their first property bootstrap. Preserve the role and assignments under
-- an unambiguous legacy name so bootstrap can create the canonical system role.
UPDATE roles
SET name = 'Property Administrator (Legacy ' || id::text || ')',
    updated_at = now()
WHERE lower(name) = lower('Property Administrator')
  AND is_system = false;

INSERT INTO permission_catalog (
    code,
    namespace,
    access_scope,
    description,
    is_platform_permission,
    is_tenant_permission
) VALUES (
    'tenant.properties.administrators.manage',
    'tenant',
    'tenant',
    'Appoint and revoke system property administrators without orphaning a property',
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
  ON pc.code = 'tenant.properties.administrators.manage'
WHERE t.deleted_at IS NULL
ON CONFLICT (tenant_id, code) DO UPDATE SET
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT tr.id, administrator_permission.id
FROM tenant_roles tr
JOIN permissions administrator_permission
  ON administrator_permission.tenant_id = tr.tenant_id
 AND administrator_permission.code = 'tenant.properties.administrators.manage'
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
        'tenant.properties.administrators.list',
        'Property Administrators',
        'GET',
        '/api/tenants/:tenantId/properties/:propertyId/administrators',
        'tenant.properties.roles.view',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'List system property administrator assignments and effective account access state'
    ),
    (
        'tenant_admin',
        'tenant.properties.administrators.assign',
        'Assign Property Administrator',
        'POST',
        '/api/tenants/:tenantId/properties/:propertyId/administrators/:userId/assign',
        'tenant.properties.administrators.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Appoint an active tenant user to the immutable system Property Administrator role'
    ),
    (
        'tenant_admin',
        'tenant.properties.administrators.revoke',
        'Revoke Property Administrator',
        'POST',
        '/api/tenants/:tenantId/properties/:propertyId/administrators/:userId/revoke',
        'tenant.properties.administrators.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Revoke a system Property Administrator assignment only when another active administrator remains'
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
